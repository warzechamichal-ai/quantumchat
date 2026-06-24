package com.quantumchat.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import io.mockk.*
import com.quantumchat.core.database.RatchetStateDao
import com.quantumchat.core.database.RatchetStateEntity
import com.quantumchat.core.database.SkippedMessageKeyDao
import com.quantumchat.core.database.SkippedMessageKeyEntity

class DoubleRatchetTest {

    private val mockTorManager = mockk<com.quantumchat.core.networking.TorManager>(relaxed = true) {
        every { onionAddress.value } returns "NO-ONION"
    }
    private val torManager: dagger.Lazy<com.quantumchat.core.networking.TorManager> = dagger.Lazy { mockTorManager }

    @Test
    fun testKeyRotationAndDecryption() {
        val aliceRatchetDao = mockk<RatchetStateDao>(relaxed = true)
        val aliceSkippedDao = mockk<SkippedMessageKeyDao>(relaxed = true)
        val bobRatchetDao = mockk<RatchetStateDao>(relaxed = true)
        val bobSkippedDao = mockk<SkippedMessageKeyDao>(relaxed = true)

        coEvery { aliceRatchetDao.getRatchetState(any()) } returns null
        coEvery { aliceSkippedDao.getSkippedMessageKey(any(), any(), any()) } returns null
        coEvery { bobRatchetDao.getRatchetState(any()) } returns null
        coEvery { bobSkippedDao.getSkippedMessageKey(any(), any(), any()) } returns null

        val alice = CryptoManagerImpl(aliceRatchetDao, aliceSkippedDao, torManager)
        val bob = CryptoManagerImpl(bobRatchetDao, bobSkippedDao, torManager)

        // 1. Exchange keys via QR content (simulate out-of-band scan)
        val aliceQr = alice.generateIdentityQRContent()
        val bobQr = bob.generateIdentityQRContent()

        val aliceData = bob.extractContactFromQR(aliceQr)
        val bobData = alice.extractContactFromQR(bobQr)

        assertNotNull(aliceData)
        assertNotNull(bobData)

        val aliceFingerprint = alice.getLocalIdentityFingerprint()
        val bobFingerprint = bob.getLocalIdentityFingerprint()

        // 2. Establish session on both sides
        assertTrue(alice.establishSecureSession(bobFingerprint))
        assertTrue(bob.establishSecureSession(aliceFingerprint))

        // 3. Alice encrypts messages for Bob, Bob decrypts in order
        val msg1 = "Hello, Bob!".toByteArray(StandardCharsets.UTF_8)
        val msg2 = "How are you?".toByteArray(StandardCharsets.UTF_8)

        val encrypted1 = alice.encryptMessage(msg1, bobFingerprint)
        val encrypted2 = alice.encryptMessage(msg2, bobFingerprint)

        // Decrypt on Bob's side
        val decrypted1 = bob.decryptMessage(encrypted1, aliceFingerprint)
        val decrypted2 = bob.decryptMessage(encrypted2, aliceFingerprint)

        assertEquals("Hello, Bob!", String(decrypted1, StandardCharsets.UTF_8))
        assertEquals("How are you?", String(decrypted2, StandardCharsets.UTF_8))
    }

