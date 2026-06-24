package com.quantumchat.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.quantumchat.core.common.model.Contact

/**
 * Room database entity representing a contact.
 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: String,
    val name: String,
    val publicKeyFingerprint: String,
    val isOnline: Boolean,
    val onionAddress: String?
)

/**
 * Map database entity to domain model.
 */
fun ContactEntity.toDomain(): Contact = Contact(
    id = id,
    name = name,
    publicKeyFingerprint = publicKeyFingerprint,
    isOnline = isOnline,
    onionAddress = onionAddress
)

/**
 * Map domain model to database entity.
 */
fun Contact.toEntity(): ContactEntity = ContactEntity(
    id = id,
    name = name,
    publicKeyFingerprint = publicKeyFingerprint,
    isOnline = isOnline,
    onionAddress = onionAddress
)
