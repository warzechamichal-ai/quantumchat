package com.quantumchat.core.crypto

import timber.log.Timber
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimpleSessionCrypto @Inject constructor() : SessionCrypto {

    override suspend fun generateSessionKey(): ByteArray {
        val key = ByteArray(32) // 256-bit key
        SecureRandom().nextBytes(key)
        Timber.i("SimpleSessionCrypto: Generated session key of length: ${key.size} bytes")
        return key
    }

    override suspend fun encryptMessage(plaintext: ByteArray, key: ByteArray): ByteArray {
        try {
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv)
            val spec = GCMParameterSpec(128, iv)
            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
            val ciphertext = cipher.doFinal(plaintext)
            
            val result = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, result, 0, iv.size)
            System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)
            
            Timber.i("SimpleSessionCrypto: Succeeded encrypting message. Plaintext size: ${plaintext.size} bytes, Output size (with IV): ${result.size} bytes")
            return result
        } catch (e: Exception) {
            Timber.e(e, "SimpleSessionCrypto: Failed to encrypt message")
            throw CryptoException.EncryptionFailed("Encryption failed", e)
        }
    }

    override suspend fun decryptMessage(ciphertext: ByteArray, key: ByteArray): ByteArray {
        Timber.i("SimpleSessionCrypto: Decrypting message of size: ${ciphertext.size} bytes")
        if (ciphertext.size < 12) {
            val errMsg = "Ciphertext too short: ${ciphertext.size} bytes"
            Timber.e("SimpleSessionCrypto: Decryption failed: $errMsg")
            throw CryptoException.DecryptionFailed(errMsg)
        }
        try {
            val iv = ciphertext.copyOfRange(0, 12)
            val encryptedData = ciphertext.copyOfRange(12, ciphertext.size)
            
            val spec = GCMParameterSpec(128, iv)
            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val plaintext = cipher.doFinal(encryptedData)
            
            Timber.i("SimpleSessionCrypto: Succeeded decrypting message. Plaintext output size: ${plaintext.size} bytes")
            return plaintext
        } catch (e: Exception) {
            Timber.e(e, "SimpleSessionCrypto: Decryption failed")
            throw CryptoException.DecryptionFailed("Decryption failed", e)
        }
    }
}
