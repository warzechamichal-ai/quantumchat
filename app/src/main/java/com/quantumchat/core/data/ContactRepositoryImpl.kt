package com.quantumchat.core.data

import com.quantumchat.core.common.model.Contact
import com.quantumchat.core.database.ContactDao
import com.quantumchat.core.database.toDomain
import com.quantumchat.core.database.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

import timber.log.Timber

/**
 * Concrete implementation of [ContactRepository] interacting with Room local DB.
 */
class ContactRepositoryImpl @Inject constructor(
    private val contactDao: ContactDao
) : ContactRepository {

    override fun observeContacts(): Flow<List<Contact>> {
        return contactDao.observeContacts().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun addContact(contact: Contact) {
        contactDao.insertContact(contact.toEntity())
    }

    override suspend fun addContactIfNotExists(contact: Contact): Result<Unit> {
        val fingerprint = contact.publicKeyFingerprint
        val existing = contactDao.getContactByFingerprint(fingerprint)
        return if (existing != null) {
            Timber.w("Contact with fingerprint $fingerprint already exists, skipping")
            Result.failure(Exception("Contact with fingerprint $fingerprint already exists"))
        } else {
            contactDao.insertContact(contact.toEntity())
            Result.success(Unit)
        }
    }

    override suspend fun deleteContact(contact: Contact) {
        contactDao.deleteContact(contact.toEntity())
    }

    override suspend fun updateContact(contact: Contact) {
        contactDao.insertContact(contact.toEntity())
    }

    override suspend fun getContact(id: String): Contact? {
        return contactDao.getContactById(id)?.toDomain()
    }
}
