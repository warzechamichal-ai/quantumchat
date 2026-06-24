package com.quantumchat.core.networking

import java.nio.ByteBuffer

/**
 * MeshPacket wraps encrypted payloads with source/destination fingerprints and TTL (Time-To-Live)
 * to facilitate multi-hop routing across peer devices.
 */
data class MeshPacket(
    val sourceFingerprint: String,
    val destinationFingerprint: String,
    val ttl: Int,
    val payload: ByteArray
) {
    /**
     * Serializes the MeshPacket into a raw byte array.
     */
    fun serialize(): ByteArray {
        val srcBytes = sourceFingerprint.toByteArray(Charsets.UTF_8)
        val destBytes = destinationFingerprint.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(1 + srcBytes.size + 1 + destBytes.size + 1 + 4 + payload.size)
        buffer.put(srcBytes.size.toByte())
        buffer.put(srcBytes)
        buffer.put(destBytes.size.toByte())
        buffer.put(destBytes)
        buffer.put(ttl.toByte())
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MeshPacket

        if (sourceFingerprint != other.sourceFingerprint) return false
        if (destinationFingerprint != other.destinationFingerprint) return false
        if (ttl != other.ttl) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sourceFingerprint.hashCode()
        result = 31 * result + destinationFingerprint.hashCode()
        result = 31 * result + ttl
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        /**
         * Deserializes a raw byte array back into a MeshPacket.
         */
        fun deserialize(bytes: ByteArray): MeshPacket {
            val buffer = ByteBuffer.wrap(bytes)
            val srcLen = buffer.get().toInt() and 0xFF
            val srcBytes = ByteArray(srcLen)
            buffer.get(srcBytes)
            val src = String(srcBytes, Charsets.UTF_8)

            val destLen = buffer.get().toInt() and 0xFF
            val destBytes = ByteArray(destLen)
            buffer.get(destBytes)
            val dest = String(destBytes, Charsets.UTF_8)

            val ttl = buffer.get().toInt() and 0xFF
            val payloadLen = buffer.getInt()
            val payload = ByteArray(payloadLen)
            buffer.get(payload)

            return MeshPacket(src, dest, ttl, payload)
        }
    }
}
