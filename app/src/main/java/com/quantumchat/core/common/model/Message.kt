package com.quantumchat.core.common.model

import kotlinx.serialization.Serializable

/**
 * Represents a chat message in the system.
 *
 * @property id Unique identifier of the message.
 * @property senderId Identifier of the sender contact.
 * @property recipientId Identifier of the recipient contact.
 * @property content The text content (decrypted in memory, or encrypted payload if not processed yet).
 * @property timestamp Epoch time in milliseconds.
 * @property isEncrypted Indicates if the message was sent/received via end-to-end encryption.
 */
@Serializable
enum class MessageStatus {
    SENT,
    DELIVERED,
    READ
}

@Serializable
data class Message(
    val id: String,
    val senderId: String,
    val recipientId: String,
    val content: String,
    val timestamp: Long,
    val isEncrypted: Boolean = true,
    val status: MessageStatus = MessageStatus.SENT
)
