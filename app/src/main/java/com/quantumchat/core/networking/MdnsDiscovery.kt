package com.quantumchat.core.networking

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.quantumchat.core.crypto.CryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MdnsDiscovery implements mDNS-based peer discovery using Android's Network Service Discovery (NSD) APIs.
 * It publishes the local device's fingerprint and listening port to the network, and simultaneously
 * listens for and resolves similar services published by other peers.
 * 
 * Provides an alternative, standard-compliant discovery mechanism that works on networks where UDP Broadcasts
 * are blocked.
 */
@Singleton
class MdnsDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager
) {
    private val serviceType = "_quantumchat._tcp."
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    var isMdnsSigningEnabled = false

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    
    /**
     * Flow emitting the list of discovered devices via mDNS.
     */
    val devices: Flow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val deviceMap = ConcurrentHashMap<String, DiscoveredDevice>()
    
    @Volatile
    private var isRunning = false
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /**
     * Starts publishing the local chat service and searching for other peers using mDNS/NSD.
     */
    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true
        Timber.d("mDNS: Starting MdnsDiscovery service")

        registerService()
        discoverServices()
    }

    /**
     * Stops service discovery and unregisters the local chat service.
     */
    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        Timber.d("mDNS: Stopping MdnsDiscovery service")

        unregisterService()
        stopDiscovery()
        deviceMap.clear()
        _devices.value = emptyList()
    }

    private fun registerService() {
        try {
            val fingerprint = cryptoManager.getLocalIdentityFingerprint()
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = fingerprint
                setServiceType(serviceType)
                port = 9090
            }

            // Cryptographically sign the local service info to protect against fingerprint hijacking/spoofing (if enabled)
            if (isMdnsSigningEnabled) {
                try {
                    val payload = "QC-MDNS:$fingerprint:9090"
                    val signature = cryptoManager.signMessage(payload.toByteArray(Charsets.UTF_8))
                    val signatureB64 = java.util.Base64.getEncoder().encodeToString(signature)
                    // Split signature into chunks of 150 bytes due to DNS TXT record value length limitations (255 bytes max)
                    val chunks = signatureB64.chunked(150)
                    serviceInfo.setAttribute("sig_count", chunks.size.toString())
                    chunks.forEachIndexed { index, chunk ->
                        serviceInfo.setAttribute("sig_$index", chunk)
                    }
                    Timber.d("mDNS: Service registration signing complete. Added signature TXT records (${chunks.size} chunks).")
                } catch (e: Exception) {
                    Timber.e(e, "mDNS: Failed to sign service registration")
                }
            } else {
                Timber.d("mDNS: Service registration signing is disabled. Skipping signature TXT records.")
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                    Timber.i("mDNS: Service registered successfully: ${nsdServiceInfo.serviceName}")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Timber.e("mDNS: Service registration failed: errorCode=$errorCode")
                }

                override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                    Timber.d("mDNS: Service unregistered: ${arg0.serviceName}")
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Timber.e("mDNS: Service unregistration failed: errorCode=$errorCode")
                }
            }

            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Timber.e(e, "mDNS: Failed to register service completely")
        }
    }

    private fun unregisterService() {
        val listener = registrationListener
        if (listener != null) {
            try {
                nsdManager.unregisterService(listener)
            } catch (e: Exception) {
                Timber.e(e, "mDNS: Failed to unregister service")
            }
            registrationListener = null
        }
    }

    private fun discoverServices() {
        try {
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Timber.e("mDNS: Start discovery failed: errorCode=$errorCode")
                    stopDiscovery()
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Timber.e("mDNS: Stop discovery failed: errorCode=$errorCode")
                }

                override fun onDiscoveryStarted(serviceType: String) {
                    Timber.d("mDNS: Service discovery started")
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Timber.d("mDNS: Service discovery stopped")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Timber.d("mDNS: Service found: ${serviceInfo.serviceName}")
                    if (serviceInfo.serviceType != serviceType) {
                        return
                    }
                    val ownFingerprint = cryptoManager.getLocalIdentityFingerprint()
                    if (serviceInfo.serviceName == ownFingerprint) {
                        return
                    }

                    try {
                        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                Timber.w("mDNS: Resolve failed: errorCode=$errorCode")
                            }

                            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                                Timber.i("mDNS: Service resolved: ${resolvedInfo.serviceName} at ${resolvedInfo.host.hostAddress}:${resolvedInfo.port}")
                                val fingerprint = resolvedInfo.serviceName
                                val ip = resolvedInfo.host.hostAddress ?: return
                                val port = resolvedInfo.port

                                // Retrieve and verify signature from service attributes to prevent spoofing
                                var isVerified = !isMdnsSigningEnabled
                                if (isMdnsSigningEnabled) {
                                    try {
                                        val attributes = resolvedInfo.attributes
                                        val sigCountBytes = attributes["sig_count"]
                                        if (sigCountBytes != null) {
                                            val sigCount = String(sigCountBytes, Charsets.UTF_8).toIntOrNull() ?: 0
                                            val sigBuilder = StringBuilder()
                                            var hasAllChunks = true
                                            for (i in 0 until sigCount) {
                                                val chunkBytes = attributes["sig_$i"]
                                                if (chunkBytes != null) {
                                                    sigBuilder.append(String(chunkBytes, Charsets.UTF_8))
                                                } else {
                                                    hasAllChunks = false
                                                    break
                                                }
                                            }
                                            if (hasAllChunks) {
                                                val signatureB64 = sigBuilder.toString()
                                                val signature = java.util.Base64.getDecoder().decode(signatureB64)
                                                val payload = "QC-MDNS:$fingerprint:$port"
                                                isVerified = cryptoManager.verifyMessageSignature(
                                                    payload.toByteArray(Charsets.UTF_8),
                                                    signature,
                                                    fingerprint
                                                )
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Timber.w(e, "mDNS: Failed to extract or verify signature for resolved service $fingerprint")
                                    }
                                }

                                if (!isVerified) {
                                    Timber.w("mDNS: Signature verification FAILED for resolved service $fingerprint at $ip. Packet dropped.")
                                    return
                                }

                                val device = DiscoveredDevice(
                                    fingerprint = fingerprint,
                                    ipAddress = ip,
                                    port = port,
                                    lastSeen = System.currentTimeMillis()
                                )
                                deviceMap[fingerprint] = device
                                _devices.value = deviceMap.values.toList()
                            }
                        })
                    } catch (e: Exception) {
                        Timber.e(e, "mDNS: Failed to resolve service")
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Timber.i("mDNS: Service lost: ${serviceInfo.serviceName}")
                    val fingerprint = serviceInfo.serviceName
                    deviceMap.remove(fingerprint)
                    _devices.value = deviceMap.values.toList()
                }
            }

            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Timber.e(e, "mDNS: Failed to start service discovery")
        }
    }

    private fun stopDiscovery() {
        val listener = discoveryListener
        if (listener != null) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Timber.e(e, "mDNS: Failed to stop service discovery")
            }
            discoveryListener = null
        }
    }
}
