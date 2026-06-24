package com.quantumchat.core.database

import androidx.room.Entity

/**
 * Room database entity representing derived message keys that were skipped due to out-of-order delivery.
 * This allows decrypting skipped messages if they arrive later, while maintaining forward secrecy once decrypted.
 */
@Entity(
    tableName = "skipped_message_keys",
    primaryKeys = ["contactFingerprint", "dhPublicKeyB64", "messageNumber"]
)
data class SkippedMessageKeyEntity(
    val contactFingerprint: String,
    val dhPublicKeyB64: String,
    val messageNumber: Int,
    val messageKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SkippedMessageKeyEntity

        if (contactFingerprint != other.contactFingerprint) return false
        if (dhPublicKeyB64 != other.dhPublicKeyB64) return false
        if (messageNumber != other.messageNumber) return false
        if (!messageKey.contentEquals(other.messageKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = contactFingerprint.hashCode()
        result = 31 * result + dhPublicKeyB64.hashCode()
        result = 31 * result + messageNumber
        result = 31 * result + messageKey.contentHashCode()
        return result
    }
}
