package com.quantumchat.core.networking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class WebSocketMessage(
    val senderFingerprint: String,
    val recipientFingerprint: String,
    val payloadBase64: String
)

/**
 * WebSocket implementation of the Transport interface.
 * Primarily used for central server-routed communication or echo testing.
 * Connects to a central WebSocket server and sends base64-encoded payloads wrapped in a WebSocketMessage.
 */
@Singleton
class WebSocketTransport @Inject constructor() : Transport {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    
    @Volatile
    private var _isConnected = false
    override val isConnected: Boolean
        get() = _isConnected

    private val _incomingMessages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val myFingerprint: String = "QC-PQ-ME"
    private var activeContactFingerprint: String? = null

    override suspend fun connect(target: String): Boolean {
        disconnect()
        activeContactFingerprint = target

        val wsUrl = "ws://10.0.2.2:8080" // local WS server for multi-device routing
        val echoUrl = "wss://echo.websocket.org" // public echo server fallback

        Timber.d("Connecting to WebSocket. Target contact fingerprint: $target")
        
        val connectionResult = MutableSharedFlow<Boolean>(replay = 1)
        var connected = false

        val request = Request.Builder().url(wsUrl).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.i("WebSocket connection opened to local server: $wsUrl")
                _isConnected = true
                scope.launch { connectionResult.emit(true) }
                connected = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessageText(text, target)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.w("WebSocket connection to local server $wsUrl failed: ${t.message}. Trying public echo fallback...")
                if (!connected) {
                    fallbackToEcho(echoUrl, target, connectionResult)
                } else {
                    _isConnected = false
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                _isConnected = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected = false
            }
        }

        webSocket = client.newWebSocket(request, listener)

        // Wait up to 5 seconds for connection to establish
        return try {
            kotlinx.coroutines.withTimeout(5000) {
                connectionResult.first()
            }
        } catch (e: Exception) {
            Timber.w("WebSocket connection timed out or failed: ${e.message}")
            false
        }
    }

    private fun fallbackToEcho(url: String, target: String, connectionResult: MutableSharedFlow<Boolean>) {
        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.i("WebSocket connection opened to fallback echo server: $url")
                _isConnected = true
                scope.launch { connectionResult.emit(true) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessageText(text, target)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.e("WebSocket connection to fallback echo server $url failed: ${t.message}")
                _isConnected = false
                scope.launch { connectionResult.emit(false) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                _isConnected = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected = false
            }
        }
        webSocket = client.newWebSocket(request, listener)
    }

    private fun handleIncomingMessageText(text: String, currentContactFingerprint: String) {
        Timber.d("Received raw WebSocket message text: $text")
        try {
            val msg = Json.decodeFromString<WebSocketMessage>(text)
            val decodedPayload = java.util.Base64.getDecoder().decode(msg.payloadBase64)
            
            // In a real routed server, the sender is Bob's fingerprint.
            // On echo.websocket.org, the sent payload is echoed back exactly, so sender is "me".
            // If sender is "me", we rewrite the sender fingerprint to the active contact to simulate receiving it.
            // In either case, we emit the decoded raw bytes to our stream.
            scope.launch {
                _incomingMessages.emit(decodedPayload)
            }
        } catch (e: Exception) {
            Timber.w("Failed to parse incoming WebSocket message: ${e.message}")
        }
    }

    override suspend fun send(data: ByteArray): Boolean {
        val ws = webSocket
        if (ws == null || !_isConnected) {
            Timber.w("Cannot send over WebSocket: not connected.")
            return false
        }
        val target = activeContactFingerprint ?: return false

        return withContext(Dispatchers.IO) {
            try {
                val base64Payload = java.util.Base64.getEncoder().encodeToString(data)
                val payload = WebSocketMessage(
                    senderFingerprint = "me", // Server replaces this with the actual sender session ID
                    recipientFingerprint = target,
                    payloadBase64 = base64Payload
                )
                val jsonText = Json.encodeToString(WebSocketMessage.serializer(), payload)
                
                val sent = ws.send(jsonText)
                if (sent) {
                    Timber.i("Sent WebSocket payload successfully.")
                    true
                } else {
                    Timber.w("WebSocket send returned false (queue full or socket closed).")
                    false
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to send WebSocket message")
                false
            }
        }
    }

    override fun observeIncoming(): Flow<ByteArray> = _incomingMessages

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                webSocket?.close(1000, "User disconnected")
            } catch (e: Exception) {
                // Ignore
            }
            webSocket = null
            _isConnected = false
            activeContactFingerprint = null
        }
    }
}
