package com.quantumchat.core.networking

import com.quantumchat.core.crypto.CryptoManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import timber.log.Timber
import java.io.InputStream
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
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
class LocalNetworkTransport @Inject constructor(
    private val cryptoManager: CryptoManager
) : Transport {

    private val _isConnected = AtomicBoolean(false)
    override val isConnected: Boolean
        get() = _isConnected.get()

    private val _handshakeCompleted = AtomicBoolean(false)
    val handshakeCompleted: Boolean
        get() = _handshakeCompleted.get()

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
                Timber.d("LocalNetworkTransport: Attempting to start ServerSocket. Binding port 9090...")
                val server = ServerSocket()
                server.reuseAddress = true
                
                val bindAddress = InetSocketAddress(InetAddress.getByName("0.0.0.0"), 9090)
                server.bind(bindAddress)
                serverSocket = server
                Timber.i("LocalNetworkTransport: ServerSocket successfully bound and listening on ${server.localSocketAddress}")
                
                while (isActive) {
                    Timber.d("LocalNetworkTransport: ServerSocket waiting for incoming connections...")
                    val socket = server.accept()
                    Timber.i("LocalNetworkTransport: Accepted incoming TCP connection from remote address: ${socket.remoteSocketAddress} (Local port: ${socket.localPort})")
                    launch {
                        try {
                            Timber.i("LocalNetworkTransport Handshake (Incoming): Initiating handshake for client ${socket.remoteSocketAddress}...")
                            
                            // 1. Read remote fingerprint
                            val remoteFingerprint = readRemoteFingerprint(socket)
                            Timber.i("LocalNetworkTransport Handshake (Incoming): Received remote fingerprint: $remoteFingerprint")

                            // 2. Send local fingerprint
                            writeLocalFingerprint(socket)

                            // Ustaw flagę na true po pomyślnym handshake'u
                            _handshakeCompleted.set(true)
                            socket.soTimeout = 0 // Reset timeout to infinite after handshake completes
                            Timber.i("LocalNetworkTransport Handshake (Incoming): Handshake completed successfully.")

                            // 3. Handle incoming messages
                            handleIncomingClient(socket)
                        } catch (e: Exception) {
                            Timber.e(e, "LocalNetworkTransport Handshake (Incoming): Handshake failed for client ${socket.remoteSocketAddress}")
                            try { socket.close() } catch (ex: Exception) {}
                        }
                    }
                }
            } catch (be: BindException) {
                Timber.e(be, "LocalNetworkTransport ServerSocket BIND FAILED on port 9090. Port is likely already in use.")
            } catch (ioe: IOException) {
                Timber.e(ioe, "LocalNetworkTransport ServerSocket encountered an IO Exception during binding or accepting connections.")
            } catch (e: Exception) {
                Timber.e(e, "LocalNetworkTransport ServerSocket encountered unexpected exception.")
            } finally {
                Timber.d("LocalNetworkTransport: ServerSocket startServer loop finished.")
            }
        }
    }

    private suspend fun readRemoteFingerprint(socket: Socket): String {
        socket.soTimeout = 15000
        val inputStream = socket.getInputStream()
        val lenBytes = ByteArray(4)
        var length = -1
        var attempts = 0
        val maxAttempts = 3
        var lastException: Exception? = null

        while (attempts < maxAttempts) {
            attempts++
            try {
                var readBytes = 0
                for (i in 0 until 4) lenBytes[i] = 0
                while (readBytes < 4) {
                    val read = withContext(Dispatchers.IO) {
                        inputStream.read(lenBytes, readBytes, 4 - readBytes)
                    }
                    if (read == -1) throw java.io.EOFException("EOF reading remote fingerprint length")
                    readBytes += read
                }
                val parsedLength = ByteBuffer.wrap(lenBytes).int
                if (parsedLength <= 0 || parsedLength > 1024) {
                    throw IOException("Invalid remote fingerprint length: $parsedLength")
                }
                length = parsedLength
                break // Success!
            } catch (e: Exception) {
                lastException = e
                Timber.w("LocalNetworkTransport: Attempt $attempts failed to read fingerprint length: ${e.message}")
                if (attempts < maxAttempts) {
                    delay(300)
                }
            }
        }

        if (length == -1) {
            throw IOException("Handshake failed after $maxAttempts attempts to read fingerprint length", lastException)
        }

        val remoteBytes = ByteArray(length)
        var readPayload = 0
        while (readPayload < length) {
            val read = withContext(Dispatchers.IO) {
                inputStream.read(remoteBytes, readPayload, length - readPayload)
            }
            if (read == -1) throw java.io.EOFException("Handshake failed: EOF reading remote fingerprint")
            readPayload += read
        }
        val remoteFingerprint = String(remoteBytes, Charsets.UTF_8)
        return remoteFingerprint
    }

    private suspend fun writeLocalFingerprint(socket: Socket) {
        val localFingerprint = cryptoManager.getLocalIdentityFingerprint()
        Timber.i("LocalNetworkTransport: Sending local fingerprint: $localFingerprint")
        val localFingerprintBytes = localFingerprint.toByteArray(Charsets.UTF_8)
        val lengthBuffer = ByteBuffer.allocate(4).putInt(localFingerprintBytes.size).array()
        val outputStream = socket.getOutputStream()
        withContext(Dispatchers.IO) {
            outputStream.write(lengthBuffer)
            outputStream.write(localFingerprintBytes)
            outputStream.flush()
        }
        Timber.i("LocalNetworkTransport: Local fingerprint sent successfully.")
    }

    private suspend fun handleIncomingClient(socket: Socket) {
        Timber.d("LocalNetworkTransport: Starting client session for incoming socket: remote=${socket.remoteSocketAddress}, localPort=${socket.localPort}")
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
                            Timber.d("LocalNetworkTransport: EOF reached while reading length prefix from remote=${s.remoteSocketAddress}")
                            return
                        }
                        bytesRead += read
                    }
                    val length = ByteBuffer.wrap(lengthBuffer).int
                    Timber.v("LocalNetworkTransport: Read incoming length prefix: $length bytes from remote=${s.remoteSocketAddress}")
                    if (length <= 0 || length > 10 * 1024 * 1024) { // 10MB limit
                        Timber.w("LocalNetworkTransport: Received invalid payload length: $length from remote=${s.remoteSocketAddress}")
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
                            Timber.w("LocalNetworkTransport: EOF reached while reading payload bytes from remote=${s.remoteSocketAddress}")
                            return
                        }
                        payloadBytesRead += read
                    }

                    Timber.i("LocalNetworkTransport: Received payload of size $length bytes from remote=${s.remoteSocketAddress} (emitting to flow)")
                    _incoming.emit(payload)
                }
            } catch (e: Exception) {
                Timber.w(e, "LocalNetworkTransport: Incoming client connection closed with exception: ${e.message} for remote=${s.remoteSocketAddress}")
            } finally {
                Timber.d("LocalNetworkTransport: Ended client session for remote=${s.remoteSocketAddress}")
            }
        }
    }

    override suspend fun connect(target: String): Boolean {
        disconnect()
        // If target looks like a fingerprint (starts with QC-PQ), don't attempt IP connection
        if (target.startsWith("QC-PQ-")) {
            Timber.d("LocalNetworkTransport: target '$target' is a fingerprint, skipping direct TCP connection.")
            return false
        }

        return withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                val parts = target.split(":")
                val host = parts[0]
                val port = if (parts.size > 1) parts[1].toInt() else 9090

                Timber.i("LocalNetworkTransport: Connecting outgoing socket to target: $host:$port")
                val s = Socket()
                socket = s
                s.connect(InetSocketAddress(host, port), 5000) // 5 seconds connection timeout
                Timber.i("LocalNetworkTransport: Outgoing TCP connection to $host:$port established. Starting handshake...")
                
                // 1. Send our fingerprint
                writeLocalFingerprint(s)

                // 2. Read remote fingerprint
                val remoteFingerprint = readRemoteFingerprint(s)
                Timber.i("LocalNetworkTransport Handshake (Outgoing): Received remote fingerprint: $remoteFingerprint")

                // Ustaw flagę na true po pomyślnym handshake'u
                _handshakeCompleted.set(true)
                s.soTimeout = 0 // Reset timeout to infinite after handshake completes
                Timber.i("LocalNetworkTransport Handshake (Outgoing): Handshake completed successfully.")

                outgoingSocket = s
                _isConnected.set(true)
                Timber.i("LocalNetworkTransport: Outgoing connection to $host:$port successfully established. Local address: ${s.localSocketAddress}")
                true
            } catch (e: Exception) {
                Timber.e(e, "LocalNetworkTransport Handshake (Outgoing): Failed to connect to target '$target': ${e.message}")
                _isConnected.set(false)
                try { socket?.close() } catch (ex: Exception) {}
                false
            }
        }
    }

    override suspend fun send(data: ByteArray): Boolean {
        val socket = outgoingSocket
        if (socket == null || !socket.isConnected || socket.isClosed) {
            Timber.w("LocalNetworkTransport: Send failed because socket is null, disconnected, or closed.")
            _isConnected.set(false)
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                Timber.d("LocalNetworkTransport: Sending payload of size ${data.size} bytes to ${socket.remoteSocketAddress}...")
                val outputStream = socket.getOutputStream()
                val lengthBuffer = ByteBuffer.allocate(4).putInt(data.size).array()
                outputStream.write(lengthBuffer)
                outputStream.write(data)
                outputStream.flush()
                Timber.i("LocalNetworkTransport: Successfully wrote and flushed ${data.size} bytes to ${socket.remoteSocketAddress}.")
                true
            } catch (e: Exception) {
                Timber.e(e, "LocalNetworkTransport: Failed to write data to ${socket.remoteSocketAddress}")
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
            _handshakeCompleted.set(false)
            val socket = outgoingSocket
            if (socket != null) {
                Timber.d("LocalNetworkTransport: Disconnecting outgoing socket: remote=${socket.remoteSocketAddress}")
                try {
                    socket.close()
                    Timber.i("LocalNetworkTransport: Outgoing socket successfully closed.")
                } catch (e: Exception) {
                    Timber.w(e, "LocalNetworkTransport: Exception while closing outgoing socket")
                }
            }
            outgoingSocket = null
            _isConnected.set(false)
        }
    }

    /**
     * Shuts down the background ServerSocket and cancels the coroutine job.
     */
    fun shutdown() {
        Timber.i("LocalNetworkTransport: Shutting down transport services...")
        serverJob?.cancel()
        try {
            serverSocket?.close()
            Timber.i("LocalNetworkTransport: ServerSocket successfully closed.")
        } catch (e: Exception) {
            Timber.w(e, "LocalNetworkTransport: Exception while closing ServerSocket")
        }
    }
}
