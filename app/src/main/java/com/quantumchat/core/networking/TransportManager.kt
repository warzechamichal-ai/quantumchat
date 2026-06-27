package com.quantumchat.core.networking

import com.quantumchat.core.data.ContactRepository
import com.quantumchat.core.database.PendingMessageDao
import com.quantumchat.core.database.PendingMessageEntity
import com.quantumchat.core.database.MessageDao
import com.quantumchat.core.database.toEntity
import com.quantumchat.core.crypto.CryptoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import dagger.Lazy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TransportManager acts as a centralized routing layer and virtual Transport implementation.
 * Manages registration of multiple transport backends (e.g., LocalNetworkTransport, WiFiDirectTransport, WebSocketTransport, TorTransport).
 * 
 * Target-based prioritization:
 * - If target matches a Tor Onion Service (.onion) -> TorTransport first, then others.
 * - If target matches a MAC address format -> WiFiDirectTransport first, then LocalNetworkTransport, then WebSocket.
 * - If target matches an IP address format or localhost -> LocalNetworkTransport first, then WiFiDirectTransport, then WebSocket.
 * - Otherwise (e.g., identity fingerprints) -> WebSocketTransport first, then LocalNetworkTransport, then WiFiDirectTransport.
 * 
 * For incoming data, it merges streams from all registered transport backends, enabling decentralized receiving.
 */
