package com.quantumchat.feature.settings

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quantumchat.core.common.QrCodeGenerator
import com.quantumchat.core.common.AppVersion
import com.quantumchat.core.crypto.CryptoManager
import com.quantumchat.core.networking.TransportManager
import com.quantumchat.core.networking.TorManager
import com.quantumchat.core.networking.TorStatus
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
    val isScanningQr: Boolean = false,
    val isWifiDirectEnabled: Boolean = true,
    val isUdpDiscoveryEnabled: Boolean = true,
    val isMdnsDiscoveryEnabled: Boolean = true,
    val shouldRequestPermissions: Boolean = false,
    val torStatus: TorStatus = TorStatus.DISCONNECTED,
    val onionAddress: String? = null,
    val isOrbotInstalled: Boolean = false,
    val isAutoStartTorEnabled: Boolean = false
)

// MVI Intent
sealed interface SettingsUiIntent {
    object LoadSettings : SettingsUiIntent
    data class ScanMockQR(val qrContent: String) : SettingsUiIntent
    object ToggleQrDisplay : SettingsUiIntent
    object StartQrScanner : SettingsUiIntent
    object StopQrScanner : SettingsUiIntent
    data class ProcessScannedQR(val qrContent: String) : SettingsUiIntent
    object ToggleWifiDirect : SettingsUiIntent
    object ToggleUdpDiscovery : SettingsUiIntent
    object ToggleMdnsDiscovery : SettingsUiIntent
    data class SetWifiDirectEnabled(val enabled: Boolean) : SettingsUiIntent
    object ClearPermissionRequestTrigger : SettingsUiIntent
    object ToggleTor : SettingsUiIntent
    object InstallOrbot : SettingsUiIntent
    object ToggleAutoStartTor : SettingsUiIntent
}

