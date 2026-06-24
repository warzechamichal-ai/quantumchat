package com.quantumchat.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room database entity representing a pending message to be sent offline.
 */
@Entity(tableName = "pending_messages")
data class PendingMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactFingerprint: String,
    val encryptedPayload: ByteArray,
    val createdAt: Long,
    val retryCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val expiresAt: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PendingMessageEntity

        if (id != other.id) return false
        if (contactFingerprint != other.contactFingerprint) return false
        if (!encryptedPayload.contentEquals(other.encryptedPayload)) return false
        if (createdAt != other.createdAt) return false
        if (retryCount != other.retryCount) return false
        if (lastAttemptAt != other.lastAttemptAt) return false
        if (expiresAt != other.expiresAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + contactFingerprint.hashCode()
        result = 31 * result + encryptedPayload.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + retryCount
        result = 31 * result + (lastAttemptAt?.hashCode() ?: 0)
        result = 31 * result + expiresAt.hashCode()
        return result
    }
}
