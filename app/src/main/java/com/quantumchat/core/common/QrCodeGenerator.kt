package com.quantumchat.core.common

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import timber.log.Timber

/**
 * Utility for generating QR Codes as Bitmaps using ZXing.
 */
object QrCodeGenerator {

    /**
     * Generates a QR Code as a [Bitmap].
     *
     * @param content The text content to encode.
     * @param width The target bitmap width in pixels.
     * @param height The target bitmap height in pixels.
     * @return A [Bitmap] containing the QR code, or null if generation fails.
     */
    fun generateQrCode(content: String, width: Int, height: Int): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            Timber.e(e, "Error generating QR Code")
            null
        }
    }
}
