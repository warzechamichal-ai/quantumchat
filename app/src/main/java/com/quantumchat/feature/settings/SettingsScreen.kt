package com.quantumchat.feature.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quantumchat.core.common.QrCodeGenerator
import com.quantumchat.core.crypto.CryptoManager
import com.quantumchat.feature.contacts.CameraPreviewScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// MVI State
data class SettingsUiState(
    val localFingerprint: String = "",
    val verifiedContactsCount: Int = 0,
    val securityStatusMessage: String = "All keys generated.",
    val qrBitmap: Bitmap? = null,
    val isShowingQrCode: Boolean = false,
    val isScanningQr: Boolean = false
)

// MVI Intent
sealed interface SettingsUiIntent {
    object LoadSettings : SettingsUiIntent
    data class ScanMockQR(val qrContent: String) : SettingsUiIntent
    object ToggleQrDisplay : SettingsUiIntent
    object StartQrScanner : SettingsUiIntent
    object StopQrScanner : SettingsUiIntent
    data class ProcessScannedQR(val qrContent: String) : SettingsUiIntent
}

// ViewModel
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val cryptoManager: CryptoManager
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        handleIntent(SettingsUiIntent.LoadSettings)
    }

    fun handleIntent(intent: SettingsUiIntent) {
        viewModelScope.launch {
            when (intent) {
                is SettingsUiIntent.LoadSettings -> {
                    cryptoManager.generateLocalIdentityKeyPair()
                    val fingerprint = cryptoManager.getLocalIdentityFingerprint()
                    val qrContent = cryptoManager.generateIdentityQRContent()
                    val qrBitmap = QrCodeGenerator.generateQrCode(qrContent, 512, 512)
                    _state.value = _state.value.copy(
                        localFingerprint = fingerprint,
                        qrBitmap = qrBitmap
                    )
                }
                is SettingsUiIntent.ScanMockQR -> {
                    val verified = cryptoManager.verifyContactWithQR(intent.qrContent)
                    if (verified) {
                        _state.value = _state.value.copy(
                            verifiedContactsCount = _state.value.verifiedContactsCount + 1,
                            securityStatusMessage = "Successfully verified mock contact via QR!"
                        )
                    } else {
                        _state.value = _state.value.copy(
                            securityStatusMessage = "QR Verification failed: Invalid signature scheme."
                        )
                    }
                }
                is SettingsUiIntent.ToggleQrDisplay -> {
                    _state.value = _state.value.copy(
                        isShowingQrCode = !_state.value.isShowingQrCode
                    )
                }
                is SettingsUiIntent.StartQrScanner -> {
                    _state.value = _state.value.copy(
                        isScanningQr = true
                    )
                }
                is SettingsUiIntent.StopQrScanner -> {
                    _state.value = _state.value.copy(
                        isScanningQr = false
                    )
                }
                is SettingsUiIntent.ProcessScannedQR -> {
                    val verified = cryptoManager.verifyContactWithQR(intent.qrContent)
                    if (verified) {
                        _state.value = _state.value.copy(
                            verifiedContactsCount = _state.value.verifiedContactsCount + 1,
                            securityStatusMessage = "Successfully verified contact: ${intent.qrContent.take(25)}...",
                            isScanningQr = false
                        )
                    } else {
                        _state.value = _state.value.copy(
                            securityStatusMessage = "Invalid QR code signature.",
                            isScanningQr = false
                        )
                    }
                }
            }
        }
    }
}

// Screen Composable
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Quantum Security Settings", fontWeight = FontWeight.Bold) },
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Local Public Key Section
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "My Identity Key Fingerprint",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = state.localFingerprint,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "This fingerprint is composed of classical X25519 and post-quantum Kyber-768 public key hashes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Show QR Code Button
                            Button(
                                onClick = { viewModel.handleIntent(SettingsUiIntent.ToggleQrDisplay) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Show My QR Code 📱")
                            }
                        }
                    }
                }

                // QR Key Verification Section
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Out-Of-Band QR Verification",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Verified peers: ${state.verifiedContactsCount}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Camera scan button
                            Button(
                                onClick = { viewModel.handleIntent(SettingsUiIntent.StartQrScanner) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                            ) {
                                Text("Scan Contact QR Code 📷")
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.handleIntent(SettingsUiIntent.ScanMockQR("QC-IDENTITY:ALICE-SIGNATURE-OK")) },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                                ) {
                                    Text("Mock Valid QR")
                                }
                                Button(
                                    onClick = { viewModel.handleIntent(SettingsUiIntent.ScanMockQR("INVALID-SIGNATURE")) },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Mock Invalid QR")
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Status: ${state.securityStatusMessage}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }

                // Trust Engine Info
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Post-Quantum Cryptography Architecture",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Our security standard uses hybrid cryptography. For each chat session, we perform a classical ECDH key exchange layered with ML-KEM (Kyber-768) key encapsulation. Messages are signed using classical Ed25519 and post-quantum ML-DSA (Dilithium-3).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }

        // QR Code Dialog overlay
        val qrBitmap = state.qrBitmap
        if (state.isShowingQrCode && qrBitmap != null) {
            Dialog(onDismissRequest = { viewModel.handleIntent(SettingsUiIntent.ToggleQrDisplay) }) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "My QR Code Identity",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "My Identity QR Code",
                            modifier = Modifier.size(240.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Scan this on your peer's device to verify end-to-end security.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.handleIntent(SettingsUiIntent.ToggleQrDisplay) }) {
                            Text("Close")
                        }
                    }
                }
            }
        }

        // Camera QR Code Scanner Overlay
        if (state.isScanningQr) {
            CameraPreviewScanner(
                onQrCodeScanned = { qrContent ->
                    viewModel.handleIntent(SettingsUiIntent.ProcessScannedQR(qrContent))
                },
                onCloseScanner = {
                    viewModel.handleIntent(SettingsUiIntent.StopQrScanner)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
