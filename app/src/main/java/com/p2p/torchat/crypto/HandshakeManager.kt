package com.p2p.torchat.crypto

import android.os.SystemClock
import com.p2p.torchat.model.PendingHandshake
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages pending handshakes with DoS protection and timeout logic.
 * Resolves Audit Point 6 (Handshake DoS).
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
    private val pendingHandshakes = ConcurrentHashMap<String, PendingHandshake>()

    /**
     * Adds a new pending handshake if limits are not exceeded.
     * Performs automatic cleanup of expired handshakes.
     */
    fun addPending(handshakeId: String, handshake: PendingHandshake): Boolean {
        cleanupExpired()

        if (pendingHandshakes.size >= maxGlobalPending) {
            return false
        }

        val perPeerCount = pendingHandshakes.values.count { it.peerOnion == handshake.peerOnion }
        if (perPeerCount >= maxPendingPerPeer) {
            return false
        }

        pendingHandshakes[handshakeId] = handshake
        return true
    }

    /**
     * Retrieves and removes a pending handshake by ID.
     * Returns null if not found or expired.
     */
    fun getAndRemove(handshakeId: String): PendingHandshake? {
        val handshake = pendingHandshakes.remove(handshakeId) ?: return null
        if (timeProvider() - handshake.createdAt > timeoutMs) {
            return null
        }
        return handshake
    }

    /**
     * Periodically called to free up resources.
     */
    fun cleanupExpired() {
        val now = timeProvider()
        // ConcurrentHashMap iterator is safe for removal during iteration
        val it = pendingHandshakes.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value.createdAt > timeoutMs) {
                it.remove()
            }
        }
    }

    fun getCurrentTime(): Long = timeProvider()
}
