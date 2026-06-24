package com.quantumchat.core.database

import androidx.room.*

/**
 * Data Access Object (DAO) for managing persisted ratchet state entities.
 */
@Dao
interface RatchetStateDao {

    @Query("SELECT * FROM ratchet_states WHERE contactFingerprint = :contactFingerprint LIMIT 1")
    suspend fun getRatchetState(contactFingerprint: String): RatchetStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(state: RatchetStateEntity)

    @Query("DELETE FROM ratchet_states WHERE contactFingerprint = :contactFingerprint")
    suspend fun deleteRatchetState(contactFingerprint: String)
}