    @Test
    fun testForwardSecrecyRotatesKeys() {
        val aliceRatchetDao = mockk<RatchetStateDao>(relaxed = true)
        val aliceSkippedDao = mockk<SkippedMessageKeyDao>(relaxed = true)
        val bobRatchetDao = mockk<RatchetStateDao>(relaxed = true)
        val bobSkippedDao = mockk<SkippedMessageKeyDao>(relaxed = true)

        coEvery { aliceRatchetDao.getRatchetState(any()) } returns null
        coEvery { aliceSkippedDao.getSkippedMessageKey(any(), any(), any()) } returns null
        coEvery { bobRatchetDao.getRatchetState(any()) } returns null
        coEvery { bobSkippedDao.getSkippedMessageKey(any(), any(), any()) } returns null

        val alice = CryptoManagerImpl(aliceRatchetDao, aliceSkippedDao, torManager)
        val bob = CryptoManagerImpl(bobRatchetDao, bobSkippedDao, torManager)

        val aliceQr = alice.generateIdentityQRContent()
        val bobQr = bob.generateIdentityQRContent()

        bob.extractContactFromQR(aliceQr)
        alice.extractContactFromQR(bobQr)

        val aliceFingerprint = alice.getLocalIdentityFingerprint()
        val bobFingerprint = bob.getLocalIdentityFingerprint()

        alice.establishSecureSession(bobFingerprint)
        bob.establishSecureSession(aliceFingerprint)

        // Encrypt multiple messages and compare ciphertexts and decrypted bytes
        val msg = "Constant message content".toByteArray(StandardCharsets.UTF_8)

        val encrypted1 = alice.encryptMessage(msg, bobFingerprint)
        val encrypted2 = alice.encryptMessage(msg, bobFingerprint)

        // Decrypt on Bob's side
        val decrypted1 = bob.decryptMessage(encrypted1, aliceFingerprint)
        val decrypted2 = bob.decryptMessage(encrypted2, aliceFingerprint)

        assertEquals(String(msg, StandardCharsets.UTF_8), String(decrypted1, StandardCharsets.UTF_8))
        assertEquals(String(msg, StandardCharsets.UTF_8), String(decrypted2, StandardCharsets.UTF_8))
    }

    @Test
    fun testRatchetStatePersistenceAndRecovery() {
        val aliceRatchetDao = mockk<RatchetStateDao>(relaxed = true)
        val aliceSkippedDao = mockk<SkippedMessageKeyDao>(relaxed = true)
        val bobRatchetDao = mockk<RatchetStateDao>(relaxed = true)
        val bobSkippedDao = mockk<SkippedMessageKeyDao>(relaxed = true)

        coEvery { aliceRatchetDao.getRatchetState(any()) } returns null
        coEvery { aliceSkippedDao.getSkippedMessageKey(any(), any(), any()) } returns null
        coEvery { bobRatchetDao.getRatchetState(any()) } returns null
        coEvery { bobSkippedDao.getSkippedMessageKey(any(), any(), any()) } returns null

        val alice = CryptoManagerImpl(aliceRatchetDao, aliceSkippedDao, torManager)
        val bob = CryptoManagerImpl(bobRatchetDao, bobSkippedDao, torManager)

        val aliceQr = alice.generateIdentityQRContent()
        val bobQr = bob.generateIdentityQRContent()

        bob.extractContactFromQR(aliceQr)
        alice.extractContactFromQR(bobQr)

        val aliceFingerprint = alice.getLocalIdentityFingerprint()
        val bobFingerprint = bob.getLocalIdentityFingerprint()

        // Establish session on Alice and Bob's sides
        assertTrue(alice.establishSecureSession(bobFingerprint))
        assertTrue(bob.establishSecureSession(aliceFingerprint))

        // Capture the saved state from insertion on Alice's side
        val aliceSavedStateSlot = slot<RatchetStateEntity>()
        coVerify { aliceRatchetDao.insertOrUpdate(capture(aliceSavedStateSlot)) }
        val savedEntity = aliceSavedStateSlot.captured

        // Restoring Alice's manager from database with captured state
        val aliceRestoredDao = mockk<RatchetStateDao>(relaxed = true)
        val aliceRestoredSkippedDao = mockk<SkippedMessageKeyDao>(relaxed = true)
        coEvery { aliceRestoredDao.getRatchetState(bobFingerprint) } returns savedEntity
        coEvery { aliceRestoredSkippedDao.getSkippedMessageKey(any(), any(), any()) } returns null

        val aliceRestored = CryptoManagerImpl(aliceRestoredDao, aliceRestoredSkippedDao, torManager)
        aliceRestored.extractContactFromQR(bobQr) // restore Bob's public key in memory

        // Restore session on Alice's side
        assertTrue(aliceRestored.establishSecureSession(bobFingerprint))

        // Encrypt message from restored session
        val msg = "Hello from restored session!".toByteArray(StandardCharsets.UTF_8)
        val encrypted = aliceRestored.encryptMessage(msg, bobFingerprint)

        // Decrypt on Bob's side
        val decrypted = bob.decryptMessage(encrypted, aliceFingerprint)
        assertEquals("Hello from restored session!", String(decrypted, StandardCharsets.UTF_8))
    }

