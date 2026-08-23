package com.p2p.torchat.crypto

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe Session Manager for TorP2PChat.
 * Manages active Double Ratchet sessions.
 */
object SessionManager {
    private val activeSessions = ConcurrentHashMap<String, DoubleRatchetSession>()

    fun getSession(peerOnion: String): DoubleRatchetSession? = activeSessions[peerOnion]

    fun putSession(peerOnion: String, session: DoubleRatchetSession) {
        activeSessions[peerOnion] = session
    }

    suspend fun removeAndDestroySession(peerOnion: String) {
        activeSessions.remove(peerOnion)?.destroy()
    }

    fun hasSession(peerOnion: String): Boolean = activeSessions.containsKey(peerOnion)

    /**
     * Securely destroys all active sessions and wipes cryptographic material.
     */
    suspend fun destroyAll() {
        activeSessions.values.forEach { it.destroy() }
        activeSessions.clear()
    }

    /**
     * Alias for destroyAll() to maintain backward compatibility if needed,
     * but ensuring it follows the new suspend protocol.
     */
    suspend fun lock() = destroyAll()
}
