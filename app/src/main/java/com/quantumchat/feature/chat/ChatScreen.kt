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
import com.quantumchat.core.networking.Transport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// MVI State
data class ChatUiState(
    val contact: Contact? = null,
    val messages: List<Message> = emptyList(),
    val connectionActive: Boolean = false,
    val inputText: String = ""
)

// MVI Intent
sealed interface ChatUiIntent {
    data class LoadChat(val contactId: String, val contactName: String) : ChatUiIntent
    data class SendTextMessage(val content: String) : ChatUiIntent
    data class UpdateInputText(val text: String) : ChatUiIntent
    data class ReceiveMessage(val message: Message) : ChatUiIntent
}

// ViewModel
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val transport: Transport
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        // Observe connection state from transport
        viewModelScope.launch {
            transport.isConnected.collect { isConnected ->
                _state.value = _state.value.copy(connectionActive = isConnected)
            }
        }

        // Observe incoming messages
        viewModelScope.launch {
            transport.observeIncomingMessages().collect { message ->
                handleIntent(ChatUiIntent.ReceiveMessage(message))
            }
        }

        // Auto-connect transport on entering screen
        viewModelScope.launch {
            transport.connect().collect { /* Connection loading/success states handled dynamically */ }
        }
    }

    fun handleIntent(intent: ChatUiIntent) {
        viewModelScope.launch {
            when (intent) {
                is ChatUiIntent.LoadChat -> {
                    // Establish secure PQ hybrid session for contact
                    cryptoManager.establishSecureSession("QC-PQ-MOCK-${intent.contactId}")
                    
                    val mockContact = Contact(intent.contactId, intent.contactName, "QC-PQ-MOCK-${intent.contactId}", true)
                    _state.value = _state.value.copy(
                        contact = mockContact,
                        messages = listOf(
                            Message("m1", mockContact.id, "me", "Hello! This session is secured with post-quantum lattice keys.", System.currentTimeMillis() - 600000)
                        )
                    )
                }
                is ChatUiIntent.SendTextMessage -> {
                    val text = intent.content.trim()
                    if (text.isEmpty()) return@launch

                    val targetContact = _state.value.contact ?: return@launch

                    // 1. Encrypt message content via CryptoManager
                    val plainBytes = text.toByteArray(Charsets.UTF_8)
                    val cipherBytes = cryptoManager.encryptMessage(plainBytes, targetContact.id)
                    val encryptedPayload = cipherBytes.toString(Charsets.UTF_8)

                    // 2. Build secure Message entity
                    val message = Message(
                        id = System.currentTimeMillis().toString(),
                        senderId = "me",
                        recipientId = targetContact.id,
                        content = encryptedPayload,
                        timestamp = System.currentTimeMillis(),
                        isEncrypted = true
                    )

                    // 3. Update local state immediately
                    // For display purposes, we keep the plain-text in memory but flag it as encrypted
                    val displayMessage = message.copy(content = text)
                    _state.value = _state.value.copy(
                        messages = _state.value.messages + displayMessage,
                        inputText = ""
                    )

                    // 4. Send encrypted payload over network transport
                    transport.sendMessage(message).collect { /* Handle send status */ }
                }
                is ChatUiIntent.UpdateInputText -> {
                    _state.value = _state.value.copy(inputText = intent.text)
                }
                is ChatUiIntent.ReceiveMessage -> {
                    // Decrypt incoming message
                    val decryptedBytes = cryptoManager.decryptMessage(intent.message.content.toByteArray(Charsets.UTF_8), intent.message.senderId)
                    val decryptedText = decryptedBytes.toString(Charsets.UTF_8)

                    val displayMessage = intent.message.copy(content = decryptedText)
                    _state.value = _state.value.copy(
                        messages = _state.value.messages + displayMessage
                    )
                }
            }
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
    val state by viewModel.state.collectAsState()

    // Initialize screen state with passed parameters
    androidx.compose.runtime.LaunchedEffect(contactId, contactName) {
        viewModel.handleIntent(ChatUiIntent.LoadChat(contactId, contactName))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.contact?.name ?: contactName, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(
                                        color = if (state.connectionActive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                        shape = RoundedCornerShape(3.dp)
                                    )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (state.connectionActive) "Secured Network" else "Reconnecting...",
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
                    .padding(16.dp),
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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (message.isEncrypted) "🛡️ Encrypted" else "Plain",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isMe) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}
