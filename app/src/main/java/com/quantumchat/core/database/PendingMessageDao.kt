package com.quantumchat.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for managing pending offline messages.
 */
@Dao
interface PendingMessageDao {
    @Query("SELECT * FROM pending_messages WHERE contactFingerprint = :fingerprint ORDER BY createdAt ASC")
    fun getPendingMessagesForContact(fingerprint: String): Flow<List<PendingMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: PendingMessageEntity)

    @Query("DELETE FROM pending_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE pending_messages SET retryCount = :retryCount, lastAttemptAt = :lastAttemptAt WHERE id = :id")
    suspend fun updateRetryInfo(id: Long, retryCount: Int, lastAttemptAt: Long)

    @Query("SELECT * FROM pending_messages ORDER BY createdAt ASC")
    suspend fun getAllPending(): List<PendingMessageEntity>

    @Query("DELETE FROM pending_messages WHERE expiresAt < :currentTime")
    suspend fun deleteExpiredMessages(currentTime: Long)
}
