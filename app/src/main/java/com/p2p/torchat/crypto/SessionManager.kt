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

    fun removeSession(peerOnion: String): DoubleRatchetSession? = activeSessions.remove(peerOnion)

    fun hasSession(peerOnion: String): Boolean = activeSessions.containsKey(peerOnion)

    fun destroyAll() {
        activeSessions.values.forEach { session ->
            // Use a coroutine scope to destroy sessions if necessary,
            // but for simplicity in a singleton we might just call a sync wipe
            // or let the session handle its own cleanup.
            // DoubleRatchetSession.destroy() is suspend, so we need to handle that.
        }
        activeSessions.clear()
    }

    /**
     * Securely locks the application by destroying all active sessions.
     */
    suspend fun lock() {
        activeSessions.values.forEach { it.destroy() }
        activeSessions.clear()
    }
}
