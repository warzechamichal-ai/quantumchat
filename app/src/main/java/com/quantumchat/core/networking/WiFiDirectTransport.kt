package com.quantumchat.core.networking

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import timber.log.Timber
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import com.quantumchat.core.crypto.CryptoManager

/**
 * WiFiDirectTransport implements the Transport interface utilizing Android's WiFi Direct (P2P) APIs.
 * It establishes peer-to-peer networks and routes encrypted packets across peers using a basic
 * multi-hop mesh structure with dynamic routing tables.
 */
@SuppressLint("MissingPermission")
@Singleton
class WiFiDirectTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager
) : Transport {

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = manager?.initialize(context, Looper.getMainLooper(), null)

    private val _isConnected = AtomicBoolean(false)
    override val isConnected: Boolean
        get() = _isConnected.get()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Active sockets and routing information for mesh topology
    private val activePeers = ConcurrentHashMap<String, Socket>()
    private val routingTable = ConcurrentHashMap<String, String>() // maps destinationFingerprint -> nextHopFingerprint

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    private val wifiDirectPort = 8999

    // Connection tracking variables for auto-reconnect
    private val isManuallyDisconnected = AtomicBoolean(false)
    private var lastTarget: String? = null
    private var reconnectJob: Job? = null

    // Track the active contact we are chatting with to set destination in MeshPacket
    var activeContactFingerprint: String? = null

    // Track the role: Group Owner (true) or Client (false)
    var isGroupOwner: Boolean = false
        private set

    /**
     * Checks if the application holds all necessary permissions to operate WiFi Direct.
     */
    fun hasPermissions(): Boolean {
        val fineLocation = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        
        val coarseLocation = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val nearbyWifi = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.NEARBY_WIFI_DEVICES
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return (fineLocation || coarseLocation) && nearbyWifi
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(target: String): Boolean {
        cleanupSocketAndGroup()
        isManuallyDisconnected.set(false)
        lastTarget = target

        // If target resembles a fingerprint, track it as our primary destination for routing
        if (target.startsWith("QC-PQ-")) {
            activeContactFingerprint = target
        }

        if (!hasPermissions()) {
            Timber.w("WiFiDirectTransport: Connection aborted. Missing required location or nearby wifi permissions.")
            return false
        }

        val p2pManager = manager ?: return false
        val p2pChannel = channel ?: return false

        Timber.d("WiFiDirectTransport: Attempting P2P connection to target: $target")

        // 1. Discover peers first to find matching target address or name
        val discoveredPeers = discoverPeersSync()
        val device = discoveredPeers.find { it.deviceAddress == target || it.deviceName == target }
        if (device == null) {
            Timber.w("WiFiDirectTransport: Target peer '$target' not found in WiFi Direct peers list.")
            return false
        }

        // 2. Connect to peer
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        val connectionSuccess = suspendCancellableCoroutine<Boolean> { continuation ->
            p2pManager.connect(p2pChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.d("WiFiDirectTransport: Connection negotiation initiated successfully.")
                    continuation.resume(true)
                }

                override fun onFailure(reason: Int) {
                    Timber.e("WiFiDirectTransport: Connection initiation failed, reason=$reason")
                    continuation.resume(false)
                }
            })
        }

        if (!connectionSuccess) return false

        // 3. Wait for connection info to resolve IP and establish socket
        val info = waitForConnectionInfo() ?: return false
        return establishP2pSocket(info)
    }

    private suspend fun discoverPeersSync(): List<WifiP2pDevice> = suspendCancellableCoroutine { continuation ->
        val p2pManager = manager ?: return@suspendCancellableCoroutine continuation.resume(emptyList())
        val p2pChannel = channel ?: return@suspendCancellableCoroutine continuation.resume(emptyList())

        p2pManager.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
            @SuppressLint("MissingPermission")
            override fun onSuccess() {
                Timber.d("WiFiDirectTransport: Peer discovery initiated.")
                p2pManager.requestPeers(p2pChannel) { peersList ->
                    continuation.resume(peersList.deviceList.toList())
                }
            }

            override fun onFailure(reason: Int) {
                Timber.w("WiFiDirectTransport: Peer discovery failed, reason=$reason")
                continuation.resume(emptyList())
            }
        })
    }

    private suspend fun waitForConnectionInfo(): WifiP2pInfo? = withTimeoutOrNull(20000) {
        val p2pManager = manager ?: return@withTimeoutOrNull null
        val p2pChannel = channel ?: return@withTimeoutOrNull null

        while (isActive) {
            val info = suspendCancellableCoroutine<WifiP2pInfo?> { continuation ->
                p2pManager.requestConnectionInfo(p2pChannel) { connectionInfo ->
                    if (connectionInfo.groupFormed) {
                        continuation.resume(connectionInfo)
                    } else {
                        continuation.resume(null)
                    }
                }
            }
            if (info != null) return@withTimeoutOrNull info
            delay(1000)
        }
        null
    }

    private suspend fun establishP2pSocket(info: WifiP2pInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            isGroupOwner = info.isGroupOwner
            if (info.isGroupOwner) {
                Timber.i("WiFiDirectTransport: Device is Group Owner. Starting server accept loop...")
                val server = ServerSocket(wifiDirectPort)
                serverSocket = server
                serverJob = scope.launch {
                    try {
                        while (isActive && !server.isClosed) {
                            val clientSocket = server.accept()
                            scope.launch {
                                handleAcceptedSocket(clientSocket)
                            }
                        }
                    } catch (e: Exception) {
                        if (!server.isClosed) {
                            Timber.e(e, "WiFiDirectTransport ServerSocket accept error")
                        }
                    }
                }
                true
            } else {
                Timber.i("WiFiDirectTransport: Device is Client. Connecting to Group Owner at ${info.groupOwnerAddress.hostAddress}")
                val socket = Socket()
                socket.connect(InetSocketAddress(info.groupOwnerAddress, wifiDirectPort), 10000)
                
                scope.launch {
                    handleAcceptedSocket(socket)
                }
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "WiFiDirectTransport: Failed to establish P2P Socket")
            _isConnected.set(false)
            false
        }
    }

    private suspend fun handleAcceptedSocket(socket: Socket) {
        val remoteFingerprint = performHandshake(socket)
        if (remoteFingerprint == null) {
            try { socket.close() } catch (e: Exception) {}
            return
        }

        activePeers[remoteFingerprint] = socket
        routingTable[remoteFingerprint] = remoteFingerprint // Direct route to neighbor
        _isConnected.set(true)

        try {
            handleIncomingStream(socket)
        } finally {
            activePeers.remove(remoteFingerprint)
            routingTable.remove(remoteFingerprint)
            try { socket.close() } catch (e: Exception) {}
            if (activePeers.isEmpty()) {
                _isConnected.set(false)
                handleConnectionLoss()
            }
        }
    }

    private suspend fun performHandshake(socket: Socket): String? = withContext(Dispatchers.IO) {
        try {
            val outputStream = socket.getOutputStream()
            val inputStream = socket.getInputStream()

            // 1. Send our local fingerprint
            val ownFingerprint = cryptoManager.getLocalIdentityFingerprint()
            val ownBytes = ownFingerprint.toByteArray(Charsets.UTF_8)
            outputStream.write(ownBytes.size)
            outputStream.write(ownBytes)
            outputStream.flush()

            // 2. Read remote peer's fingerprint
            val remoteLen = inputStream.read()
            if (remoteLen == -1) return@withContext null
            val remoteBytes = ByteArray(remoteLen)
            var read = 0
            while (read < remoteLen) {
                val r = inputStream.read(remoteBytes, read, remoteLen - read)
                if (r == -1) return@withContext null
                read += r
            }
            val remoteFingerprint = String(remoteBytes, Charsets.UTF_8)
            Timber.i("WiFiDirectTransport: Handshake completed with remote peer: $remoteFingerprint")
            remoteFingerprint
        } catch (e: Exception) {
            Timber.e(e, "WiFiDirectTransport: Handshake exchange failed")
            null
        }
    }

    private suspend fun handleIncomingStream(socket: Socket) {
        try {
            val inputStream = socket.getInputStream()
            while (scope.isActive && !socket.isClosed) {
                // Read 4-byte message length
                val lengthBuffer = ByteArray(4)
                var bytesRead = 0
                while (bytesRead < 4) {
                    val read = withContext(Dispatchers.IO) {
                        inputStream.read(lengthBuffer, bytesRead, 4 - bytesRead)
                    }
                    if (read == -1) {
                        Timber.d("WiFiDirectTransport: Socket EOF reached while reading length.")
                        return
                    }
                    bytesRead += read
                }
                val length = ByteBuffer.wrap(lengthBuffer).int
                if (length <= 0 || length > 10 * 1024 * 1024) {
                    Timber.w("WiFiDirectTransport: Received invalid message length $length.")
                    return
                }

                // Read payload
                val payload = ByteArray(length)
                var payloadBytesRead = 0
                while (payloadBytesRead < length) {
                    val read = withContext(Dispatchers.IO) {
                        inputStream.read(payload, payloadBytesRead, length - payloadBytesRead)
                    }
                    if (read == -1) {
                        Timber.d("WiFiDirectTransport: Socket EOF reached while reading payload.")
                        return
                    }
                    payloadBytesRead += read
                }

                // Deserialize and process/forward MeshPacket
                try {
                    val packet = MeshPacket.deserialize(payload)
                    
                    // Learn routing path: S is reachable via the direct neighbor socket we got it from
                    val neighborFingerprint = activePeers.entries.find { it.value == socket }?.key
                    if (neighborFingerprint != null) {
                        routingTable[packet.sourceFingerprint] = neighborFingerprint
                    }

                    // Check destination
                    val ownFingerprint = cryptoManager.getLocalIdentityFingerprint()
                    if (packet.destinationFingerprint == ownFingerprint) {
                        // Packet reached its final destination! Emit payload to UI.
                        _incoming.emit(packet.payload)
                    } else if (packet.ttl > 1) {
                        // Forward packet (decrement TTL to avoid loops)
                        val forwardedPacket = packet.copy(ttl = packet.ttl - 1)
                        val forwardedBytes = forwardedPacket.serialize()
                        
                        val nextHop = routingTable[packet.destinationFingerprint]
                        if (nextHop != null) {
                            val nextSocket = activePeers[nextHop]
                            if (nextSocket != null && nextSocket.isConnected && !nextSocket.isClosed) {
                                Timber.d("WiFiDirectTransport: Routing packet from ${packet.sourceFingerprint} to ${packet.destinationFingerprint} via next hop $nextHop")
                                sendRaw(nextSocket, forwardedBytes)
                            }
                        } else {
                            // Flooding fallback: broadcast to all neighbors except the sender
                            Timber.d("WiFiDirectTransport: Flooding packet from ${packet.sourceFingerprint} to ${packet.destinationFingerprint} across neighbors")
                            for ((peer, peerSocket) in activePeers) {
                                if (peerSocket != socket && peerSocket.isConnected && !peerSocket.isClosed) {
                                    sendRaw(peerSocket, forwardedBytes)
                                }
                            }
                        }
                    }
                } catch (ex: Exception) {
                    Timber.w(ex, "WiFiDirectTransport: Failed to process incoming mesh packet")
                }
            }
        } catch (e: Exception) {
            Timber.d("WiFiDirectTransport: P2P Socket read closed: ${e.message}")
        }
    }

    private fun handleConnectionLoss() {
        _isConnected.set(false)
        val target = lastTarget
        if (target != null && !isManuallyDisconnected.get()) {
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                Timber.w("WiFiDirectTransport: Connection lost to $target. Attempting auto-reconnect in 5 seconds...")
                delay(5000)
                if (!isConnected && !isManuallyDisconnected.get()) {
                    val reconnected = connect(target)
                    if (!reconnected && !isManuallyDisconnected.get()) {
                        // Re-trigger reconnect loop if failed
                        handleConnectionLoss()
                    }
                }
            }
        }
    }

    override suspend fun send(data: ByteArray): Boolean {
        val dest = activeContactFingerprint ?: run {
            val fallback = activePeers.keys.firstOrNull()
            Timber.w("WiFiDirectTransport: activeContactFingerprint is null. Attempting fallback neighbor: $fallback")
            fallback
        } ?: return false

        val packet = MeshPacket(
            sourceFingerprint = cryptoManager.getLocalIdentityFingerprint(),
            destinationFingerprint = dest,
            ttl = 3,
            payload = data
        )
        val packetBytes = packet.serialize()

        // 1. Try to route via the next hop from routing table
        val nextHop = routingTable[dest]
        if (nextHop != null) {
            val socket = activePeers[nextHop]
            if (socket != null && socket.isConnected && !socket.isClosed) {
                return sendRaw(socket, packetBytes)
            }
        }

        // 2. Flooding fallback (broadcast to all connected peers)
        var sent = false
        for ((peer, socket) in activePeers) {
            if (socket.isConnected && !socket.isClosed) {
                if (sendRaw(socket, packetBytes)) {
                    sent = true
                }
            }
        }
        return sent
    }

    private fun sendRaw(socket: Socket, data: ByteArray): Boolean {
        return try {
            val outputStream = socket.getOutputStream()
            val lengthBuffer = ByteBuffer.allocate(4).putInt(data.size).array()
            outputStream.write(lengthBuffer)
            outputStream.write(data)
            outputStream.flush()
            true
        } catch (e: Exception) {
            Timber.e(e, "WiFiDirectTransport: failed to send bytes")
            try { socket.close() } catch (ex: Exception) {}
            false
        }
    }

    override fun observeIncoming(): Flow<ByteArray> = _incoming

    private suspend fun cleanupSocketAndGroup() {
        withContext(Dispatchers.IO) {
            reconnectJob?.cancel()
            serverJob?.cancel()
            
            // Close all client sockets
            for ((peer, socket) in activePeers) {
                try {
                    socket.close()
                } catch (e: Exception) {}
            }
            activePeers.clear()
            routingTable.clear()

            try {
                serverSocket?.close()
            } catch (e: Exception) {}
            serverSocket = null
            _isConnected.set(false)

            val p2pManager = manager
            val p2pChannel = channel
            if (p2pManager != null && p2pChannel != null) {
                p2pManager.removeGroup(p2pChannel, null)
            }
        }
    }

    override suspend fun disconnect() {
        isManuallyDisconnected.set(true)
        lastTarget = null
        cleanupSocketAndGroup()
    }
}
