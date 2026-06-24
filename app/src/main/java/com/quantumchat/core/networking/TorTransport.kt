package com.quantumchat.core.networking

import com.quantumchat.core.crypto.CryptoManager
import com.quantumchat.core.data.ContactRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.Proxy as JavaProxy
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TorTransport implements the Transport interface, routing all connections through Tor's local SOCKS proxy
 * to target Tor Onion Services v3 (.onion addresses).
 * 
 * Flow:
 * - A connection target is specified as an onion address (e.g. abc123xyz.onion:9090).
 * - SOCKS Proxy Socket is instantiated, resolving the onion address via Tor.
 * - Local identity fingerprint is sent and remote fingerprint is verified via a handshake.
 */
@Singleton
class TorTransport @Inject constructor(
    private val torManager: TorManager,
    private val cryptoManager: CryptoManager,
    private val contactRepository: ContactRepository
) : Transport {

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _onlinePeers = MutableStateFlow<Set<String>>(emptySet())
    val onlinePeers: StateFlow<Set<String>> = _onlinePeers.asStateFlow()

    private fun updateOnlinePeers() {
        _onlinePeers.value = activePeers.keys.toSet()
    }

    private var activeSocket: Socket? = null
    
    // Server socket and active peer connections for Onion Service incoming/outgoing connections
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    
    private class PeerConnection(
        val socket: Socket,
        val sendQueue: Channel<ByteArray>,
        var senderJob: Job?,
        var heartbeatJob: Job?,
        var readJob: Job?
    )
    
    private val activePeers = ConcurrentHashMap<String, PeerConnection>()

    var activeContactFingerprint: String? = null

    override val isConnected: Boolean
        get() {
            val fingerprint = activeContactFingerprint ?: return false
            val peerConn = activePeers[fingerprint]
            return peerConn != null && peerConn.socket.isConnected && !peerConn.socket.isClosed
        }

    private fun compress(data: ByteArray): ByteArray {
        val deflater = java.util.zip.Deflater()
        deflater.setInput(data)
        deflater.finish()
        val bos = java.io.ByteArrayOutputStream(data.size)
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            bos.write(buffer, 0, count)
        }
        deflater.end()
        return bos.toByteArray()
    }

    private fun decompress(data: ByteArray): ByteArray {
        val inflater = java.util.zip.Inflater()
        inflater.setInput(data)
        val bos = java.io.ByteArrayOutputStream(data.size)
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            bos.write(buffer, 0, count)
        }
        inflater.end()
        return bos.toByteArray()
    }

    private fun startSenderJob(peerConn: PeerConnection) {
        peerConn.senderJob?.cancel()
        peerConn.senderJob = scope.launch(Dispatchers.IO) {
            try {
                val outputStream = peerConn.socket.getOutputStream()
                while (isActive && !peerConn.socket.isClosed) {
                    val first = peerConn.sendQueue.receive()
                    val batch = mutableListOf(first)

                    delay(100)
                    while (true) {
                        val next = peerConn.sendQueue.tryReceive().getOrNull() ?: break
                        batch.add(next)
                    }

                    val bos = java.io.ByteArrayOutputStream()
                    batch.forEach { msg ->
                        val subLen = ByteBuffer.allocate(4).putInt(msg.size).array()
                        bos.write(subLen)
                        bos.write(msg)
                    }
                    val rawBatchBytes = bos.toByteArray()
                    val compressedBatch = compress(rawBatchBytes)

                    val lengthHeader = ByteBuffer.allocate(4).putInt(compressedBatch.size).array()
                    outputStream.write(lengthHeader)
                    outputStream.write(compressedBatch)
                    outputStream.flush()
                    Timber.d("TorTransport: Sent batch of ${batch.size} sub-messages (${compressedBatch.size} bytes compressed).")
                }
            } catch (e: Exception) {
                Timber.d("TorTransport: Outgoing sender job completed or closed: ${e.message}")
            }
        }
    }

    private fun startHeartbeat(peerConn: PeerConnection) {
        peerConn.heartbeatJob?.cancel()
        peerConn.heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(60000)
                if (peerConn.socket.isConnected && !peerConn.socket.isClosed) {
                    peerConn.sendQueue.trySend(ByteArray(0))
                    Timber.d("TorTransport: Dispatched heartbeat ping to the queue.")
                }
            }
        }
    }

    init {
        // Automatically start/stop Onion ServerSocket listener based on Tor connectivity status
        scope.launch {
            torManager.status.collect { status ->
                if (status == TorStatus.CONNECTED) {
                    startServer()
                    if (torManager.isAutoStartEnabled) {
                        preconnectMostUsedContacts()
                    }
                } else {
                    stopServer()
                }
            }
        }
    }

    private fun startServer() {
        stopServer() // Ensure clean state
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                // Listen on local port 9095 where Orbot redirects Onion Service traffic
                val server = ServerSocket(9095)
                serverSocket = server
                Timber.i("TorTransport: Onion ServerSocket started on port 9095")
                
                while (isActive && !server.isClosed) {
                    val socket = server.accept()
                    Timber.d("TorTransport: Accepted incoming connection from ${socket.remoteSocketAddress}")
                    launch {
                        handleIncomingConnection(socket)
                    }
                }
            } catch (e: Exception) {
                Timber.d("TorTransport: ServerSocket closed or failed: ${e.message}")
            }
        }
    }

    private fun stopServer() {
        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
        
        // Close all peer connections
        val peers = ArrayList(activePeers.values)
        activePeers.clear()
        updateOnlinePeers()
        peers.forEach { peerConn ->
            peerConn.senderJob?.cancel()
            peerConn.heartbeatJob?.cancel()
            peerConn.readJob?.cancel()
            try { peerConn.socket.close() } catch (e: Exception) {}
        }
    }

    private suspend fun handleIncomingConnection(socket: Socket) {
        try {
            // 1. Perform handshake to get remote fingerprint
            val remoteFingerprint = performHandshake(socket)
            if (remoteFingerprint == null) {
                Timber.w("TorTransport: Incoming handshake failed from ${socket.remoteSocketAddress}")
                try { socket.close() } catch (e: Exception) {}
                return
            }

            Timber.i("TorTransport: Incoming handshake succeeded. Peer: $remoteFingerprint")
            
            // Close old connection with this peer if it existed
            activePeers[remoteFingerprint]?.let { oldConn ->
                oldConn.senderJob?.cancel()
                oldConn.heartbeatJob?.cancel()
                oldConn.readJob?.cancel()
                try { oldConn.socket.close() } catch (e: Exception) {}
            }

            val sendQueue = Channel<ByteArray>(Channel.UNLIMITED)
            val peerConn = PeerConnection(
                socket = socket,
                sendQueue = sendQueue,
                senderJob = null,
                heartbeatJob = null,
                readJob = null
            )

            startSenderJob(peerConn)
            startHeartbeat(peerConn)

            peerConn.readJob = scope.launch {
                handleIncomingStream(peerConn, remoteFingerprint)
            }

            activePeers[remoteFingerprint] = peerConn
            updateOnlinePeers()
        } catch (e: Exception) {
            Timber.e(e, "TorTransport: Error handling incoming connection")
            try { socket.close() } catch (ex: Exception) {}
        }
    }

    private suspend fun performHandshake(socket: Socket): String? = withContext(Dispatchers.IO) {
        try {
            val outputStream = socket.getOutputStream()
            val inputStream = socket.getInputStream()

            val ownFingerprint = cryptoManager.getLocalIdentityFingerprint()
            val ownOnion = torManager.onionAddress.value ?: "NO-ONION"
            val payloadStr = "$ownFingerprint|$ownOnion"
            val ownBytes = payloadStr.toByteArray(Charsets.UTF_8)
            outputStream.write(ownBytes.size)
            outputStream.write(ownBytes)
            outputStream.flush()

            val remoteLen = inputStream.read()
            if (remoteLen == -1) return@withContext null
            val remoteBytes = ByteArray(remoteLen)
            var read = 0
            while (read < remoteLen) {
                val r = inputStream.read(remoteBytes, read, remoteLen - read)
                if (r == -1) return@withContext null
                read += r
            }
            val remotePayload = String(remoteBytes, Charsets.UTF_8)
            val parts = remotePayload.split("|")
            val remoteFingerprint = parts[0]
            val remoteOnion = parts.getOrNull(1)

            if (!remoteOnion.isNullOrEmpty() && remoteOnion != "NO-ONION" && remoteOnion.endsWith(".onion")) {
                scope.launch {
                    val contacts = contactRepository.observeContacts().first()
                    val contact = contacts.find { it.publicKeyFingerprint == remoteFingerprint }
                    if (contact != null && contact.onionAddress != remoteOnion) {
                        contactRepository.addContact(contact.copy(onionAddress = remoteOnion))
                        Timber.i("TorTransport: Automatically exchanged and saved Onion address for $remoteFingerprint: $remoteOnion")
                    }
                }
            }

            Timber.i("TorTransport: Handshake completed with remote peer: $remoteFingerprint")
            remoteFingerprint
        } catch (e: Exception) {
            Timber.e(e, "TorTransport: Handshake exchange failed")
            null
        }
    }

    private suspend fun handleIncomingStream(peerConn: PeerConnection, remoteFingerprint: String) {
        val s = peerConn.socket
        try {
            val inputStream = s.getInputStream()
            while (scope.isActive && !s.isClosed) {
                val lengthBuffer = ByteArray(4)
                var bytesRead = 0
                while (bytesRead < 4) {
                    val read = withContext(Dispatchers.IO) {
                        inputStream.read(lengthBuffer, bytesRead, 4 - bytesRead)
                    }
                    if (read == -1) return
                    bytesRead += read
                }
                val length = ByteBuffer.wrap(lengthBuffer).int
                if (length <= 0 || length > 10 * 1024 * 1024) return

                val compressedPayload = ByteArray(length)
                var payloadBytesRead = 0
                while (payloadBytesRead < length) {
                    val read = withContext(Dispatchers.IO) {
                        inputStream.read(compressedPayload, payloadBytesRead, length - payloadBytesRead)
                    }
                    if (read == -1) return
                    payloadBytesRead += read
                }

                val rawBatchBytes = decompress(compressedPayload)
                val buffer = ByteBuffer.wrap(rawBatchBytes)
                while (buffer.hasRemaining()) {
                    if (buffer.remaining() < 4) break
                    val subLen = buffer.int
                    if (subLen < 0 || subLen > buffer.remaining()) break
                    if (subLen == 0) {
                        continue
                    }
                    val subPayload = ByteArray(subLen)
                    buffer.get(subPayload)
                    _incoming.emit(subPayload)
                }
            }
        } catch (e: Exception) {
            Timber.d("TorTransport: Socket read closed: ${e.message}")
        } finally {
            activePeers.remove(remoteFingerprint)
            updateOnlinePeers()
            peerConn.senderJob?.cancel()
            peerConn.heartbeatJob?.cancel()
            try { s.close() } catch (e: Exception) {}
        }
    }

    private suspend fun establishPhysicalConnection(onionAddress: String): String? = withContext(Dispatchers.IO) {
        val parts = onionAddress.trim().split(":")
        val onionHost = parts[0]
        val onionPort = parts.getOrNull(1)?.toIntOrNull() ?: 9095

        if (torManager.status.value != TorStatus.CONNECTED) {
            return@withContext null
        }

        var attempt = 0
        var delayMs = 5000L
        val maxAttempts = 3
        var connectedSocket: Socket? = null
        var remoteFingerprint: String? = null

        while (attempt < maxAttempts) {
            try {
                val proxyHost = "127.0.0.1"
                val proxyPort = torManager.socksPort.value
                val socksProxy = JavaProxy(JavaProxy.Type.SOCKS, InetSocketAddress(proxyHost, proxyPort))

                val socket = Socket(socksProxy)
                socket.connect(InetSocketAddress(onionHost, onionPort), 45000)

                remoteFingerprint = performHandshake(socket)
                if (remoteFingerprint == null) {
                    try { socket.close() } catch (e: Exception) {}
                    throw Exception("Handshake failed")
                }

                connectedSocket = socket
                break
            } catch (e: Exception) {
                attempt++
                if (attempt >= maxAttempts) {
                    Timber.e(e, "TorTransport: Physical connection attempt to $onionHost failed.")
                    return@withContext null
                }
                Timber.w("TorTransport: Connection attempt $attempt/$maxAttempts failed: ${e.message}. Retrying in ${delayMs / 1000}s...")
                delay(delayMs)
                delayMs *= 2
            }
        }

        val socket = connectedSocket ?: return@withContext null
        val fingerprint = remoteFingerprint ?: return@withContext null

        // Close old connection with this peer if it existed
        activePeers[fingerprint]?.let { oldConn ->
            oldConn.senderJob?.cancel()
            oldConn.heartbeatJob?.cancel()
            oldConn.readJob?.cancel()
            try { oldConn.socket.close() } catch (e: Exception) {}
        }

        val sendQueue = Channel<ByteArray>(Channel.UNLIMITED)
        val peerConn = PeerConnection(
            socket = socket,
            sendQueue = sendQueue,
            senderJob = null,
            heartbeatJob = null,
            readJob = null
        )

        startSenderJob(peerConn)
        startHeartbeat(peerConn)

        peerConn.readJob = scope.launch {
            handleIncomingStream(peerConn, fingerprint)
        }

        activePeers[fingerprint] = peerConn
        updateOnlinePeers()
        fingerprint
    }

    override suspend fun connect(target: String): Boolean {
        val cleanTarget = target.trim()
        
        var contactFingerprint: String? = null
        if (cleanTarget.startsWith("QC-PQ-")) {
            contactFingerprint = cleanTarget
        } else {
            val contacts = contactRepository.observeContacts().first()
            val contact = contacts.find { it.onionAddress == cleanTarget || (it.onionAddress != null && it.onionAddress!!.startsWith(cleanTarget.split(":")[0])) }
            contactFingerprint = contact?.publicKeyFingerprint
        }

        if (contactFingerprint != null) {
            activeContactFingerprint = contactFingerprint
            val existingConn = activePeers[contactFingerprint]
            if (existingConn != null && existingConn.socket.isConnected && !existingConn.socket.isClosed) {
                Timber.i("TorTransport: Reusing active connection to $contactFingerprint")
                activeSocket = existingConn.socket
                return true
            }
        }

        activeSocket = null

        if (torManager.status.value != TorStatus.CONNECTED) {
            Timber.w("TorTransport: Connection aborted. Tor status is not CONNECTED.")
            return false
        }

        if (cleanTarget.startsWith("QC-PQ-") && (contactFingerprint == null || activePeers[contactFingerprint] == null)) {
            val contacts = contactRepository.observeContacts().first()
            val contact = contacts.find { it.publicKeyFingerprint == cleanTarget }
            if (contact?.onionAddress.isNullOrBlank()) {
                Timber.w("TorTransport: Cannot dial fingerprint target '$cleanTarget' without a registered onion address.")
                return false
            }
        }

        val resolvedOnion = if (cleanTarget.startsWith("QC-PQ-")) {
            val contacts = contactRepository.observeContacts().first()
            contacts.find { it.publicKeyFingerprint == cleanTarget }?.onionAddress ?: return false
        } else {
            cleanTarget
        }

        val fingerprint = establishPhysicalConnection(resolvedOnion)
        if (fingerprint != null) {
            activeContactFingerprint = fingerprint
            activeSocket = activePeers[fingerprint]?.socket
            return true
        }

        return false
    }

    private fun preconnectMostUsedContacts() {
        scope.launch {
            try {
                delay(5000)
                val contacts = contactRepository.observeContacts().first()
                val candidates = contacts.filter { !it.onionAddress.isNullOrBlank() }.take(3)
                Timber.d("TorTransport: Pre-connecting to ${candidates.size} contacts in background...")
                for (contact in candidates) {
                    val fingerprint = contact.publicKeyFingerprint
                    val existing = activePeers[fingerprint]
                    if (existing == null || !existing.socket.isConnected || existing.socket.isClosed) {
                        launch {
                            try {
                                Timber.d("TorTransport: Background warming connection to ${contact.name} (${contact.onionAddress})")
                                establishPhysicalConnection(contact.onionAddress!!)
                            } catch (e: Exception) {
                                Timber.w("TorTransport: Pre-connect failed to ${contact.name}: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "TorTransport: Error in preconnectMostUsedContacts")
            }
        }
    }

    override suspend fun send(data: ByteArray): Boolean {
        val fingerprint = activeContactFingerprint ?: return false
        val peerConn = activePeers[fingerprint]
        if (peerConn == null || !peerConn.socket.isConnected || peerConn.socket.isClosed) {
            Timber.w("TorTransport: send failed, no active connected socket for $fingerprint.")
            return false
        }
        val success = peerConn.sendQueue.trySend(data).isSuccess
        if (!success) {
            Timber.w("TorTransport: Failed to queue message for sending to $fingerprint.")
        }
        return success
    }

    override fun observeIncoming(): Flow<ByteArray> = _incoming

    fun hasActiveConnection(fingerprint: String): Boolean {
        val peerConn = activePeers[fingerprint]
        return peerConn != null && peerConn.socket.isConnected && !peerConn.socket.isClosed
    }

    fun sendToPeer(fingerprint: String, data: ByteArray): Boolean {
        val peerConn = activePeers[fingerprint] ?: return false
        if (!peerConn.socket.isConnected || peerConn.socket.isClosed) return false
        val success = peerConn.sendQueue.trySend(data).isSuccess
        if (!success) {
            Timber.w("TorTransport: Failed to queue message to peer $fingerprint.")
        }
        return success
    }

    override suspend fun disconnect() {
        activeContactFingerprint = null
        activeSocket = null
    }
}
