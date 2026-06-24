package com.quantumchat.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room database entity representing the symmetric key ratchet state for a secure session.
 */
@Entity(tableName = "ratchet_states")
data class RatchetStateEntity(
    @PrimaryKey val contactFingerprint: String,
    val rootKey: ByteArray,
    val sendingChainKey: ByteArray,
    val receivingChainKey: ByteArray,
    val sendingMessageNumber: Int,
    val receivingMessageNumber: Int,
    val previousChainLength: Int,
    val localDhPublicKey: ByteArray,
    val localDhPrivateKey: ByteArray,
    val remoteDhPublicKey: ByteArray,
    val isFallback: Boolean,
    val pendingKyberCiphertext: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RatchetStateEntity

        if (contactFingerprint != other.contactFingerprint) return false
        if (!rootKey.contentEquals(other.rootKey)) return false
        if (!sendingChainKey.contentEquals(other.sendingChainKey)) return false
        if (!receivingChainKey.contentEquals(other.receivingChainKey)) return false
        if (sendingMessageNumber != other.sendingMessageNumber) return false
        if (receivingMessageNumber != other.receivingMessageNumber) return false
        if (previousChainLength != other.previousChainLength) return false
        if (!localDhPublicKey.contentEquals(other.localDhPublicKey)) return false
        if (!localDhPrivateKey.contentEquals(other.localDhPrivateKey)) return false
        if (!remoteDhPublicKey.contentEquals(other.remoteDhPublicKey)) return false
        if (isFallback != other.isFallback) return false
        if (pendingKyberCiphertext != null) {
            if (other.pendingKyberCiphertext == null) return false
            if (!pendingKyberCiphertext.contentEquals(other.pendingKyberCiphertext)) return false
        } else if (other.pendingKyberCiphertext != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = contactFingerprint.hashCode()
        result = 31 * result + rootKey.contentHashCode()
        result = 31 * result + sendingChainKey.contentHashCode()
        result = 31 * result + receivingChainKey.contentHashCode()
        result = 31 * result + sendingMessageNumber
        result = 31 * result + receivingMessageNumber
        result = 31 * result + previousChainLength
        result = 31 * result + localDhPublicKey.contentHashCode()
        result = 31 * result + localDhPrivateKey.contentHashCode()
        result = 31 * result + remoteDhPublicKey.contentHashCode()
        result = 31 * result + isFallback.hashCode()
        result = 31 * result + (pendingKyberCiphertext?.contentHashCode() ?: 0)
        return result
    }
}