@Singleton
class TransportManager @Inject constructor(
    private val localNetworkTransport: LocalNetworkTransport,
    private val webSocketTransport: WebSocketTransport,
    val wiFiDirectTransport: WiFiDirectTransport,
    private val torTransport: TorTransport,
    private val localNetworkDiscovery: LocalNetworkDiscovery,
    private val mdnsDiscovery: MdnsDiscovery,
    private val contactRepository: Lazy<ContactRepository>,
    private val pendingMessageDao: Lazy<PendingMessageDao>,
    private val messageDao: Lazy<MessageDao>,
    private val cryptoManager: CryptoManager
) : Transport {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeContactFingerprint: String? = null

    // Registered list of transports
    private val transports = mutableListOf<Transport>()
    private var activeTransport: Transport? = null

    // Expose online peers flow
    val onlinePeers: Flow<Set<String>> = torTransport.onlinePeers

    // Configuration flags for discovery and WiFi Direct
    var isUdpDiscoveryEnabled = true
        set(value) {
            field = value
            updateDiscoveryState()
        }

    var isMdnsDiscoveryEnabled = true
        set(value) {
            field = value
            updateDiscoveryState()
        }

    var isWifiDirectEnabled = true

    private var isDiscoveryRunning = false

    init {
        // Register default transports
        registerTransport(localNetworkTransport)
        // Disable for Stage 1:
        // registerTransport(wiFiDirectTransport)
        // registerTransport(webSocketTransport)
        // registerTransport(torTransport)
        Timber.d("TransportManager initialized. Registered only LocalNetworkTransport for Stage 1. Registered: ${transports.size} transports")

        // Start background incoming message processing
        scope.launch {
            observeIncoming().collect { packet ->
                handleIncomingPacket(packet)
            }
        }

        // Clean expired pending messages on startup (TTL)
        scope.launch {
            try {
                pendingMessageDao.get().deleteExpiredMessages(System.currentTimeMillis())
                Timber.i("TransportManager: Cleared expired pending messages on startup")
            } catch (e: Exception) {
                Timber.e(e, "TransportManager: Failed to delete expired pending messages on startup")
            }
        }
    }

    /**
     * Starts active discovery services based on current configurations.
     */
    fun startDiscovery() {
        isDiscoveryRunning = true
        if (isUdpDiscoveryEnabled) {
            localNetworkDiscovery.start()
        } else {
            localNetworkDiscovery.stop()
        }
        if (isMdnsDiscoveryEnabled) {
            mdnsDiscovery.start()
        } else {
            mdnsDiscovery.stop()
        }
    }

    /**
     * Stops all active discovery services.
     */
    fun stopDiscovery() {
        isDiscoveryRunning = false
        localNetworkDiscovery.stop()
        mdnsDiscovery.stop()
    }

    private fun updateDiscoveryState() {
        if (isDiscoveryRunning) {
            startDiscovery()
        }
    }

    /**
     * Consolidates discovered device lists from UDP and mDNS sources into a single stream.
     */
    val discoveredDevices: Flow<List<DiscoveredDevice>> = combine(
        localNetworkDiscovery.devices,
        mdnsDiscovery.devices
    ) { udpList, mdnsList ->
        val list = mutableListOf<DiscoveredDevice>()
        if (isUdpDiscoveryEnabled) {
            list.addAll(udpList)
        }
        if (isMdnsDiscoveryEnabled) {
            list.addAll(mdnsList)
        }
        list.associateBy { it.fingerprint }.values.toList()
    }

    /**
     * Registers a new transport implementation.
     */
    fun registerTransport(transport: Transport) {
        synchronized(transports) {
            if (!transports.contains(transport)) {
                transports.add(transport)
                Timber.d("Transport registered: ${transport.javaClass.simpleName}")
            }
        }
    }

    /**
     * Registers multiple transports at once.
     */
    fun registerTransports(vararg newTransports: Transport) {
        newTransports.forEach { registerTransport(it) }
    }

    override val isConnected: Boolean
        get() = activeTransport?.isConnected ?: false

    override suspend fun connect(target: String): Boolean {
        Timber.i("TransportManager.connect() called with target: $target")
        disconnect()
        
        var resolvedTarget = target
        val isFingerprint = target.startsWith("QC-PQ-") || (!target.contains(".") && !target.contains(":") && target.length > 10)
        
        if (isFingerprint) {
            activeContactFingerprint = target
            try {
                val contacts = contactRepository.get().observeContacts().first()
                val matchedContact = contacts.find { it.publicKeyFingerprint == target }
                if (matchedContact != null && !matchedContact.onionAddress.isNullOrBlank()) {
                    resolvedTarget = matchedContact.onionAddress
                    Timber.d("TransportManager: Resolved fingerprint '$target' to onion address '$resolvedTarget'")
                }
            } catch (e: Exception) {
                Timber.w("TransportManager: Failed to resolve target fingerprint to onion address: ${e.message}")
            }
        } else {
            try {
                val contacts = contactRepository.get().observeContacts().first()
                val matchedContact = contacts.find { 
                    it.onionAddress == target || 
                    (it.onionAddress != null && it.onionAddress.startsWith(target.split(":")[0]))
                }
                if (matchedContact != null) {
                    activeContactFingerprint = matchedContact.publicKeyFingerprint
                }
            } catch (e: Exception) {}
        }
        
        // Select prioritized order of transports based on target format
        val prioritizedList = getPrioritizedTransports(resolvedTarget)
        
        Timber.i("TransportManager: Attempting connection to target '$resolvedTarget' (original: '$target'). First transport selected: ${prioritizedList.firstOrNull()?.javaClass?.simpleName}. Order: ${prioritizedList.map { it.javaClass.simpleName }}")
        
        for (transport in prioritizedList) {
            try {
                val connTarget = if (transport is TorTransport) resolvedTarget else target
                Timber.d("TransportManager: Trying connection via ${transport.javaClass.simpleName} to '$connTarget'...")
                val success = transport.connect(connTarget)
                if (success) {
                    activeTransport = transport
                    Timber.i("TransportManager: Successfully connected using ${transport.javaClass.simpleName} to '$connTarget'")
                    
                    val fingerprint = activeContactFingerprint
                    if (fingerprint != null) {
                        scope.launch {
                            sendPendingMessagesForContact(fingerprint)
                        }
                    }
                    return true
                }
            } catch (e: Exception) {
                Timber.w("TransportManager: ${transport.javaClass.simpleName} failed to connect: ${e.message}")
            }
        }
        Timber.e("TransportManager: All registered transports failed to connect to target: $target")
        return false
    }

    override suspend fun send(data: ByteArray): Boolean {
        val transport = activeTransport
        val fingerprint = activeContactFingerprint
        
        if (fingerprint == null) {
            Timber.w("TransportManager: send failed, no active contact fingerprint.")
            return false
        }

        // Build the message packet (Type 0x01)
        val packet = buildPacket(0x01, cryptoManager.getLocalIdentityFingerprint(), data)

        if (transport == null || !transport.isConnected) {
            Timber.w("TransportManager: send failed, no active connected transport. Queueing message offline.")
            savePendingMessage(fingerprint, packet)
            return true
        }
        
        val success = transport.send(packet)
        if (!success) {
            Timber.w("TransportManager: send failed on active transport. Queueing message offline.")
            savePendingMessage(fingerprint, packet)
            return true
        }
        return success
    }

    private suspend fun savePendingMessage(fingerprint: String, data: ByteArray) {
        try {
            // TTL 14 days in milliseconds
            val ttlMs = 14L * 24L * 60L * 60L * 1000L
            val expiresAt = System.currentTimeMillis() + ttlMs
            val entity = PendingMessageEntity(
                contactFingerprint = fingerprint,
                encryptedPayload = data,
                createdAt = System.currentTimeMillis(),
                retryCount = 0,
                expiresAt = expiresAt
            )
            pendingMessageDao.get().insert(entity)
            Timber.i("TransportManager: Saved pending message offline for $fingerprint (expires at $expiresAt)")
        } catch (e: Exception) {
            Timber.e(e, "TransportManager: Failed to save pending message offline")
        }
    }

    private suspend fun sendPendingMessagesForContact(fingerprint: String) {
        val dao = pendingMessageDao.get()
        try {
            val pendingList = dao.getPendingMessagesForContact(fingerprint).first()
            if (pendingList.isEmpty()) return

            Timber.d("TransportManager: Found ${pendingList.size} pending messages for contact $fingerprint. Sending...")
            for (msg in pendingList) {
                if (msg.retryCount >= 5) {
                    Timber.w("TransportManager: Pending message ${msg.id} exceeded max retries. Deleting.")
                    dao.deleteById(msg.id)
                    continue
                }

                val transport = activeTransport
                if (transport != null && transport.isConnected) {
                    val success = transport.send(msg.encryptedPayload)
                    if (success) {
                        Timber.i("TransportManager: Successfully sent pending message ${msg.id}")
                        dao.deleteById(msg.id)
                    } else {
                        val newRetryCount = msg.retryCount + 1
                        dao.updateRetryInfo(msg.id, newRetryCount, System.currentTimeMillis())
                        Timber.w("TransportManager: Failed to send pending message ${msg.id}, retry count: $newRetryCount")
                    }
                } else {
                    Timber.w("TransportManager: Active transport disconnected while sending pending messages.")
                    break
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "TransportManager: Error sending pending messages for $fingerprint")
        }
    }

    override fun observeIncoming(): Flow<ByteArray> {
        // Merging incoming flows from all registered transports
        // Allows receiving messages dynamically from any interface (e.g. peer connects to our TCP ServerSocket)
        val list = synchronized(transports) { ArrayList(transports) }
        Timber.d("TransportManager: observing incoming merged streams from ${list.size} transports.")
        return merge(*list.map { it.observeIncoming() }.toTypedArray())
    }

    override suspend fun disconnect() {
        Timber.d("TransportManager: disconnecting all transports.")
        val list = synchronized(transports) { ArrayList(transports) }
        list.forEach {
            try {
                it.disconnect()
            } catch (e: Exception) {
                Timber.e(e, "Error disconnecting transport ${it.javaClass.simpleName}")
            }
        }
        activeTransport = null
        activeContactFingerprint = null
    }

    /**
     * Determines transport priority based on target characteristics.
     * Onion addresses prioritize TorTransport.
     * MAC addresses prioritize WiFiDirectTransport.
     * IP addresses or localhosts prioritize LocalNetworkTransport.
     * Identity fingerprints prioritize WebSocketTransport.
     */
    private fun getPrioritizedTransports(target: String): List<Transport> {
        Timber.i("TransportManager: Target prioritization overridden for Stage 1. target=$target, using only LocalNetworkTransport")
        return listOf(localNetworkTransport)
    }

    private fun buildPacket(type: Byte, senderFingerprint: String, payload: ByteArray): ByteArray {
        val fingerprintBytes = senderFingerprint.toByteArray(Charsets.UTF_8)
        val fingerprintLen = fingerprintBytes.size
        val totalSize = 1 + 1 + fingerprintLen + payload.size
        val buffer = java.nio.ByteBuffer.allocate(totalSize)
        buffer.put(type)
        buffer.put(fingerprintLen.toByte())
        buffer.put(fingerprintBytes)
        buffer.put(payload)
        return buffer.array()
    }

    private suspend fun handleIncomingPacket(packet: ByteArray) {
        try {
            if (packet.size < 2) {
                Timber.w("TransportManager: Incoming packet too small: ${packet.size}")
                return
            }
            val type = packet[0].toInt()
            val fingerprintLen = packet[1].toInt() and 0xFF
            if (packet.size < 2 + fingerprintLen) {
                Timber.w("TransportManager: Packet size ${packet.size} is less than required header size: ${2 + fingerprintLen}")
                return
            }
            val senderFingerprint = String(packet, 2, fingerprintLen, Charsets.UTF_8)
            val payload = packet.copyOfRange(2 + fingerprintLen, packet.size)

            when (type) {
                0x01 -> {
                    Timber.i("TransportManager: Received message packet from $senderFingerprint")
                    handleIncomingMessage(senderFingerprint, payload)
                }
                0x02 -> {
                    val messageId = String(payload, Charsets.UTF_8)
                    Timber.i("TransportManager: Received Ack for message $messageId from $senderFingerprint")
                    messageDao.get().updateMessageStatus(messageId, com.quantumchat.core.common.model.MessageStatus.DELIVERED.name)
                }
                else -> {
                    Timber.w("TransportManager: Unknown packet type: $type")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "TransportManager: Failed to process incoming packet: ${e.message}")
        }
    }

    private suspend fun handleIncomingMessage(senderFingerprint: String, encryptedPayload: ByteArray) {
        try {
            val decryptedBytes = cryptoManager.decryptMessage(encryptedPayload, senderFingerprint)
            if (decryptedBytes.isEmpty()) {
                Timber.w("TransportManager: Decrypted payload is empty (decryption failed). Dropping packet.")
                return
            }
            val jsonStr = decryptedBytes.toString(Charsets.UTF_8)
            val receivedMessage = kotlinx.serialization.json.Json.decodeFromString<com.quantumchat.core.common.model.Message>(jsonStr)
            val finalMessage = receivedMessage.copy(
                senderId = senderFingerprint,
                recipientId = "me",
                status = com.quantumchat.core.common.model.MessageStatus.DELIVERED
            )
            messageDao.get().insertMessage(finalMessage.toEntity())
            Timber.i("TransportManager: Saved message ${finalMessage.id} to database from $senderFingerprint")

            // Send Ack back
            val ackPayload = finalMessage.id.toByteArray(Charsets.UTF_8)
            val ackPacket = buildPacket(0x02, cryptoManager.getLocalIdentityFingerprint(), ackPayload)
            val sentAck = sendPacketToFingerprint(senderFingerprint, ackPacket)
            if (sentAck) {
                Timber.i("TransportManager: Successfully sent Ack for message ${finalMessage.id} to $senderFingerprint")
            } else {
                Timber.w("TransportManager: Failed to send Ack for message ${finalMessage.id} to $senderFingerprint")
            }
        } catch (e: Exception) {
            Timber.e(e, "TransportManager: Failed to handle incoming message: ${e.message}")
        }
    }

    private suspend fun sendPacketToFingerprint(fingerprint: String, packet: ByteArray): Boolean {
        if (activeContactFingerprint == fingerprint) {
            val transport = activeTransport
            if (transport != null && transport.isConnected) {
                Timber.d("TransportManager: sendPacketToFingerprint sending via active transport to $fingerprint")
                return transport.send(packet)
            }
        }
        Timber.w("TransportManager: sendPacketToFingerprint failed, no active transport or mismatch for $fingerprint")
        return false
    }
}
