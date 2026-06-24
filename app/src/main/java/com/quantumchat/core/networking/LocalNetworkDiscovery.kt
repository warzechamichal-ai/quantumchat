package com.quantumchat.core.networking

import com.quantumchat.core.crypto.CryptoManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton



/**
 * LocalNetworkDiscovery handles automatic peer discovery in the same WiFi/local network using UDP Broadcasts.
 * It periodically broadcasts its own identity (fingerprint and listening port) and listens for broadcasts
 * from other peers. Discovered peers are maintained in a list and emitted via a Flow. Expired peers (no packets
 * received for 15 seconds) are automatically cleaned up and removed.
 */
@Singleton
class LocalNetworkDiscovery @Inject constructor(
    private val cryptoManager: CryptoManager
) {
    private val discoveryPort = 8888
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    
    /**
     * Flow emitting the list of currently active discovered devices in the local network.
     */
    val devices: Flow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val deviceMap = ConcurrentHashMap<String, DiscoveredDevice>()
    private var socket: DatagramSocket? = null
    
    @Volatile
    private var isRunning = false
    private var listenJob: Job? = null
    private var broadcastJob: Job? = null
    private var cleanupJob: Job? = null

    /**
     * Starts the discovery service, launching background threads/coroutines to broadcast own info,
     * listen for incoming broadcasts, and clean up expired devices.
     */
    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true
        Timber.d("Starting LocalNetworkDiscovery service")

        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 3000
                bind(InetSocketAddress(discoveryPort))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to bind DatagramSocket on discovery port $discoveryPort")
            // Try fallback socket just for broadcasting if listener bind failed
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 3000
                }
                Timber.w("LocalNetworkDiscovery started in SEND-ONLY fallback mode.")
            } catch (ex: Exception) {
                Timber.e(ex, "Failed to create fallback DatagramSocket")
                isRunning = false
                return
            }
        }

        val activeSocket = socket ?: return

        // 1. Coroutine to listen for incoming broadcasts
        listenJob = scope.launch {
            val buffer = ByteArray(10240)
            while (isActive && isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    withContext(Dispatchers.IO) {
                        activeSocket.receive(packet)
                    }
                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val hostAddress = packet.address?.hostAddress ?: ""
                    handleIncomingPacket(message, hostAddress)
                } catch (e: SocketTimeoutException) {
                    // Normal behavior due to soTimeout, loop and check status
                } catch (e: Exception) {
                    if (isRunning) {
                        Timber.d("Datagram receive interrupted or socket closed: ${e.message}")
                    }
                }
            }
        }

        // 2. Coroutine to periodically broadcast own info
        broadcastJob = scope.launch {
            val ownPort = 9090
            val broadcastAddress = InetAddress.getByName("255.255.255.255")

            while (isActive && isRunning) {
                try {
                    val ownFingerprint = cryptoManager.getLocalIdentityFingerprint()
                    val payload = "QC-DISCOVERY:$ownFingerprint:$ownPort"
                    
                    // Sign discovery packets using post-quantum ML-DSA (Dilithium) to guarantee authenticity.
                    // This protects against fingerprint hijacking and IP spoofing.
                    val signature = cryptoManager.signMessage(payload.toByteArray(Charsets.UTF_8))
                    val signatureB64 = java.util.Base64.getEncoder().encodeToString(signature)
                    val broadcastMsg = "$payload:$signatureB64"
                    val broadcastBytes = broadcastMsg.toByteArray(Charsets.UTF_8)
                    
                    val sendPacket = DatagramPacket(
                        broadcastBytes,
                        broadcastBytes.size,
                        broadcastAddress,
                        discoveryPort
                    )
                    withContext(Dispatchers.IO) {
                        try {
                            activeSocket.send(sendPacket)
                            Timber.d("Sent signed discovery broadcast packet: $broadcastMsg")
                        } catch (ioe: java.io.IOException) {
                            Timber.w("LocalNetworkDiscovery: UDP broadcast send failed (e.g. EPERM or permission denied): ${ioe.message}")
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to transmit discovery broadcast")
                }
                delay(5000) // Broadcast every 5 seconds
            }
        }

        // 3. Coroutine to clean up devices not seen for 15 seconds
        cleanupJob = scope.launch {
            while (isActive && isRunning) {
                delay(5000)
                val now = System.currentTimeMillis()
                var changed = false
                val iterator = deviceMap.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (now - entry.value.lastSeen > 15000) {
                        Timber.d("LocalNetworkDiscovery: Expired discovered device: ${entry.key}")
                        iterator.remove()
                        changed = true
                    }
                }
                if (changed) {
                    _devices.value = deviceMap.values.toList()
                }
            }
        }
    }

    /**
     * Stops the discovery service, closing sockets and cancelling background jobs.
     */
    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        Timber.d("Stopping LocalNetworkDiscovery service")

        listenJob?.cancel()
        broadcastJob?.cancel()
        cleanupJob?.cancel()

        socket?.close()
        socket = null

        deviceMap.clear()
        _devices.value = emptyList()
    }

    private fun handleIncomingPacket(message: String, senderIp: String) {
        // Expected signed message packet format: QC-DISCOVERY:<fingerprint>:<port>:<signatureBase64>
        if (!message.startsWith("QC-DISCOVERY:")) return

        try {
            val parts = message.split(":")
            if (parts.size >= 4) {
                val fingerprint = parts[1]
                val port = parts[2].toInt()
                val signatureB64 = parts[3]

                // Ignore own broadcast packet
                val ownFingerprint = cryptoManager.getLocalIdentityFingerprint()
                if (fingerprint == ownFingerprint) return

                val payload = "QC-DISCOVERY:$fingerprint:$port"
                val signature = java.util.Base64.getDecoder().decode(signatureB64)

                // Cryptographically verify broadcast packet using sender's ML-DSA public key (authenticity layer)
                val isVerified = cryptoManager.verifyMessageSignature(
                    payload.toByteArray(Charsets.UTF_8),
                    signature,
                    fingerprint
                )

                if (!isVerified) {
                    Timber.w("LocalNetworkDiscovery: Signature verification FAILED for peer $fingerprint at $senderIp. Packet dropped.")
                    return
                }

                val device = DiscoveredDevice(
                    fingerprint = fingerprint,
                    ipAddress = senderIp,
                    port = port,
                    lastSeen = System.currentTimeMillis()
                )

                val prev = deviceMap.put(fingerprint, device)
                if (prev == null || prev.ipAddress != senderIp || prev.port != port) {
                    Timber.i("LocalNetworkDiscovery: Discovered new verified peer: $fingerprint at $senderIp:$port")
                }
                _devices.value = deviceMap.values.toList()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse incoming discovery packet payload: $message")
        }
    }
}
