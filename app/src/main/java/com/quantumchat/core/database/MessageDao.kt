package com.quantumchat.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for chat message database operations.
 */
@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE (senderId = :contactId AND recipientId = 'me') OR (senderId = 'me' AND recipientId = :contactId) ORDER BY timestamp ASC")
    fun observeMessagesForContact(contactId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE senderId = :contactId OR recipientId = :contactId")
    suspend fun deleteMessagesForContact(contactId: String)
}
