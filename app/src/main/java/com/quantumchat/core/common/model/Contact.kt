package com.quantumchat.core.common.model

import kotlinx.serialization.Serializable

/**
 * Represents a contact in the secure messaging system.
 *
 * @property id Unique identifier of the contact.
 * @property name Human-readable name.
 * @property publicKeyFingerprint Hex or Base64 fingerprint of the contact's public identity key.
 * @property isOnline Connection status of the contact.
 */
@Serializable
data class Contact(
    val id: String,
    val name: String,
    val publicKeyFingerprint: String,
    val isOnline: Boolean = false
)
