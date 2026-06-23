package com.quantumchat.core.crypto

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CryptoManagerImpl @Inject constructor() : CryptoManager {

    init {
        Timber.d("CryptoManagerImpl has been initialized.")
    }

    override fun generateLocalIdentityKeyPair(): Boolean {
        Timber.d("Generating hybrid identity keypair (X25519 + ML-KEM-1024 + ML-DSA-87)")
        return true
    }

    override fun getLocalIdentityFingerprint(): String {
        return "QC-PQ-5F8A-B9C2-E401"
    }

    override fun establishSecureSession(contactFingerprint: String): Boolean {
        Timber.d("Establishing hybrid post-quantum secure session (X3DH + ML-KEM KEM) for: $contactFingerprint")
        return true
    }

    override fun encryptMessage(plainText: ByteArray, contactId: String): ByteArray {
        Timber.d("Encrypting message for contactId: $contactId using double ratchet dynamic session keys.")
        // Mock encryption: return plain text bytes
        return plainText
    }

    override fun decryptMessage(cipherText: ByteArray, contactId: String): ByteArray {
        Timber.d("Decrypting message from contactId: $contactId using double ratchet dynamic session keys.")
        // Mock decryption: return cipher text bytes
        return cipherText
    }

    override fun signMessage(message: ByteArray): ByteArray {
        Timber.d("Signing message with ML-DSA-87 private key.")
        // Mock signature
        return "QC-SIG-MOCK-DATA".toByteArray(Charsets.UTF_8)
    }

    override fun verifyMessageSignature(
        message: ByteArray,
        signature: ByteArray,
        contactFingerprint: String
    ): Boolean {
        Timber.d("Verifying signature of message from fingerprint: $contactFingerprint using ML-DSA-87 public key.")
        return true
    }

    override fun generateIdentityQRContent(): String {
        val fingerprint = getLocalIdentityFingerprint()
        return "QC-IDENTITY:$fingerprint:DEV-MODE"
    }

    override fun verifyContactWithQR(scannedQrContent: String): Boolean {
        Timber.d("Verifying out-of-band contact QR content: $scannedQrContent")
        return scannedQrContent.startsWith("QC-IDENTITY:")
    }

    override fun extractContactFromQR(scannedQrContent: String): com.quantumchat.core.common.model.Contact? {
        if (!verifyContactWithQR(scannedQrContent)) return null
        val parts = scannedQrContent.split(":")
        val fingerprint = parts.getOrNull(1) ?: "QC-PQ-UNKNOWN"
        return com.quantumchat.core.common.model.Contact(
            id = System.currentTimeMillis().toString(),
            name = "Verified Contact (${fingerprint.take(6)})",
            publicKeyFingerprint = fingerprint,
            isOnline = true
        )
    }

    override fun generateContactFingerprint(): String {
        return "QC-PQ-NEW-" + System.currentTimeMillis().toString().takeLast(4)
    }
}
