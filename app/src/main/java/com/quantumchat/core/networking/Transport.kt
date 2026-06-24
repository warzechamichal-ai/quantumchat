package com.quantumchat.core.networking

import kotlinx.coroutines.flow.Flow

/**
 * Interface representing the network transport layer for secure communication.
 * Handles establishing connection, sending data, and observing incoming messages.
 * Allows multiple implementations (e.g., Local WiFi, WebSockets, WiFi Direct, Tor).
 */
interface Transport {
    /**
     * Indicates whether the transport is currently connected.
     */
    val isConnected: Boolean

    /**
     * Establishes a connection to the specified target.
     * Target can be an IP address, a public key fingerprint, or other identifier.
     * @return true if connection was established successfully, false otherwise.
     */
    suspend fun connect(target: String): Boolean

    /**
     * Transmits raw data over the established connection.
     * @return true if sending succeeded, false otherwise.
     */
    suspend fun send(data: ByteArray): Boolean

    /**
     * Emits incoming raw data in real-time.
     */
    fun observeIncoming(): Flow<ByteArray>

    /**
     * Disconnects the active connection.
     */
    suspend fun disconnect()
}
