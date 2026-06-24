package com.quantumchat.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quantumchat.core.common.model.Contact
import com.quantumchat.core.common.model.Message
import com.quantumchat.core.crypto.CryptoManager
import com.quantumchat.core.networking.TransportManager
import com.quantumchat.core.networking.DiscoveredDevice
import com.quantumchat.core.database.MessageDao
import com.quantumchat.core.database.toDomain
import com.quantumchat.core.database.toEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import com.quantumchat.core.data.ContactRepository
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.toRoute
import com.quantumchat.ChatScreenDestination

// MVI State
data class ChatUiState(
    val contact: Contact? = null,
    val messages: List<Message> = emptyList(),
    val connectionActive: Boolean = false,
    val inputText: String = "",
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val isScanningManually: Boolean = false
)

// MVI Intent
sealed interface ChatUiIntent {
    data class LoadChat(val contactId: String, val contactName: String) : ChatUiIntent
    data class SendTextMessage(val content: String) : ChatUiIntent
    data class UpdateInputText(val text: String) : ChatUiIntent
    object ForceDiscovery : ChatUiIntent
}

// ViewModel
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val transportManager: TransportManager,
    private val messageDao: MessageDao,
    private val contactRepository: ContactRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val chatArgs = savedStateHandle.toRoute<ChatScreenDestination>()
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        timber.log.Timber.d("ChatViewModel INIT - contactId: ${chatArgs.contactId}, contactName: ${chatArgs.contactName}")

        // Observe discovered devices in the local network via consolidated flow
        viewModelScope.launch {
            transportManager.discoveredDevices.collect { devicesList ->
                _state.value = _state.value.copy(discoveredDevices = devicesList)
                
                // Automatically connect via LAN/P2P if contact is discovered
                val contact = _state.value.contact ?: return@collect
                if (!_state.value.connectionActive) {
                    val matchingDevice = devicesList.find { it.fingerprint == contact.publicKeyFingerprint }
                    if (matchingDevice != null) {
                        timber.log.Timber.i("TransportManager: Found matching active contact in discovery: ${matchingDevice.ipAddress}:${matchingDevice.port}. Connecting P2P...")
                        val success = transportManager.connect("${matchingDevice.ipAddress}:${matchingDevice.port}")
                        _state.value = _state.value.copy(connectionActive = success)
                        if (success) {
                            timber.log.Timber.i("TransportManager: Connected via P2P. Stopping active discovery to save battery.")
                            transportManager.stopDiscovery()
                        }
                    }
                }
            }
        }

        // Intelligent Battery Optimization & Direct IP Reconnection Loop:
        // Periodically checks connection status, retries direct IP transport connection, and manages discovery state.
        viewModelScope.launch {
            while (isActive) {
                val contact = _state.value.contact
                val fingerprint = contact?.publicKeyFingerprint
                val isCurrentlyConnected = transportManager.isConnected

                // Reconnection logic for direct IP targets if disconnected
                if (!isCurrentlyConnected && contact != null) {
                    val targetAddress = contact.onionAddress
                    if (targetAddress != null && targetAddress.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?$"""))) {
                        timber.log.Timber.i("ChatViewModel: Direct IP connection is inactive. Retrying auto-connect to: $targetAddress")
                        val success = transportManager.connect(targetAddress)
                        timber.log.Timber.d("ChatViewModel: Auto-connect result to $targetAddress was: $success")
                    }
                }

                val isOnline = transportManager.isConnected || (fingerprint != null && transportManager.onlinePeers.first().contains(fingerprint))
                if (_state.value.connectionActive != isOnline) {
                    _state.value = _state.value.copy(connectionActive = isOnline)
                }

                if (transportManager.isConnected) {
                    // Turn discovery OFF if we already have an active secure connection
                    transportManager.stopDiscovery()
                } else if (!_state.value.isScanningManually) {
                    // Turn discovery ON to find local devices only if not already connected
                    transportManager.startDiscovery()
                }
                delay(5000) // Poll and check connection/discovery state every 5 seconds
            }
        }
    }

    fun handleIntent(intent: ChatUiIntent) {
        timber.log.Timber.d("ChatViewModel handleIntent called with intent: $intent")
        viewModelScope.launch {
            when (intent) {
                is ChatUiIntent.LoadChat -> {
                    timber.log.Timber.d("ChatViewModel: Processing LoadChat intent for contactId=${intent.contactId}")
                    val contact = contactRepository.getContact(intent.contactId)
                    timber.log.Timber.i("ChatViewModel: Database query for ID '${intent.contactId}' returned: $contact")
                    
                    if (contact != null) {
                        val fingerprint = contact.publicKeyFingerprint
                        timber.log.Timber.i("ChatViewModel: Successfully loaded contact '${contact.name}'. Fingerprint='$fingerprint', onionAddress='${contact.onionAddress}'")
                        
                        val targetAddress = contact.onionAddress ?: fingerprint
                        val isIp = contact.onionAddress != null && contact.onionAddress.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?$"""))

                        if (!isIp) {
                            timber.log.Timber.i("ChatViewModel: Attempting to establish secure PQ hybrid session for contact '${contact.name}' (fingerprint: $fingerprint)")
                            try {
                                cryptoManager.establishSecureSession(fingerprint)
                                timber.log.Timber.i("ChatViewModel: Secure PQ session established successfully for fingerprint: $fingerprint")
                            } catch (e: Exception) {
                                timber.log.Timber.e(e, "ChatViewModel: Failed to establish secure PQ session for fingerprint: $fingerprint")
                            }
                        }

                        _state.value = _state.value.copy(
                            contact = contact
                        )

                        // Observe messages from database reactively
                        viewModelScope.launch {
                            messageDao.observeMessagesForContact(contact.id).collect { entities ->
                                timber.log.Timber.d("ChatViewModel: Observed ${entities.size} messages in database for contact: ${contact.id}")
                                _state.value = _state.value.copy(
                                    messages = entities.map { it.toDomain() }
                                )
                            }
                        }

                        // Observe online peers flow reactively
                        viewModelScope.launch {
                            transportManager.onlinePeers.collect { onlineSet ->
                                val isOnline = onlineSet.contains(fingerprint) || transportManager.isConnected
                                timber.log.Timber.d("ChatViewModel: Observed onlinePeers update. TargetOnline=${onlineSet.contains(fingerprint)}, TransportConnected=${transportManager.isConnected}")
                                _state.value = _state.value.copy(connectionActive = isOnline)
                            }
                        }

                        // TCP health check / diagnostics for direct IP addresses
                        if (isIp) {
                            timber.log.Timber.i("ChatViewModel: Contact address '${contact.onionAddress}' is a direct IP. Running TCP Socket health check...")
                            viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    val parts = contact.onionAddress!!.split(":")
                                    val host = parts[0]
                                    val port = if (parts.size > 1) parts[1].toInt() else 9090
                                    timber.log.Timber.d("ChatViewModel Diagnostics: Testing TCP connection to $host:$port...")
                                    val testSocket = java.net.Socket()
                                    testSocket.connect(java.net.InetSocketAddress(host, port), 3000)
                                    timber.log.Timber.i("ChatViewModel Diagnostics: Direct TCP Socket connection check to $host:$port SUCCEEDED. Destination socket is OPEN and listening.")
                                    testSocket.close()
                                } catch (e: Exception) {
                                    timber.log.Timber.e(e, "ChatViewModel Diagnostics: Direct TCP Socket connection check to ${contact.onionAddress} FAILED. Reason: ${e.message}")
                                }
                            }
                        }

                        // Connect to transport for this contact (TCP IP or WebSocket fingerprint fallback)
                        timber.log.Timber.i("ChatViewModel: Requesting TransportManager connection to '$targetAddress'")
                        val connected = transportManager.connect(targetAddress)
                        timber.log.Timber.i("ChatViewModel: TransportManager connection result for '$targetAddress': connected=$connected")
                        
                        if (isIp && connected) {
                            timber.log.Timber.i("ChatViewModel: TCP Connection successful. Now establishing secure PQ session for fingerprint: $fingerprint")
                            try {
                                cryptoManager.establishSecureSession(fingerprint, isNewSession = true)
                                timber.log.Timber.i("ChatViewModel: Secure PQ session established successfully for fingerprint: $fingerprint")
                            } catch (e: Exception) {
                                timber.log.Timber.e(e, "ChatViewModel: Failed to establish secure PQ session for fingerprint: $fingerprint")
                            }
                        }

                        val isOnline = connected || transportManager.onlinePeers.first().contains(fingerprint)
                        _state.value = _state.value.copy(connectionActive = isOnline)
                    } else {
                        timber.log.Timber.e("ChatViewModel: Contact not found in database for ID: ${intent.contactId}")
                    }
                }
                is ChatUiIntent.SendTextMessage -> {
                    val text = intent.content.trim()
                    timber.log.Timber.d("ChatViewModel: Processing SendTextMessage intent. Text length: ${text.length} characters")
                    if (text.isEmpty()) {
                        timber.log.Timber.w("ChatViewModel: Cannot send message, text content is empty.")
                        return@launch
                    }

                    val targetContact = _state.value.contact
                    if (targetContact == null) {
                        timber.log.Timber.e("ChatViewModel: Cannot send message, targetContact is NULL in state.")
                        return@launch
                    }

                    timber.log.Timber.i("ChatViewModel: Preparing to send message to contact '${targetContact.name}' (fingerprint: ${targetContact.publicKeyFingerprint})")

                    // 1. Build secure Message entity
                    val displayMessage = Message(
                        id = System.currentTimeMillis().toString(),
                        senderId = "me",
                        recipientId = targetContact.id,
                        content = text,
                        timestamp = System.currentTimeMillis(),
                        isEncrypted = true,
                        status = com.quantumchat.core.common.model.MessageStatus.SENT
                    )

                    // 2. Save message to local database immediately (UI will auto-update via observation)
                    timber.log.Timber.d("ChatViewModel: Saving outgoing message to database: ${displayMessage.id}")
                    messageDao.insertMessage(displayMessage.toEntity())

                    // 3. Serialize and encrypt message content
                    val jsonStr = Json.encodeToString(displayMessage)
                    val plainBytes = jsonStr.toByteArray(Charsets.UTF_8)
                    val cipherBytes = cryptoManager.encryptMessage(plainBytes, targetContact.publicKeyFingerprint)

                    _state.value = _state.value.copy(inputText = "")

                    // 4. Send encrypted payload over network transport
                    val success = transportManager.send(cipherBytes)
                    if (!success) {
                        timber.log.Timber.w("Failed to send message over transport")
                    }
                    val isOnline = transportManager.isConnected || transportManager.onlinePeers.first().contains(targetContact.publicKeyFingerprint)
                    _state.value = _state.value.copy(connectionActive = isOnline)
                }
                is ChatUiIntent.UpdateInputText -> {
                    _state.value = _state.value.copy(inputText = intent.text)
                }
                is ChatUiIntent.ForceDiscovery -> {
                    _state.value = _state.value.copy(isScanningManually = true)
                    transportManager.startDiscovery()
                    // Auto-stop manual scan status after 15 seconds
                    viewModelScope.launch {
                        delay(15000)
                        _state.value = _state.value.copy(isScanningManually = false)
                        if (transportManager.isConnected) {
                            transportManager.stopDiscovery()
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        transportManager.stopDiscovery()
        viewModelScope.launch {
            transportManager.disconnect()
        }
    }
}

// Screen Composable
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    contactId: String,
    contactName: String,
    onNavigateBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    timber.log.Timber.d("ChatScreen Composable: Rendering with contactId=$contactId, contactName=$contactName")
    val state by viewModel.state.collectAsState()

    // Initialize screen state with passed parameters
    androidx.compose.runtime.LaunchedEffect(contactId, contactName) {
        timber.log.Timber.d("ChatScreen LaunchedEffect: Triggering LoadChat for contactId=$contactId")
        viewModel.handleIntent(ChatUiIntent.LoadChat(contactId, contactName))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.contact?.name ?: contactName, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val matchingDevice = state.contact?.let { c -> state.discoveredDevices.find { it.fingerprint == c.publicKeyFingerprint } }
                            val networkText = if (state.connectionActive) {
                                if (matchingDevice != null) "Secured LAN P2P (${matchingDevice.ipAddress})" else "Secured Server Connection"
                            } else {
                                "Reconnecting..."
                            }
                            val statusColor = if (state.connectionActive) {
                                if (matchingDevice != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(
                                        color = statusColor,
                                        shape = RoundedCornerShape(3.dp)
                                    )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = networkText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text("←", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    if (!state.connectionActive) {
                        TextButton(
                            onClick = { viewModel.handleIntent(ChatUiIntent.ForceDiscovery) },
                            enabled = !state.isScanningManually
                        ) {
                            Text(
                                text = if (state.isScanningManually) "Szukanie..." else "Szukaj 🔍",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .padding(bottom = 16.dp)
        ) {
            // Messages List
            LazyColumn(
                reverseLayout = false,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages) { message ->
                    val isMe = message.senderId == "me"
                    MessageBubble(message = message, isMe = isMe)
                }
            }

            // Input Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.inputText,
                    onValueChange = { viewModel.handleIntent(ChatUiIntent.UpdateInputText(it)) },
                    placeholder = { Text("Secure message...", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surface
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { viewModel.handleIntent(ChatUiIntent.SendTextMessage(state.inputText)) },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("🔒 Send", color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: Message, isMe: Boolean) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isMe) 16.dp else 2.dp,
                bottomEnd = if (isMe) 2.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (message.isEncrypted) "🛡️ Encrypted" else "Plain",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isMe) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    if (isMe) {
                        Spacer(modifier = Modifier.width(4.dp))
                        val statusText = when (message.status) {
                            com.quantumchat.core.common.model.MessageStatus.SENT -> "✓"
                            com.quantumchat.core.common.model.MessageStatus.DELIVERED -> "✓✓"
                            com.quantumchat.core.common.model.MessageStatus.READ -> "✓✓"
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isMe) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }
}
