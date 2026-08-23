package com.p2p.torchat.crypto

import android.content.Context
import android.util.Base64
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
import java.io.File
import java.security.SecureRandom
import kotlin.system.exitProcess

/**
 * Enhanced Security States (v6)
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
 * Resolves Audit Point 3, 4, 6 & 7 (Atomic lifecycle, Panic Wipe).
 */
object PrivacyController {
    private val mutex = Mutex()
    private val _securityState = MutableStateFlow(SecurityState.LOCKED)
    val securityState: StateFlow<SecurityState> = _securityState

    private val _securityEvents = MutableSharedFlow<SecurityEvent>(extraBufferCapacity = 1)
    val securityEvents: SharedFlow<SecurityEvent> = _securityEvents

    private var torManager: TorManager? = null
    private var localServer: LocalServer? = null
    private var context: Context? = null
    private var isInitialized = false

    private var identityContext: IdentityContext? = null

    fun initialize(ctx: Context, tor: TorManager, server: LocalServer) {
        check(!isInitialized) { "PrivacyController already initialized" }
        this.context = ctx.applicationContext
        this.torManager = tor
        this.localServer = server
        isInitialized = true
    }

    /**
     * Transitions the app to UNLOCKED state atomically.
     * Handles full initialization: Auth -> Entropy -> Keys -> Services.
     */
    suspend fun unlock(password: CharArray) = unlockInternal(password, null)

    /**
     * Handles initial setup or recovery: Saves password and entropy, then unlocks.
     */
    suspend fun setup(password: CharArray, entropy: ByteArray) = unlockInternal(password, entropy)

    private suspend fun unlockInternal(password: CharArray, newEntropy: ByteArray?) = mutex.withLock {
        if (_securityState.value != SecurityState.LOCKED) return@withLock

        _securityState.value = SecurityState.UNLOCKING
        Log.i(Constants.TAG, "PrivacyController: Unlocking...")

        try {
            val ctx = context ?: throw IllegalStateException("Not initialized")
            val prefs = ctx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

            val entropy: ByteArray
            val mnemonicWords: List<String>
            val salt = getOrCreateSalt(ctx)

            if (newEntropy != null) {
                // Initial Setup / Recovery flow
                entropy = newEntropy
                mnemonicWords = MnemonicManager.entropyToMnemonic(entropy)
                val h = E2EManager.hashPassword(password)
                val encMnemonic = E2EManager.encrypt(mnemonicWords.joinToString(" "), E2EManager.deriveMnemonicKey(password, salt))

                prefs.edit()
                    .putString(Constants.KEY_PASS_HASH, h)
                    .putString(Constants.KEY_SAVED_SEED_ENC, encMnemonic)
                    .apply()

                Log.i(Constants.TAG, "Initial setup complete")
            } else {
                // Standard login flow
                val savedHash = prefs.getString(Constants.KEY_PASS_HASH, null) ?: throw SecurityException("No password set")
                if (!E2EManager.verifyPassword(password, savedHash)) {
                    _securityState.value = SecurityState.LOCKED
                    throw SecurityException("Invalid password")
                }

                val encSeed = prefs.getString(Constants.KEY_SAVED_SEED_ENC, null) ?: throw SecurityException("Seed missing")
                val mnemonicKey = E2EManager.deriveMnemonicKey(password, salt)
                mnemonicWords = E2EManager.decrypt(encSeed, mnemonicKey).split(" ")
                entropy = MnemonicManager.mnemonicToEntropy(mnemonicWords) ?: throw SecurityException("Invalid mnemonic")
            }

            // 3. Derive Identity
            val identitySeed = HKDF.deriveKey(entropy, null, "TorChat/V2/IdentitySeed".toByteArray(), 32)
            val identityKeyPair = E2EManager.ed25519KeyPairFromSeed(identitySeed)

            // Link Identity in Prefs (v6: centralizing this)
            val seedEnc = E2EManager.encryptWithHardwareKey(Base64.getEncoder().encodeToString(identitySeed)).getOrNull() ?: ""
            prefs.edit()
                .putString(Constants.KEY_IDENTITY_SEED_ENC, seedEnc)
                .putString(Constants.KEY_PUBLIC_KEY, E2EManager.publicKeyToString(identityKeyPair.public))
                .apply()

            identityContext = IdentityContext(entropy, identityKeyPair, mnemonicWords)

            // 4. Start Services
            localServer?.startServer()
            val savedOnion = prefs.getString(Constants.KEY_ONION, null)
            if (savedOnion != null) {
                torManager?.setTorRunning(savedOnion)
            }

            _securityState.value = SecurityState.UNLOCKED
            Log.i(Constants.TAG, "System UNLOCKED")
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Unlock failed: ${e.message}")
            identityContext?.wipe()
            identityContext = null
            _securityState.value = SecurityState.LOCKED
            throw e
        }
    }