// ViewModel
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val transportManager: TransportManager,
    private val torManager: TorManager
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        handleIntent(SettingsUiIntent.LoadSettings)

        // Observe TorManager states and dynamically update settings state
        viewModelScope.launch {
            torManager.status.collect { status ->
                _state.value = _state.value.copy(torStatus = status)
            }
        }

        viewModelScope.launch {
            torManager.onionAddress.collect { address ->
                _state.value = _state.value.copy(onionAddress = address)
            }
        }
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
                        qrBitmap = qrBitmap,
                        isWifiDirectEnabled = transportManager.isWifiDirectEnabled,
                        isUdpDiscoveryEnabled = transportManager.isUdpDiscoveryEnabled,
                        isMdnsDiscoveryEnabled = transportManager.isMdnsDiscoveryEnabled,
                        isOrbotInstalled = torManager.isOrbotInstalled(),
                        isAutoStartTorEnabled = torManager.isAutoStartEnabled
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
                is SettingsUiIntent.ToggleWifiDirect -> {
                    val nextVal = !transportManager.isWifiDirectEnabled
                    if (nextVal) {
                        if (transportManager.wiFiDirectTransport.hasPermissions()) {
                            transportManager.isWifiDirectEnabled = true
                            _state.value = _state.value.copy(
                                isWifiDirectEnabled = true,
                                securityStatusMessage = "WiFi Direct enabled."
                            )
                        } else {
                            _state.value = _state.value.copy(
                                shouldRequestPermissions = true
                            )
                        }
                    } else {
                        transportManager.isWifiDirectEnabled = false
                        _state.value = _state.value.copy(
                            isWifiDirectEnabled = false,
                            securityStatusMessage = "WiFi Direct disabled."
                        )
                    }
                }
                is SettingsUiIntent.ToggleUdpDiscovery -> {
                    val nextVal = !transportManager.isUdpDiscoveryEnabled
                    transportManager.isUdpDiscoveryEnabled = nextVal
                    _state.value = _state.value.copy(
                        isUdpDiscoveryEnabled = nextVal
                    )
                }
                is SettingsUiIntent.ToggleMdnsDiscovery -> {
                    val nextVal = !transportManager.isMdnsDiscoveryEnabled
                    transportManager.isMdnsDiscoveryEnabled = nextVal
                    _state.value = _state.value.copy(
                        isMdnsDiscoveryEnabled = nextVal
                    )
                }
                is SettingsUiIntent.SetWifiDirectEnabled -> {
                    transportManager.isWifiDirectEnabled = intent.enabled
                    _state.value = _state.value.copy(
                        isWifiDirectEnabled = intent.enabled,
                        securityStatusMessage = if (intent.enabled) "WiFi Direct enabled." else "WiFi Direct disabled (missing permissions)."
                    )
                }
                is SettingsUiIntent.ClearPermissionRequestTrigger -> {
                    _state.value = _state.value.copy(
                        shouldRequestPermissions = false
                    )
                }
                is SettingsUiIntent.ToggleTor -> {
                    if (torManager.status.value == TorStatus.CONNECTED) {
                        torManager.stopTor()
                    } else {
                        torManager.startTor()
                    }
                }
                is SettingsUiIntent.InstallOrbot -> {
                    torManager.installOrbot()
                }
                is SettingsUiIntent.ToggleAutoStartTor -> {
                    val nextVal = !_state.value.isAutoStartTorEnabled
                    torManager.isAutoStartEnabled = nextVal
                    _state.value = _state.value.copy(isAutoStartTorEnabled = nextVal)
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
    val clipboardManager = LocalClipboardManager.current

    // Setup permission launcher for WiFi Direct
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        val nearbyGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions[android.Manifest.permission.NEARBY_WIFI_DEVICES] ?: false
        } else {
            true
        }
        val isGranted = (fineGranted || coarseGranted) && nearbyGranted
        viewModel.handleIntent(SettingsUiIntent.SetWifiDirectEnabled(isGranted))
    }

    LaunchedEffect(state.shouldRequestPermissions) {
        if (state.shouldRequestPermissions) {
            val list = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.NEARBY_WIFI_DEVICES
                )
            } else {
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            }
            requestPermissionLauncher.launch(list)
            viewModel.handleIntent(SettingsUiIntent.ClearPermissionRequestTrigger)
        }
    }

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

                // Tor Onion Services Card
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Tor / Onion Services (v3)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Tor Onion Services establish fully anonymous end-to-end connections. All traffic is layered with multi-node encryptions, shielding your IP address and resolving communication without central server logs.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Tor Daemon Status:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                val badgeColor = when (state.torStatus) {
                                    TorStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                                    TorStatus.CONNECTING -> MaterialTheme.colorScheme.secondary
                                    TorStatus.DISCONNECTED -> MaterialTheme.colorScheme.outline
                                    TorStatus.ERROR -> MaterialTheme.colorScheme.error
                                }
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = badgeColor),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = state.torStatus.name,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            Text("My .onion Address:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            val addressDisplay = state.onionAddress ?: "Not generated. Launch Tor/Orbot to register service."
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = addressDisplay,
                                    modifier = Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            val onionAddress = state.onionAddress
                            if (!onionAddress.isNullOrEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { clipboardManager.setText(AnnotatedString(onionAddress)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                ) {
                                    Text("Copy Address 📋")
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Uruchamiaj Tor automatycznie przy starcie",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Switch(
                                    checked = state.isAutoStartTorEnabled,
                                    onCheckedChange = { viewModel.handleIntent(SettingsUiIntent.ToggleAutoStartTor) }
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            if (!state.isOrbotInstalled) {
                                Button(
                                    onClick = { viewModel.handleIntent(SettingsUiIntent.InstallOrbot) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Install Orbot from Play Store 🌐")
                                }
                            } else {
                                val buttonText = if (state.torStatus == TorStatus.CONNECTED) "Stop Tor 🛑" else "Start Tor 🧅"
                                Button(
                                    onClick = { viewModel.handleIntent(SettingsUiIntent.ToggleTor) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(buttonText)
                                }
                            }
                        }
                    }
                }

                // WiFi Direct and Local Discovery Section
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "WiFi Direct & Local Discovery Settings",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "WiFi Direct requires Location Permissions (ACCESS_FINE_LOCATION) and Nearby Devices permission on Android. This is because scanning for peer-to-peer WiFi networks can reveal surrounding network beacons, which Android classifies as location-revealing. No location data is ever tracked or transmitted by QuantumChat.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Enable WiFi Direct Transport", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Text("Enables peer-to-peer WiFi Direct connections.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                                Switch(
                                    checked = state.isWifiDirectEnabled,
                                    onCheckedChange = { viewModel.handleIntent(SettingsUiIntent.ToggleWifiDirect) }
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Enable UDP Broadcast Discovery", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Text("Broadcasting signed identities over port 8888.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                                Switch(
                                    checked = state.isUdpDiscoveryEnabled,
                                    onCheckedChange = { viewModel.handleIntent(SettingsUiIntent.ToggleUdpDiscovery) }
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Enable mDNS (NSD) Discovery", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Text("Publishing standard DNS-SD records concurrently.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                                Switch(
                                    checked = state.isMdnsDiscoveryEnabled,
                                    onCheckedChange = { viewModel.handleIntent(SettingsUiIntent.ToggleMdnsDiscovery) }
                                )
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

                // App Version and Changelog Section
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Informacje o aplikacji",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Wersja: ${AppVersion.versionName} (build ${AppVersion.versionCode})",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Historia zmian:",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            AppVersion.changelog.forEach { logItem ->
                                Text(
                                    text = "• $logItem",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
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
