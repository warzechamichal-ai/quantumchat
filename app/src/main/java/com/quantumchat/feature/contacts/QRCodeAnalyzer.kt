package com.quantumchat.feature.contacts

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import timber.log.Timber

/**
 * CameraX Image Analyzer that runs Google ML Kit Barcode Scanner on every frame
 * to detect and decode QR Codes.
 */
class QRCodeAnalyzer(
    private val onQrCodeScanned: (String) -> Unit
) : ImageAnalysis.Analyzer {

    // Configure the scanner to ONLY detect QR Codes for performance optimization
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            // Convert Media.Image to ML Kit input format
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        barcode.rawValue?.let { qrValue ->
                            Timber.d("QR Code scanned successfully: $qrValue")
                            onQrCodeScanned(qrValue)
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "Error processing image for barcode scanning")
                }
                .addOnCompleteListener {
                    // Critical: Close the image proxy to release the frame buffer
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}