    /**
     * Transitions the app to LOCKED state atomically, wiping RAM-sensitive data and stopping services.
     */
    suspend fun lock() = mutex.withLock {
        if (_securityState.value == SecurityState.LOCKED || _securityState.value == SecurityState.LOCKING) return@withLock

        _securityState.value = SecurityState.LOCKING
        Log.i(Constants.TAG, "PrivacyController: Locking system...")

        // 1. Wipe RAM
        identityContext?.wipe()
        identityContext = null
        SessionManager.lock()

        // 2. Stop networking services
        torManager?.stopTor()
        localServer?.stopServer()

        // 3. Signal UI to clear references
        _securityEvents.emit(SecurityEvent.WipeRequested)

        _securityState.value = SecurityState.LOCKED
        Log.i(Constants.TAG, "System LOCKED")
    }

    /**
     * Performs a full Panic Wipe: destroys RAM and deletes all persistent data.
     * Resolves Audit Point 6 (Panic Wipe).
     */
    suspend fun panicWipe() = mutex.withLock {
        _securityState.value = SecurityState.LOCKING
        Log.w(Constants.TAG, "!!! PANIC WIPE INITIATED !!!")

        // 1. RAM Wipe
        identityContext?.wipe()
        identityContext = null
        SessionManager.lock()

        // 2. Stop Services
        torManager?.stopTor()
        localServer?.stopServer()

        // 3. Persistent Wipe
        val ctx = context
        if (ctx != null) {
            E2EManager.deleteMasterKey()
            val prefsList = listOf(Constants.PREFS_NAME, "secure_prefs_salt", "pairing_prefs")
            prefsList.forEach { name ->
                ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
            val torDir = File(ctx.filesDir, "tor")
            if (torDir.exists()) torDir.deleteRecursively()
        }

        _securityEvents.emit(SecurityEvent.WipeRequested)
        _securityState.value = SecurityState.LOCKED

        // 4. Terminate Process
        Log.w(Constants.TAG, "Panic Wipe complete. Terminating.")
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(0)
    }

    private fun getOrCreateSalt(ctx: Context): ByteArray {
        val p = ctx.getSharedPreferences("secure_prefs_salt", Context.MODE_PRIVATE)
        val sEnc = p.getString("install_salt_enc", null) ?: return generateAndSaveSalt(p)
        return try {
            Base64.getDecoder().decode(E2EManager.decryptWithHardwareKey(sEnc).getOrThrow())
        } catch (e: Exception) {
            throw IllegalStateException("Secure storage inaccessible", e)
        }
    }

    private fun generateAndSaveSalt(p: android.content.SharedPreferences): ByteArray {
        val s = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val enc = E2EManager.encryptWithHardwareKey(Base64.getEncoder().encodeToString(s)).getOrNull() ?: ""
        p.edit().putString("install_salt_enc", enc).apply()
        return s
    }

    fun getIdentityContext(): IdentityContext? = identityContext
    fun isLocked(): Boolean = _securityState.value == SecurityState.LOCKED
    fun isUnlocked(): Boolean = _securityState.value == SecurityState.UNLOCKED
}
