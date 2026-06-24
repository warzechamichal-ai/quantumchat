package com.quantumchat.core.database

import androidx.room.*

/**
 * Data Access Object (DAO) for managing skipped message keys.
 */
@Dao
interface SkippedMessageKeyDao {

    @Query("SELECT * FROM skipped_message_keys WHERE contactFingerprint = :contactFingerprint AND dhPublicKeyB64 = :dhPublicKeyB64 AND messageNumber = :messageNumber LIMIT 1")
    suspend fun getSkippedMessageKey(
        contactFingerprint: String,
        dhPublicKeyB64: String,
        messageNumber: Int
    ): SkippedMessageKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkippedMessageKey(skippedKey: SkippedMessageKeyEntity)

    @Query("DELETE FROM skipped_message_keys WHERE contactFingerprint = :contactFingerprint AND dhPublicKeyB64 = :dhPublicKeyB64 AND messageNumber = :messageNumber")
    suspend fun deleteSkippedMessageKey(
        contactFingerprint: String,
        dhPublicKeyB64: String,
        messageNumber: Int
    )

    @Query("DELETE FROM skipped_message_keys WHERE contactFingerprint = :contactFingerprint")
    suspend fun deleteSkippedMessageKeysForContact(contactFingerprint: String)
}
