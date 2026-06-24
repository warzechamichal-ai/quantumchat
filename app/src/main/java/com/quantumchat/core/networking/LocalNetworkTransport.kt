package com.quantumchat.core.networking

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import timber.log.Timber
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local network implementation of the Transport interface using direct TCP sockets.
 * Runs a background ServerSocket listening on port 9090 to accept incoming peer connections.
 * Initiates an outgoing client Socket to the peer's IP address when connect is called.
 * Uses a simple framing protocol: [4 bytes payload length] + [payload bytes].
 */
@Singleton
class LocalNetworkTransport @Inject constructor() : Transport {

    private val _isConnected = AtomicBoolean(false)
    override val isConnected: Boolean
        get() = _isConnected.get()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var serverSocket: ServerSocket? = null
    private var outgoingSocket: Socket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null

    init {
        startServer()
    }

    private fun startServer() {
        serverJob = scope.launch {
            try {
                // Listen on all network interfaces
                val server = ServerSocket(9090)
                serverSocket = server
                Timber.d("LocalNetworkTransport ServerSocket started on port 9090")
                while (isActive) {
                    val socket = server.accept()
                    Timber.d("Accepted local connection from: ${socket.remoteSocketAddress}")
                    launch {
                        handleIncomingClient(socket)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "ServerSocket error or closed in LocalNetworkTransport")
            }
        }
    }

    private suspend fun handleIncomingClient(socket: Socket) {
        socket.use { s ->
            try {
                val inputStream = s.getInputStream()
                while (scope.isActive && !s.isClosed) {
                    // Read length prefix (4 bytes)
                    val lengthBuffer = ByteArray(4)
                    var bytesRead = 0
                    while (bytesRead < 4) {
                        val read = withContext(Dispatchers.IO) {
                            inputStream.read(lengthBuffer, bytesRead, 4 - bytesRead)
                        }
                        if (read == -1) {
                            Timber.d("Local connection EOF reached for client: ${s.remoteSocketAddress}")
                            return
                        }
                        bytesRead += read
                    }
                    val length = ByteBuffer.wrap(lengthBuffer).int
                    if (length <= 0 || length > 10 * 1024 * 1024) { // 10MB limit
                        Timber.w("LocalNetworkTransport: Received invalid payload length: $length")
                        return
                    }

                    // Read payload bytes
                    val payload = ByteArray(length)
                    var payloadBytesRead = 0
                    while (payloadBytesRead < length) {
                        val read = withContext(Dispatchers.IO) {
                            inputStream.read(payload, payloadBytesRead, length - payloadBytesRead)
                        }
                        if (read == -1) {
                            Timber.w("Local connection EOF reached during payload read")
                            return
                        }
                        payloadBytesRead += read
                    }

                    Timber.i("LocalNetworkTransport received payload of size $length bytes")
                    _incoming.emit(payload)
                }
            } catch (e: Exception) {
                Timber.w("LocalNetworkTransport client connection closed: ${e.message}")
            }
        }
    }

    override suspend fun connect(target: String): Boolean {
        disconnect()
        // If target looks like a fingerprint (starts with QC-PQ), don't attempt IP connection
        if (target.startsWith("QC-PQ-")) {
            Timber.d("LocalNetworkTransport: target '$target' is a fingerprint, skipping.")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val parts = target.split(":")
                val host = parts[0]
                val port = if (parts.size > 1) parts[1].toInt() else 9090

                Timber.d("LocalNetworkTransport: Connecting to $host:$port")
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 5000) // 5 seconds connection timeout
                outgoingSocket = socket
                _isConnected.set(true)
                Timber.i("LocalNetworkTransport: Connected to $host:$port successfully.")
                true
            } catch (e: Exception) {
                Timber.w("LocalNetworkTransport: Failed to connect to target '$target': ${e.message}")
                _isConnected.set(false)
                false
            }
        }
    }

    override suspend fun send(data: ByteArray): Boolean {
        val socket = outgoingSocket
        if (socket == null || !socket.isConnected || socket.isClosed) {
            Timber.w("LocalNetworkTransport send failed: not connected.")
            _isConnected.set(false)
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val outputStream = socket.getOutputStream()
                val lengthBuffer = ByteBuffer.allocate(4).putInt(data.size).array()
                outputStream.write(lengthBuffer)
                outputStream.write(data)
                outputStream.flush()
                Timber.i("LocalNetworkTransport: Sent ${data.size} bytes successfully.")
                true
            } catch (e: Exception) {
                Timber.e(e, "LocalNetworkTransport: Failed to send data")
                _isConnected.set(false)
                try { socket.close() } catch (ex: Exception) {}
                outgoingSocket = null
                false
            }
        }
    }

    override fun observeIncoming(): Flow<ByteArray> = _incoming

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                outgoingSocket?.close()
            } catch (e: Exception) {
                // Ignore
            }
            outgoingSocket = null
            _isConnected.set(false)
            Timber.d("LocalNetworkTransport: Disconnected outgoing socket.")
        }
    }

    /**
     * Shuts down the background ServerSocket and cancels the coroutine job.
     */
    fun shutdown() {
        serverJob?.cancel()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
