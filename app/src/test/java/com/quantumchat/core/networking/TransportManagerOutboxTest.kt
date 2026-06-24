package com.quantumchat.core.networking

import com.quantumchat.core.common.model.Contact
import com.quantumchat.core.crypto.CryptoManager
import com.quantumchat.core.data.ContactRepository
import com.quantumchat.core.database.MessageDao
import com.quantumchat.core.database.PendingMessageDao
import com.quantumchat.core.database.PendingMessageEntity
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransportManagerOutboxTest {

    private val localNetworkTransport = mockk<LocalNetworkTransport>(relaxed = true)
    private val webSocketTransport = mockk<WebSocketTransport>(relaxed = true)
    private val wiFiDirectTransport = mockk<WiFiDirectTransport>(relaxed = true)
    private val torTransport = mockk<TorTransport>(relaxed = true)
    private val localNetworkDiscovery = mockk<LocalNetworkDiscovery>(relaxed = true)
    private val mdnsDiscovery = mockk<MdnsDiscovery>(relaxed = true)
    private val contactRepository = mockk<ContactRepository>(relaxed = true)
    private val pendingMessageDao = mockk<PendingMessageDao>(relaxed = true)
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val cryptoManager = mockk<CryptoManager>(relaxed = true)

    private val lazyContactRepo = dagger.Lazy { contactRepository }
    private val lazyPendingDao = dagger.Lazy { pendingMessageDao }
    private val lazyMessageDao = dagger.Lazy { messageDao }

    private lateinit var manager: TransportManager

    private val targetFingerprint = "QC-PQ-TARGET"
    private val testContact = Contact("id1", "Alice", targetFingerprint, true, "alice.onion:9095")

    @Before
    fun setUp() {
        clearAllMocks()
        every { cryptoManager.getLocalIdentityFingerprint() } returns "QC-PQ-ME"
        every { contactRepository.observeContacts() } returns flowOf(listOf(testContact))
        
        manager = TransportManager(
            localNetworkTransport,
            webSocketTransport,
            wiFiDirectTransport,
            torTransport,
            localNetworkDiscovery,
            mdnsDiscovery,
            lazyContactRepo,
            lazyPendingDao,
            lazyMessageDao,
            cryptoManager
        )
    }

    @Test
    fun testQueueOffline() = runTest {
        // Mock all transports to fail to connect
        coEvery { torTransport.connect(any()) } returns false
        coEvery { torTransport.isConnected } returns false
        coEvery { webSocketTransport.isConnected } returns false

        // Connect attempts fail
        manager.connect(targetFingerprint)

        // Try to send a message
        val data = byteArrayOf(1, 2, 3)
        val success = manager.send(data)

        // It should queue offline and return true
        assertTrue(success)
        coVerify { 
            pendingMessageDao.insert(match { 
                it.contactFingerprint == targetFingerprint &&
                it.expiresAt > System.currentTimeMillis() + 13 * 24 * 60 * 60 * 1000L
            }) 
        }
    }

    @Test
    fun testDispatchQueueOnConnect() = runTest {
        // Mock pending messages for target fingerprint
        val pendingMsg = PendingMessageEntity(
            id = 42,
            contactFingerprint = targetFingerprint,
            encryptedPayload = byteArrayOf(7, 8, 9),
            createdAt = System.currentTimeMillis(),
            retryCount = 0
        )
        every { pendingMessageDao.getPendingMessagesForContact(targetFingerprint) } returns flowOf(listOf(pendingMsg))

        // Mock Tor transport connection to succeed
        coEvery { torTransport.connect("alice.onion:9095") } returns true
        coEvery { torTransport.isConnected } returns true
        coEvery { torTransport.send(any()) } returns true

        // Call connect, which should trigger queue dispatching
        manager.connect(targetFingerprint)

        // Verify it was dispatched and deleted from outbox
        coVerify { torTransport.send(match { it.contentEquals(pendingMsg.encryptedPayload) }) }
        coVerify { pendingMessageDao.deleteById(42) }
    }

    @Test
    fun testMaxRetryCountDeletion() = runTest {
        // Mock pending message that exceeded retries
        val deadMsg = PendingMessageEntity(
            id = 99,
            contactFingerprint = targetFingerprint,
            encryptedPayload = byteArrayOf(7, 8, 9),
            createdAt = System.currentTimeMillis(),
            retryCount = 5
        )
        every { pendingMessageDao.getPendingMessagesForContact(targetFingerprint) } returns flowOf(listOf(deadMsg))

        // Mock Tor transport connection to succeed
        coEvery { torTransport.connect("alice.onion:9095") } returns true
        coEvery { torTransport.isConnected } returns true

        // Connect, triggering dispatch
        manager.connect(targetFingerprint)

        // Verify it was deleted without attempting to send
        coVerify(exactly = 0) { torTransport.send(any()) }
        coVerify { pendingMessageDao.deleteById(99) }
    }

    @Test
    fun testRetryCountIncrementedOnFailure() = runTest {
        // Mock pending message that fails to send
        val pendingMsg = PendingMessageEntity(
            id = 50,
            contactFingerprint = targetFingerprint,
            encryptedPayload = byteArrayOf(7, 8, 9),
            createdAt = System.currentTimeMillis(),
            retryCount = 2
        )
        every { pendingMessageDao.getPendingMessagesForContact(targetFingerprint) } returns flowOf(listOf(pendingMsg))

        // Mock Tor transport connection to succeed but sending fails
        coEvery { torTransport.connect("alice.onion:9095") } returns true
        coEvery { torTransport.isConnected } returns true
        coEvery { torTransport.send(any()) } returns false

        // Connect, triggering dispatch
        manager.connect(targetFingerprint)

        // Verify it was NOT deleted, and retry info was updated/incremented
        coVerify(exactly = 0) { pendingMessageDao.deleteById(50) }
        coVerify { pendingMessageDao.updateRetryInfo(50, 3, any()) }
    }
}
