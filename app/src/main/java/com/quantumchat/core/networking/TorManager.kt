package com.quantumchat.core.networking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

enum class TorStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * TorManager handles interaction with Orbot to check if Tor is running,
 * request starting Tor, register an Onion Service v3, and retrieve SOCKS proxy details.
 */
@Singleton
class TorManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _status = MutableStateFlow(TorStatus.DISCONNECTED)
    val status: StateFlow<TorStatus> = _status.asStateFlow()

    private val _onionAddress = MutableStateFlow<String?>(null)
    val onionAddress: StateFlow<String?> = _onionAddress.asStateFlow()

    private val _socksPort = MutableStateFlow(9050)
    val socksPort: StateFlow<Int> = _socksPort.asStateFlow()

    private val sharedPrefs = context.getSharedPreferences("tor_settings", Context.MODE_PRIVATE)

    private val orbotPackageName = "org.torproject.android"

    private var receiver: BroadcastReceiver? = null

    init {
        // Load stored Onion address if previously generated
        _onionAddress.value = sharedPrefs.getString("onion_address", null)
    }

    /**
     * Property to toggle or retrieve Tor automatic startup preference.
     */
    var isAutoStartEnabled: Boolean
        get() = sharedPrefs.getBoolean("auto_start_tor", false)
        set(value) = sharedPrefs.edit().putBoolean("auto_start_tor", value).apply()

    /**
     * Checks if Orbot app is installed on the system.
     */
    fun isOrbotInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(orbotPackageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Returns an intent to launch Google Play Store to install Orbot.
     */
    fun getOrbotInstallIntent(): Intent {
        val playStoreUri = android.net.Uri.parse("market://details?id=$orbotPackageName")
        return Intent(Intent.ACTION_VIEW, playStoreUri)
    }

    /**
     * Launches the Play Store to install Orbot.
     */
    fun installOrbot() {
        try {
            val intent = getOrbotInstallIntent().apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Timber.d("TorManager: Successfully launched Play Store intent for Orbot.")
        } catch (e: Exception) {
            Timber.e(e, "TorManager: Failed to launch Play Store for Orbot.")
        }
    }

    /**
     * Connects to Orbot by sending start intents and registering broadcast receivers
     * to monitor status and Onion service details.
     */
    @Synchronized
    fun startTor() {
        if (_status.value == TorStatus.CONNECTED || _status.value == TorStatus.CONNECTING) return

        _status.value = TorStatus.CONNECTING
        Timber.d("TorManager: Starting Tor/Orbot integration...")

        if (!isOrbotInstalled()) {
            _status.value = TorStatus.ERROR
            Timber.e("TorManager: Orbot is not installed on this device.")
            return
        }

        registerOrbotReceiver()

        // 1. Send action Intent to Orbot to start Tor
        try {
            val startIntent = Intent("org.torproject.android.intent.action.START").apply {
                setPackage(orbotPackageName)
            }
            context.sendBroadcast(startIntent)
            Timber.d("TorManager: Sent Orbot START broadcast.")
        } catch (e: Exception) {
            Timber.e(e, "TorManager: Failed to send START broadcast to Orbot.")
            _status.value = TorStatus.ERROR
            return
        }

        // 2. Request an Onion Service (v3 Hidden Service) for our app's listening port (9095)
        try {
            val requestHsIntent = Intent("org.torproject.android.intent.action.REQUEST_HS_PORT").apply {
                setPackage(orbotPackageName)
                putExtra("hs_port", 9095)
                putExtra("hs_name", "QuantumChat")
            }
            context.sendBroadcast(requestHsIntent)
            Timber.d("TorManager: Sent REQUEST_HS_PORT broadcast for port 9095.")
        } catch (e: Exception) {
            Timber.e(e, "TorManager: Failed to request Hidden Service from Orbot.")
        }
    }

    /**
     * Disconnects from Tor, unregisters broadcast receivers, and updates status.
     */
    @Synchronized
    fun stopTor() {
        Timber.d("TorManager: Stopping Tor integration.")
        unregisterOrbotReceiver()

        try {
            val stopIntent = Intent("org.torproject.android.intent.action.STOP").apply {
                setPackage(orbotPackageName)
            }
            context.sendBroadcast(stopIntent)
            Timber.d("TorManager: Sent Orbot STOP broadcast.")
        } catch (e: Exception) {
            Timber.e(e, "TorManager: Failed to send STOP broadcast to Orbot.")
        }

        _status.value = TorStatus.DISCONNECTED
    }

    private fun registerOrbotReceiver() {
        if (receiver != null) return

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    "org.torproject.android.intent.action.STATUS" -> {
                        val statusStr = intent.getStringExtra("org.torproject.android.intent.extra.STATUS")
                        Timber.d("TorManager: Orbot status broadcast received: $statusStr")
                        if (statusStr == "ON") {
                            _status.value = TorStatus.CONNECTED
                            val port = intent.getIntExtra("org.torproject.android.intent.extra.SOCKS_PROXY_PORT", 9050)
                            _socksPort.value = port
                            Timber.i("TorManager: Connected successfully. SOCKS Proxy running on port $port")
                        } else if (statusStr == "OFF") {
                            _status.value = TorStatus.DISCONNECTED
                        } else if (statusStr == "STARTING") {
                            _status.value = TorStatus.CONNECTING
                        }
                    }
                    "org.torproject.android.intent.action.HS_PORT_STATUS" -> {
                        val port = intent.getIntExtra("hs_port", -1)
                        if (port == 9095) {
                            val onion = intent.getStringExtra("hs_onion")
                            if (!onion.isNullOrEmpty()) {
                                Timber.i("TorManager: Received Onion address for port 9095: $onion")
                                _onionAddress.value = onion
                                sharedPrefs.edit().putString("onion_address", onion).apply()
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction("org.torproject.android.intent.action.STATUS")
            addAction("org.torproject.android.intent.action.HS_PORT_STATUS")
        }
        
        // Exported is required for registering receivers to external app broadcasts
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun unregisterOrbotReceiver() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {}
            receiver = null
        }
    }
}
