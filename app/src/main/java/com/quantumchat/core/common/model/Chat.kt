package com.quantumchat.core.common.model

import kotlinx.serialization.Serializable

/**
 * Represents a chat conversation containing metadata and the last message.
 *
 * @property id Unique identifier of the conversation.
 * @property name Title of the conversation (e.g. name of the contact).
 * @property lastMessage The last message received or sent in this chat.
 * @property unreadCount Number of unread messages for the local user.
 */
@Serializable
data class Chat(
    val id: String,
    val name: String,
    val lastMessage: Message? = null,
    val unreadCount: Int = 0
)
