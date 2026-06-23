package com.quantumchat.core.data

import com.quantumchat.core.common.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing contacts data flow.
 */
interface ContactRepository {
    /**
     * Observes the list of contacts.
     */
    fun observeContacts(): Flow<List<Contact>>

    /**
     * Adds or updates a contact.
     */
    suspend fun addContact(contact: Contact)

    /**
     * Deletes a contact.
     */
    suspend fun deleteContact(contact: Contact)
}
