package com.quantumchat.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.quantumchat.core.common.model.Message

/**
 * Room database entity representing a chat message.
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val senderId: String,
    val recipientId: String,
    val content: String,
    val timestamp: Long,
    val isEncrypted: Boolean,
    val status: String
)

/**
 * Map database entity to domain model.
 */
fun MessageEntity.toDomain(): Message = Message(
    id = id,
    senderId = senderId,
    recipientId = recipientId,
    content = content,
    timestamp = timestamp,
    isEncrypted = isEncrypted,
    status = com.quantumchat.core.common.model.MessageStatus.valueOf(status)
)

/**
 * Map domain model to database entity.
 */
fun Message.toEntity(): MessageEntity = MessageEntity(
    id = id,
    senderId = senderId,
    recipientId = recipientId,
    content = content,
    timestamp = timestamp,
    isEncrypted = isEncrypted,
    status = status.name
)
