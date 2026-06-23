package com.quantumchat.core.networking

import com.quantumchat.core.common.Result
import com.quantumchat.core.common.model.Message
import kotlinx.coroutines.flow.Flow

/**
 * Interface representing the network transport layer for secure communication.
 * Handles establishing connection, sending encrypted messages, and observing incoming messages.
 */
interface Transport {

    /**
     * Flow tracking the network connection status (true = connected, false = disconnected).
     */
    val isConnected: Flow<Boolean>

    /**
     * Establishes a secure connection to the chat server (e.g. via secure WebSockets or gRPC).
     */
    fun connect(): Flow<Result<Unit>>

    /**
     * Disconnects from the network server.
     */
    fun disconnect()

    /**
     * Transmits a message to the recipient.
     */
    fun sendMessage(message: Message): Flow<Result<Unit>>

    /**
     * Emits incoming messages in real-time.
     */
    fun observeIncomingMessages(): Flow<Message>
}
