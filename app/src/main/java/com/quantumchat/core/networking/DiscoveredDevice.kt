package com.quantumchat.core.networking

/**
 * Data class representing a discovered device on the local network.
 * @property fingerprint Unique cryptographic identity fingerprint of the peer device.
 * @property ipAddress IP address where the peer is reachable.
 * @property port Port number where the peer's ServerSocket is listening.
 * @property lastSeen Timestamp of the last received discovery broadcast packet.
 */
data class DiscoveredDevice(
    val fingerprint: String,
    val ipAddress: String,
    val port: Int,
    val lastSeen: Long = System.currentTimeMillis()
)
