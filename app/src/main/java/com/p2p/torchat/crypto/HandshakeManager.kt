package com.p2p.torchat.crypto

import android.os.SystemClock
import com.p2p.torchat.model.PendingHandshake

/**
 * Manages pending handshakes with DoS protection and timeout logic.
 * Resolves Audit Point 6 (Handshake DoS) and DOS-LIMIT-001 (Atomic enforcement).
 */
class HandshakeManager(
    private val maxPendingPerPeer: Int = 3,
    private val maxGlobalPending: Int = 50,
    private val timeoutMs: Long = 60000L,
    private val timeProvider: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
) {
    private val lock = Any()
    // Using mutableMapOf because access is protected by synchronized(lock)
    private val pendingHandshakes = mutableMapOf<String, PendingHandshake>()

    /**
     * Adds a new pending handshake if limits are not exceeded.
     * Performs automatic cleanup of expired handshakes.
     */
    fun addPending(handshakeId: String, handshake: PendingHandshake): Boolean = synchronized(lock) {
        cleanupExpiredInternal()

        // 1. Prevent overwrite/replay of in-progress handshakeId
        if (pendingHandshakes.containsKey(handshakeId)) {
            return false
        }

        // 2. Global limit enforcement
        if (pendingHandshakes.size >= maxGlobalPending) {
            return false
        }

        // 3. Per-peer limit enforcement
        val perPeerCount = pendingHandshakes.values.count { it.peerOnion == handshake.peerOnion }
        if (perPeerCount >= maxPendingPerPeer) {
            return false
        }

        pendingHandshakes[handshakeId] = handshake
        return true
    }

    /**
     * Atomically verifies a pending handshake and consumes it if the verifier returns true.
     * Resolves Audit P1 (Race condition in handshake consumption).
     */
    fun verifyAndConsume(handshakeId: String, verifier: (PendingHandshake) -> Boolean): Boolean = synchronized(lock) {
        val handshake = pendingHandshakes[handshakeId] ?: return false

        // 1. Check expiration
        if (timeProvider() - handshake.createdAt > timeoutMs) {
            pendingHandshakes.remove(handshakeId)
            return false
        }

        // 2. Perform verification
        if (verifier(handshake)) {
            pendingHandshakes.remove(handshakeId)
            return true
        }

        return false
    }

    /**
     * Retrieves and removes a pending handshake by ID (Legacy method, use with care).
     */
    fun getAndRemove(handshakeId: String): PendingHandshake? = synchronized(lock) {
        val handshake = pendingHandshakes.remove(handshakeId) ?: return null
        if (timeProvider() - handshake.createdAt > timeoutMs) {
            return null
        }
        return handshake
    }

    /**
     * Periodically called to free up resources.
     */
    fun cleanupExpired() = synchronized(lock) {
        cleanupExpiredInternal()
    }

    private fun cleanupExpiredInternal() {
        val now = timeProvider()
        val it = pendingHandshakes.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value.createdAt > timeoutMs) {
                it.remove()
            }
        }
    }

    fun getCurrentTime(): Long = timeProvider()

    // For testing purposes
    fun getPendingCount(): Int = synchronized(lock) { pendingHandshakes.size }
}