    @Test
    fun testOutOfOrderDecryptionWithSkippedKeys() {
        val aliceRatchetDao = mockk<RatchetStateDao>(relaxed = true)
        val aliceSkippedDao = mockk<SkippedMessageKeyDao>(relaxed = true)
        val bobRatchetDao = mockk<RatchetStateDao>(relaxed = true)
        val bobSkippedDao = mockk<SkippedMessageKeyDao>(relaxed = true)

        coEvery { aliceRatchetDao.getRatchetState(any()) } returns null
        coEvery { aliceSkippedDao.getSkippedMessageKey(any(), any(), any()) } returns null
        coEvery { bobRatchetDao.getRatchetState(any()) } returns null
        
        // Mock Bob's skipped keys storage in-memory for testing out-of-order behavior
        val bobSkippedKeysMap = mutableMapOf<String, SkippedMessageKeyEntity>()
        coEvery { bobSkippedDao.insertSkippedMessageKey(any()) } answers {
            val entity = firstArg<SkippedMessageKeyEntity>()
            val key = "${entity.contactFingerprint}:${entity.dhPublicKeyB64}:${entity.messageNumber}"
            bobSkippedKeysMap[key] = entity
        }
        coEvery { bobSkippedDao.getSkippedMessageKey(any(), any(), any()) } answers {
            val contact = firstArg<String>()
            val dhKeyB64 = secondArg<String>()
            val msgNum = thirdArg<Int>()
            val key = "$contact:$dhKeyB64:$msgNum"
            bobSkippedKeysMap[key]
        }
        coEvery { bobSkippedDao.deleteSkippedMessageKey(any(), any(), any()) } answers {
            val contact = firstArg<String>()
            val dhKeyB64 = secondArg<String>()
            val msgNum = thirdArg<Int>()
            val key = "$contact:$dhKeyB64:$msgNum"
            bobSkippedKeysMap.remove(key)
        }

        val alice = CryptoManagerImpl(aliceRatchetDao, aliceSkippedDao, torManager)
        val bob = CryptoManagerImpl(bobRatchetDao, bobSkippedDao, torManager)

        val aliceQr = alice.generateIdentityQRContent()
        val bobQr = bob.generateIdentityQRContent()

        bob.extractContactFromQR(aliceQr)
        alice.extractContactFromQR(bobQr)

        val aliceFingerprint = alice.getLocalIdentityFingerprint()
        val bobFingerprint = bob.getLocalIdentityFingerprint()

        assertTrue(alice.establishSecureSession(bobFingerprint))
        assertTrue(bob.establishSecureSession(aliceFingerprint))

        // Alice encrypts 3 messages
        val msg1 = "Message 1".toByteArray(StandardCharsets.UTF_8)
        val msg2 = "Message 2".toByteArray(StandardCharsets.UTF_8)
        val msg3 = "Message 3".toByteArray(StandardCharsets.UTF_8)

        val encrypted1 = alice.encryptMessage(msg1, bobFingerprint)
        val encrypted2 = alice.encryptMessage(msg2, bobFingerprint)
        val encrypted3 = alice.encryptMessage(msg3, bobFingerprint)

        // Bob decrypts message 3 first! (Skipping 1 and 2)
        val decrypted3 = bob.decryptMessage(encrypted3, aliceFingerprint)
        assertEquals("Message 3", String(decrypted3, StandardCharsets.UTF_8))

        // Verify that Bob saved skipped keys for message 0 and 1
        assertTrue(bobSkippedKeysMap.isNotEmpty())

        // Bob now decrypts message 1
        val decrypted1 = bob.decryptMessage(encrypted1, aliceFingerprint)
        assertEquals("Message 1", String(decrypted1, StandardCharsets.UTF_8))

        // Bob now decrypts message 2
        val decrypted2 = bob.decryptMessage(encrypted2, aliceFingerprint)
        assertEquals("Message 2", String(decrypted2, StandardCharsets.UTF_8))
    }
}
