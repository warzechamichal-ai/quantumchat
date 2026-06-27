package com.quantumchat.core.crypto

sealed class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class DecryptionFailed(message: String, cause: Throwable? = null) : CryptoException(message, cause)
    class EncryptionFailed(message: String, cause: Throwable? = null) : CryptoException(message, cause)
}
