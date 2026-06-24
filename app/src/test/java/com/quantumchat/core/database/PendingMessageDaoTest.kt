package com.quantumchat.core.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingMessageDaoTest {

    private val dao = FakePendingMessageDao()

    @Test
    fun testInsertAndRetrieve() = runTest {
        val msg1 = PendingMessageEntity(
            id = 0,
            contactFingerprint = "FP1",
            encryptedPayload = byteArrayOf(1, 2, 3),
            createdAt = 1000L,
            expiresAt = 5000L
        )
        val msg2 = PendingMessageEntity(
            id = 0,
            contactFingerprint = "FP2",
            encryptedPayload = byteArrayOf(4, 5),
            createdAt = 2000L,
            expiresAt = 6000L
        )
        val msg3 = PendingMessageEntity(
            id = 0,
            contactFingerprint = "FP1",
            encryptedPayload = byteArrayOf(6, 7),
            createdAt = 500L, // earlier timestamp
            expiresAt = 4000L
        )

        dao.insert(msg1)
        dao.insert(msg2)
        dao.insert(msg3)

        // Verify FP1 pending messages are returned in ascending createdAt order
        val fp1Messages = dao.getPendingMessagesForContact("FP1").first()
        assertEquals(2, fp1Messages.size)
        assertEquals(500L, fp1Messages[0].createdAt)
        assertEquals(1000L, fp1Messages[1].createdAt)

        val allPending = dao.getAllPending()
        assertEquals(3, allPending.size)
    }

    @Test
    fun testDeleteById() = runTest {
        val msg = PendingMessageEntity(
            id = 10,
            contactFingerprint = "FP1",
            encryptedPayload = byteArrayOf(9),
            createdAt = 1000L,
            expiresAt = 5000L
        )
        dao.insert(msg)
        assertEquals(1, dao.getAllPending().size)

        dao.deleteById(10)
        assertTrue(dao.getAllPending().isEmpty())
    }

    @Test
    fun testUpdateRetryInfo() = runTest {
        val msg = PendingMessageEntity(
            id = 15,
            contactFingerprint = "FP1",
            encryptedPayload = byteArrayOf(9),
            createdAt = 1000L,
            expiresAt = 5000L,
            retryCount = 0,
            lastAttemptAt = null
        )
        dao.insert(msg)

        dao.updateRetryInfo(15, 3, 2000L)

        val pending = dao.getAllPending().first { it.id == 15L }
        assertEquals(3, pending.retryCount)
        assertEquals(2000L, pending.lastAttemptAt)
    }

    @Test
    fun testDeleteExpiredMessages() = runTest {
        val msg1 = PendingMessageEntity(
            id = 1,
            contactFingerprint = "FP1",
            encryptedPayload = byteArrayOf(1),
            createdAt = 1000L,
            expiresAt = 2000L // expired (before currentTime=2500)
        )
        val msg2 = PendingMessageEntity(
            id = 2,
            contactFingerprint = "FP1",
            encryptedPayload = byteArrayOf(2),
            createdAt = 1000L,
            expiresAt = 3000L // active
        )
        val msg3 = PendingMessageEntity(
            id = 3,
            contactFingerprint = "FP2",
            encryptedPayload = byteArrayOf(3),
            createdAt = 1000L,
            expiresAt = 1500L // expired
        )

        dao.insert(msg1)
        dao.insert(msg2)
        dao.insert(msg3)

        dao.deleteExpiredMessages(2500L)

        val remaining = dao.getAllPending()
        assertEquals(1, remaining.size)
        assertEquals(2L, remaining[0].id)
    }
}

class FakePendingMessageDao : PendingMessageDao {
    private val messages = mutableListOf<PendingMessageEntity>()
    private val _messagesFlow = MutableStateFlow<List<PendingMessageEntity>>(emptyList())

    private fun updateFlow() {
        _messagesFlow.value = messages.toList()
    }

    override fun getPendingMessagesForContact(fingerprint: String): Flow<List<PendingMessageEntity>> {
        return _messagesFlow.map { list ->
            list.filter { it.contactFingerprint == fingerprint }.sortedBy { it.createdAt }
        }
    }

    override suspend fun insert(message: PendingMessageEntity) {
        val finalMessage = if (message.id == 0L) {
            val nextId = (messages.maxOfOrNull { it.id } ?: 0L) + 1L
            message.copy(id = nextId)
        } else {
            message
        }
        messages.removeIf { it.id == finalMessage.id }
        messages.add(finalMessage)
        updateFlow()
    }

    override suspend fun deleteById(id: Long) {
        messages.removeIf { it.id == id }
        updateFlow()
    }

    override suspend fun updateRetryInfo(id: Long, retryCount: Int, lastAttemptAt: Long) {
        val index = messages.indexOfFirst { it.id == id }
        if (index != -1) {
            val existing = messages[index]
            messages[index] = existing.copy(retryCount = retryCount, lastAttemptAt = lastAttemptAt)
            updateFlow()
        }
    }

    override suspend fun getAllPending(): List<PendingMessageEntity> {
        return messages.sortedBy { it.createdAt }
    }

    override suspend fun deleteExpiredMessages(currentTime: Long) {
        messages.removeIf { it.expiresAt < currentTime }
        updateFlow()
    }
}
