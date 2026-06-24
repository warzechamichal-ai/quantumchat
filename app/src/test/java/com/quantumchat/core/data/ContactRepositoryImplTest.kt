package com.quantumchat.core.data

import com.quantumchat.core.common.model.Contact
import com.quantumchat.core.database.ContactDao
import com.quantumchat.core.database.ContactEntity
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactRepositoryImplTest {

    private val contactDao: ContactDao = mockk(relaxed = true)
    private val repository = ContactRepositoryImpl(contactDao)

    @Test
    fun observeContacts_mapsEntitiesToDomain() = runTest {
        val entities = listOf(
            ContactEntity("1", "Alice", "FINGERPRINT1", true, null),
            ContactEntity("2", "Bob", "FINGERPRINT2", false, null)
        )
        every { contactDao.observeContacts() } returns flowOf(entities)

        val result = repository.observeContacts().first()

        assertEquals(2, result.size)
        assertEquals("Alice", result[0].name)
        assertEquals("Bob", result[1].name)
    }

    @Test
    fun addContact_callsDaoInsert() = runTest {
        val contact = Contact("1", "Alice", "FINGERPRINT1", true)
        
        repository.addContact(contact)

        coVerify { contactDao.insertContact(any()) }
    }

    @Test
    fun deleteContact_callsDaoDelete() = runTest {
        val contact = Contact("1", "Alice", "FINGERPRINT1", true)

        repository.deleteContact(contact)

        coVerify { contactDao.deleteContact(any()) }
    }

    @Test
    fun addContactIfNotExists_whenContactDoesNotExist_callsDaoInsertAndReturnsSuccess() = runTest {
        val contact = Contact("1", "Alice", "FINGERPRINT1", true)
        coEvery { contactDao.getContactByFingerprint("FINGERPRINT1") } returns null

        val result = repository.addContactIfNotExists(contact)

        coVerify { contactDao.insertContact(any()) }
        org.junit.Assert.assertTrue(result.isSuccess)
    }

    @Test
    fun addContactIfNotExists_whenContactAlreadyExists_skipsDaoInsertAndReturnsFailure() = runTest {
        val contact = Contact("1", "Alice", "FINGERPRINT1", true)
        val existingEntity = ContactEntity("2", "Alice Existing", "FINGERPRINT1", true, null)
        coEvery { contactDao.getContactByFingerprint("FINGERPRINT1") } returns existingEntity

        val result = repository.addContactIfNotExists(contact)

        coVerify(exactly = 0) { contactDao.insertContact(any()) }
        org.junit.Assert.assertTrue(result.isFailure)
    }
}
