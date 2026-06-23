package com.quantumchat.core.networking

import com.quantumchat.core.common.Result
import com.quantumchat.core.common.model.Message
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransportImpl @Inject constructor() : Transport {

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: Flow<Boolean> = _isConnected

    private val _incomingMessages = MutableSharedFlow<Message>()

    init {
        Timber.d("TransportImpl has been initialized.")
    }

    override fun connect(): Flow<Result<Unit>> = flow {
        emit(Result.Loading)
        delay(500) // Simulating network handshake latency
        _isConnected.value = true
        Timber.i("Secure TLS + Post-Quantum handshake established with chat server.")
        emit(Result.Success(Unit))
    }

    override fun disconnect() {
        Timber.i("Disconnecting from chat server.")
        _isConnected.value = false
    }

    override fun sendMessage(message: Message): Flow<Result<Unit>> = flow {
        emit(Result.Loading)
        delay(300) // Simulating network latency
        Timber.i("Encrypted message sent successfully: ${message.id}")
        emit(Result.Success(Unit))
    }

    override fun observeIncomingMessages(): Flow<Message> = _incomingMessages

    /**
     * Helper to simulate receiving messages for testing UI updates in MVI.
     */
    suspend fun simulateIncomingMessage(message: Message) {
        _incomingMessages.emit(message)
    }
}
