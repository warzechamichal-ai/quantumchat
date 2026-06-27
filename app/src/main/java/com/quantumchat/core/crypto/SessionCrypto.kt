package com.quantumchat.core.crypto

interface SessionCrypto {
    suspend fun generateSessionKey(): ByteArray
    suspend fun encryptMessage(plaintext: ByteArray, key: ByteArray): ByteArray
    suspend fun decryptMessage(ciphertext: ByteArray, key: ByteArray): ByteArray
}
