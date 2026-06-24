package com.quantumchat.feature.contacts

import com.quantumchat.core.common.model.Contact
import com.quantumchat.core.crypto.CryptoManager
import com.quantumchat.core.crypto.VerifiedContactData
import com.quantumchat.core.data.ContactRepository
import com.quantumchat.core.networking.TransportManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelTest {

    private val contactRepository: ContactRepository = mockk(relaxed = true)
    private val cryptoManager: CryptoManager = mockk(relaxed = true)
    private val transportManager: TransportManager = mockk(relaxed = true)
    
    private val testDispatcher = StandardTestDispatcher()
    private val contactsFlow = MutableStateFlow<List<Contact>>(emptyList())
    private val onlinePeersFlow = MutableStateFlow<Set<String>>(emptySet())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { contactRepository.observeContacts() } returns contactsFlow
        every { transportManager.onlinePeers } returns onlinePeersFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun init_observesContacts() = runTest(testDispatcher) {
        val viewModel = ContactsViewModel(contactRepository, cryptoManager, transportManager)
        
        // At start, list is empty
        assertEquals(emptyList<Contact>(), viewModel.state.value.contacts)

        // Emit new contacts
        val list = listOf(Contact("1", "Alice", "FP", false))
        contactsFlow.value = list
        testScheduler.advanceUntilIdle()

        assertEquals(list, viewModel.state.value.contacts)
    }

    @Test
    fun addContact_callsRepositoryAdd() = runTest(testDispatcher) {
        val viewModel = ContactsViewModel(contactRepository, cryptoManager, transportManager)
        every { cryptoManager.generateContactFingerprint() } returns "FP_NEW"
        
        viewModel.handleIntent(ContactsUiIntent.AddContact("Dave", "FP_DAVE", "192.168.1.100:9090"))
        testScheduler.advanceUntilIdle()

        coVerify { contactRepository.addContact(match { 
            it.name == "Dave" && it.publicKeyFingerprint == "FP_DAVE" && it.onionAddress == "192.168.1.100:9090"
        }) }
    }

    @Test
    fun updateContact_callsRepositoryUpdate() = runTest(testDispatcher) {
        val viewModel = ContactsViewModel(contactRepository, cryptoManager, transportManager)
        val contact = Contact("1", "Alice Edited", "FP", false, "10.0.2.2:9090")
        
        viewModel.handleIntent(ContactsUiIntent.UpdateContact(contact))
        testScheduler.advanceUntilIdle()

        coVerify { contactRepository.updateContact(contact) }
    }

    @Test
    fun deleteContact_callsRepositoryDeleteAndPurgesCrypto() = runTest(testDispatcher) {
        val viewModel = ContactsViewModel(contactRepository, cryptoManager, transportManager)
        val contact = Contact("1", "Alice", "FP", false)
        
        viewModel.handleIntent(ContactsUiIntent.DeleteContact(contact))
        testScheduler.advanceUntilIdle()

        coVerify { contactRepository.deleteContact(contact) }
        coVerify { cryptoManager.deleteSession("FP") }
    }

    @Test
    fun processScannedQR_success_addsVerifiedContact() = runTest(testDispatcher) {
        val viewModel = ContactsViewModel(contactRepository, cryptoManager, transportManager)
        val qrContent = "QC-IDENTITY:FP_QR:DEV-MODE"
        val verifiedData = VerifiedContactData("Device", "FP_QR")
        
        every { cryptoManager.extractContactFromQR(qrContent) } returns verifiedData
        
        viewModel.handleIntent(ContactsUiIntent.ProcessScannedQR(qrContent))
        testScheduler.advanceUntilIdle()

        coVerify { contactRepository.addContact(any()) }
        assertEquals("Successfully verified and added contact.", viewModel.state.value.lastScanResultMessage)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun processScannedQR_failure_setsError() = runTest(testDispatcher) {
        val viewModel = ContactsViewModel(contactRepository, cryptoManager, transportManager)
        val qrContent = "INVALID"
        
        every { cryptoManager.extractContactFromQR(qrContent) } returns null
        
        viewModel.handleIntent(ContactsUiIntent.ProcessScannedQR(qrContent))
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 0) { contactRepository.addContact(any()) }
        assertTrue(viewModel.state.value.error is ContactsError.QrVerificationFailed)
        assertNull(viewModel.state.value.lastScanResultMessage)
    }
}
