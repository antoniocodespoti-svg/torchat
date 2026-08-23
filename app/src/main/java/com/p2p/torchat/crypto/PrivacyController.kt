package com.p2p.torchat.crypto

import android.util.Log
import com.p2p.torchat.service.TorManager
import com.p2p.torchat.service.LocalServer
import com.p2p.torchat.util.Constants
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Enhanced Security States (v5)
 */
enum class SecurityState {
    LOCKED,
    UNLOCKING,
    UNLOCKED,
    LOCKING
}

sealed class SecurityEvent {
    object WipeRequested : SecurityEvent()
}

/**
 * Central Privacy Controller - Atomic Security State Machine.
 * Resolves Audit Point 3 (Race conditions in security transitions).
 */
object PrivacyController {
    private val mutex = Mutex()
    private val _securityState = MutableStateFlow(SecurityState.LOCKED)
    val securityState: StateFlow<SecurityState> = _securityState

    private val _securityEvents = MutableSharedFlow<SecurityEvent>(extraBufferCapacity = 1)
    val securityEvents: SharedFlow<SecurityEvent> = _securityEvents

    private var torManager: TorManager? = null
    private var localServer: LocalServer? = null
    private var isInitialized = false

    fun initialize(tor: TorManager, server: LocalServer) {
        if (isInitialized) return
        this.torManager = tor
        this.localServer = server
        isInitialized = true
    }

    /**
     * Transitions the app to UNLOCKED state atomically.
     */
    suspend fun unlock() = mutex.withLock {
        if (_securityState.value != SecurityState.LOCKED) return@withLock

        _securityState.value = SecurityState.UNLOCKING
        // Environment integrity checks could go here.
        _securityState.value = SecurityState.UNLOCKED
        Log.i(Constants.TAG, "System UNLOCKED")
    }

    /**
     * Transitions the app to LOCKED state atomically, wiping RAM-sensitive data and stopping services.
     * Resolves Audit P1: Atomic lockdown of all crypto references.
     */
    suspend fun lock() = mutex.withLock {
        if (_securityState.value == SecurityState.LOCKED || _securityState.value == SecurityState.LOCKING) return@withLock

        _securityState.value = SecurityState.LOCKING
        Log.i(Constants.TAG, "PrivacyController: Locking system...")

        // 1. Wipe sessions from RAM (Suspend call to ensure all keys are zeroed)
        SessionManager.lock()

        // 2. Stop networking services
        torManager?.stopTor()
        localServer?.stopServer()

        // 3. Signal UI/Activity to wipe its local state (messages, identity references)
        _securityEvents.emit(SecurityEvent.WipeRequested)

        _securityState.value = SecurityState.LOCKED
        Log.i(Constants.TAG, "System LOCKED")
    }

    /**
     * Performs a full Panic Wipe: destroys RAM and signals persistent data deletion.
     */
    suspend fun panicWipe() {
        lock()
        // The caller (MainActivity) is responsible for deleting Keystore and SharedPrefs
        // based on the WipeRequested event or a specific Panic event.
    }

    fun isLocked(): Boolean = _securityState.value == SecurityState.LOCKED
    fun isUnlocked(): Boolean = _securityState.value == SecurityState.UNLOCKED
}
