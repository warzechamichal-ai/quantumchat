package com.quantumchat.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.KeyPair
import java.security.PublicKey

/**
 * Manages the symmetric and asymmetric key ratchet state for a secure session.
 * Tracks sending and receiving chains to rotate message keys per message.
 */
class RatchetState(
    var rootKey: ByteArray,
    var sendingChainKey: ByteArray,
    var receivingChainKey: ByteArray,
    var sendingMessageNumber: Int = 0,
    var receivingMessageNumber: Int = 0,
    var previousChainLength: Int = 0,
    var localDhKeyPair: KeyPair,
    var remoteDhPublicKey: PublicKey,
    val isFallback: Boolean,
    var pendingKyberCiphertext: ByteArray? = null
) {
    /**
     * Derives the next message key for sending and advances the sending chain key.
     */
    fun deriveNextSendingKey(): ByteArray {
        val mk = hmacSha256(sendingChainKey, byteArrayOf(1))
        sendingChainKey = hmacSha256(sendingChainKey, byteArrayOf(2))
        sendingMessageNumber++
        return mk
    }

    /**
     * Derives the next message key for receiving and advances the receiving chain key.
     */
    fun deriveNextReceivingKey(): ByteArray {
        val mk = hmacSha256(receivingChainKey, byteArrayOf(1))
        receivingChainKey = hmacSha256(receivingChainKey, byteArrayOf(2))
        receivingMessageNumber++
        return mk
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
