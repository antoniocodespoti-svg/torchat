package com.p2p.torchat

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.p2p.torchat.crypto.*
import com.p2p.torchat.model.*
import com.p2p.torchat.service.*
import com.p2p.torchat.ui.screens.*
import com.p2p.torchat.ui.theme.TorP2PChatTheme
import com.p2p.torchat.util.Constants
import com.p2p.torchat.pairing.PairingTokenManager
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.*
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = Constants.TAG
    }

    private lateinit var torManager: TorManager
    private lateinit var p2pMessenger: P2PMessenger
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var backupManager: BackupManager
    private lateinit var mediaManager: MediaManager
    private val totpManager = TotpManager()
    private val timeFetcher = NetworkTimeFetcher()

    private var myIdentityKeyPair: KeyPair? = null
    private val activeSessions = mutableStateMapOf<String, DoubleRatchetSession>()
    private val handshakeLoading = mutableStateMapOf<String, Boolean>()

    private val handshakeManager = HandshakeManager()

    private val peersList = mutableStateListOf<Peer>()
    private val messagesMap = mutableStateMapOf<String, MutableList<Message>>()
    private val unreadCounts = mutableStateMapOf<String, Int>()
    private val networkSequence = AtomicInteger(0)

    private var currentScreenState by mutableStateOf<Screen>(Screen.Auth)
    private var isAuthenticated by mutableStateOf(false)
    private var isTermsAccepted by mutableStateOf(false)
    private var savedPasswordHash by mutableStateOf<String?>(null)
    private var failedAttempts by mutableIntStateOf(0)
    private var currentSeed by mutableStateOf<List<String>>(emptyList())

    private var myAlias by mutableStateOf("Amico")
    private var isDarkTheme by mutableStateOf(true)
    private var isAutoBackupEnabled by mutableStateOf(false)
    private var isAvailable by mutableStateOf(false)
    private var expiryDate by mutableLongStateOf(0L)

    private var peerToConfirmWithKey by mutableStateOf<Triple<String, String, String>?>(null)
    private var activeChatPeer: Peer? = null
    private var activeChatMessages: MutableList<Message>? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> u?.let { handleMediaPick(it, true) } }
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { u -> u?.let { handleMediaPick(it, false) } }
    private val exportBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u -> u?.let { handleExport(it) } }
    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { handleImport(it) } }
    private val orbotLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) res.data?.getStringExtra("onion_address")?.let { torManager.setTorRunning(it) }
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        initializeSystem()
        loadPreferences()

        val localServer = LocalServer(port = Constants.LOCAL_SERVER_PORT, onPacketReceived = { handleIncomingPacket(it) })
        localServer.startServer()

        if (torManager.isOrbotInstalled()) {
            val s = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).getString(Constants.KEY_ONION, null)
            if (s != null) torManager.setTorRunning(s) else orbotLauncher.launch(torManager.getOrbotRequestIntent())
        }

        setContent {
            TorP2PChatTheme(darkTheme = isDarkTheme) {
                AppMainContent()
            }
        }

        checkAndRequestPermissions()
    }

    @Composable
    private fun AppMainContent() {
        val torState by torManager.torState.collectAsState()
        val myOnion = (torState as? TorState.Running)?.onionAddress ?: ""

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val screen = currentScreenState) {
                is Screen.Auth -> AuthScreen(if (savedPasswordHash == null) AuthMode.CREATE else AuthMode.LOGIN, Constants.MAX_AUTH_ATTEMPTS - failedAttempts, { handleAuthResult(it) })
                is Screen.Subscription -> SubscriptionScreen(myOnion) { handleSubscription(it, myOnion) }
                is Screen.Home -> HomeScreen(torState, myOnion, myAlias, myIdentityKeyPair?.let { E2EManager.publicKeyToString(it.public) } ?: "", isDarkTheme, isAvailable, expiryDate, peersList, unreadCounts, peerToConfirmWithKey, { isDarkTheme = !isDarkTheme; saveThemePreference(isDarkTheme) }, { isAvailable = !isAvailable; saveAvailabilityPreference(isAvailable); broadcastMyStatus(isAvailable) }, { myAlias = it; saveMyAlias(it) }, { a, o, k -> handleAddPeer(a, o, k) }, { handleSelectPeer(it) }, { currentScreenState = Screen.QRCode }, { currentScreenState = Screen.QRScanner }, { currentScreenState = Screen.Settings }, { torManager.setTorRunning(it) }, { peersList.remove(it); savePeers() }, { peerToConfirmWithKey = null })
                is Screen.Chat -> {
                    val ms = messagesMap.getOrPut(screen.peer.onionAddress) { mutableStateListOf() }
                    LaunchedEffect(screen.peer.onionAddress) { if (!activeSessions.containsKey(screen.peer.onionAddress)) initiateHandshake(screen.peer) }
                    ChatScreen(screen.peer, ms, handshakeLoading[screen.peer.onionAddress] ?: false, { sendMessage(screen.peer, it, ms) }, { activeChatPeer = screen.peer; activeChatMessages = ms; pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, { activeChatPeer = screen.peer; activeChatMessages = ms; pickFileLauncher.launch("*/*") }, { f, b -> saveAttachmentToExternalStorage(f, b) }, { handleDeleteSession(screen.peer, ms) }, { currentScreenState = Screen.Verification(screen.peer) }, { currentScreenState = Screen.Home })
                }
                is Screen.Settings -> SettingsScreen({ handleExportInternal() }, { importBackupLauncher.launch(arrayOf("application/json")) }, isAutoBackupEnabled, { isAutoBackupEnabled = it; saveAutoBackupPreference(it) }, { currentScreenState = Screen.ChangePassword }, { currentScreenState = Screen.Subscription }, expiryDate, { currentScreenState = Screen.Info }, { currentScreenState = Screen.TermsOfUse }, { currentScreenState = Screen.Home })
                is Screen.QRCode -> QRCodeScreen(myOnion, myAlias, myIdentityKeyPair?.let { E2EManager.publicKeyToString(it.public) } ?: "") { currentScreenState = Screen.Home }
                is Screen.Verification -> VerificationScreen(screen.peer, { handleVerifyPeer(screen.peer) }, { currentScreenState = Screen.Chat(screen.peer) })
                is Screen.QRScanner -> ClientQRScannerScreen({ handleQRScan(it) }, { currentScreenState = Screen.Home })
                is Screen.TermsOfUse -> TermsOfUseScreen(isTermsAccepted, { handleTermsAccept() }, { currentScreenState = Screen.Settings })
                is Screen.Info -> InfoScreen { currentScreenState = Screen.Settings }
                is Screen.ChangePassword -> AuthScreen(AuthMode.CHANGE, onAuthSuccess = { handleChangePassword(it) }) { currentScreenState = Screen.Settings }
                is Screen.SeedBackup -> {
                    if (currentSeed.isEmpty()) {
                        val p = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
                        val saved = p.getString(Constants.KEY_SAVED_SEED, null)
                        currentSeed = if (saved != null) saved.split(" ") else MnemonicManager.generateMnemonic()
                    }
                    SeedScreen(SeedMode.DISPLAY, currentSeed, { handleExportInternal() }, { currentScreenState = Screen.Home })
                }
                is Screen.SeedRestore -> SeedScreen(SeedMode.INPUT, emptyList(), { handleSeedRestore(it) }, { currentScreenState = Screen.Settings })
            }
        }
    }

    private fun handleAuthResult(password: String): Boolean {
        if (E2EManager.verifyPassword(password, savedPasswordHash ?: "")) {
            isAuthenticated = true; failedAttempts = 0; saveFailedAttempts(0); currentScreenState = Screen.Home; return true
        }
        failedAttempts++; saveFailedAttempts(failedAttempts); if (failedAttempts >= Constants.MAX_AUTH_ATTEMPTS) performWipe(); return false
    }

    private fun handleSubscription(code: String, onion: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val netTime = timeFetcher.fetchTimeViaTor() ?: System.currentTimeMillis()
            val matchedDays = totpManager.findMatchingClientDuration(code, onion, netTime)
            if (matchedDays != null) {
                expiryDate = maxOf(netTime, expiryDate) + (matchedDays.toLong() * 24 * 60 * 60 * 1000)
                saveExpiryDate(expiryDate)
                withContext(Dispatchers.Main) {
                    currentScreenState = Screen.Home
                    Toast.makeText(this@MainActivity, "OK", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleAddPeer(alias: String, onion: String, pubKey: String) {
        val clean = sanitizeOnionAddress(onion)
        if (peersList.any { it.onionAddress == clean }) Toast.makeText(this, "ESISTE", Toast.LENGTH_SHORT).show()
        else { peersList.add(Peer(clean, alias, identityPublicKey = pubKey)); savePeers() }
    }

    private fun handleSelectPeer(peer: Peer) {
        unreadCounts[peer.onionAddress] = 0
        if (!activeSessions.containsKey(peer.onionAddress)) initiateHandshake(peer)
        currentScreenState = Screen.Chat(peer)
    }

    private fun handleVerifyPeer(peer: Peer) {
        val idx = peersList.indexOfFirst { it.onionAddress == peer.onionAddress }
        if (idx != -1) { peersList[idx] = peersList[idx].copy(isVerified = true); savePeers() }; currentScreenState = Screen.Home
    }

    private fun handleQRScan(data: String) {
        val peer = PairingTokenManager(this).parseAndVerifyToken(data)
        if (peer != null) {
            peerToConfirmWithKey = Triple(peer.onionAddress, peer.alias, peer.identityPublicKey)
        } else {
            Toast.makeText(this, "Errore Token", Toast.LENGTH_SHORT).show()
        }
        currentScreenState = Screen.Home
    }

    private fun handleTermsAccept() {
        isTermsAccepted = true
        getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(Constants.KEY_TERMS_ACCEPTED, true)
        }
        currentScreenState = Screen.Auth
    }
    private fun handleChangePassword(data: String): Boolean { val p = data.removePrefix("VERIFY:").split("|"); if (E2EManager.verifyPassword(p[0], savedPasswordHash ?: "")) { val h = E2EManager.hashPassword(p[1]); savePasswordHash(h); savedPasswordHash = h; currentScreenState = Screen.Settings; return true } else return false }
    private fun handleSeedRestore(seed: List<String>) { if (MnemonicManager.isValidMnemonic(seed)) { currentSeed = seed; importBackupLauncher.launch(arrayOf("application/json")) } }
    private fun handleDeleteSession(peer: Peer, messages: MutableList<Message>) { activeSessions.remove(peer.onionAddress); messages.clear(); currentScreenState = Screen.Home }
    private fun handleRemoveSeed() { if (currentSeed.isNotEmpty()) { getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().remove(Constants.KEY_SAVED_SEED).apply(); currentSeed = emptyList(); Toast.makeText(this, "RIMOSSO", Toast.LENGTH_SHORT).show() } }
    private fun handleExportInternal() { exportBackupLauncher.launch("torchat_backup.json") }

    private fun handleExport(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val salt = getOrCreateSalt()
            val backupJson = backupManager.createEncryptedBackupJson(currentSeed, salt)
            contentResolver.openOutputStream(uri)?.use { it.write(backupJson.toByteArray()) }
            currentScreenState = Screen.Home
        } catch (e: Exception) { Log.e(TAG, "Export error", e) }
    }

    private fun handleImport(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { i ->
                val pkg = BufferedReader(InputStreamReader(i)).readText()
                if (backupManager.restoreFromEncryptedBackup(pkg, currentSeed)) {
                    finish()
                    val restartIntent = Intent(this, MainActivity::class.java)
                    startActivity(restartIntent)
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Import error", e) }
    }

    private fun handleIncomingPacket(packet: RawPacket) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val onion = packet.senderOnion
                when (PayloadType.entries[packet.type.toInt()]) {
                    PayloadType.SESSION_HANDSHAKE -> handleHandshakeReceived(packet.dataB64, onion)
                    PayloadType.CHAT_MESSAGE, PayloadType.IMAGE, PayloadType.FILE -> {
                        val session = activeSessions[onion]
                        if (session != null) {
                            try {
                                val peerRatchetKey = E2EManager.stringToPublicKey(packet.ratchetPubKey, Constants.X25519_ALGO)
                                val header = DoubleRatchetSession.RatchetHeader(peerRatchetKey, packet.pn, packet.n)
                                val aad = E2EManager.buildAAD(packet.version, packet.type, packet.sequenceNumber, onion, session.sessionId, packet.ratchetPubKey, packet.pn, packet.n)

                                val dec = session.tryDecrypt(header, packet.dataB64, aad) { enc, key, a ->
                                    E2EManager.decryptV2(enc, key, a)
                                }

                                val msg = Message(
                                    id = UUID.randomUUID().toString(),
                                    senderOnion = onion,
                                    recipientOnion = (torManager.torState.value as? TorState.Running)?.onionAddress ?: "",
                                    content = dec,
                                    timestamp = System.currentTimeMillis(),
                                    isOutgoing = false,
                                    isDelivered = true,
                                    isError = false,
                                    type = PayloadType.entries[packet.type.toInt()],
                                    attachment = null,
                                    sequenceNumber = packet.sequenceNumber
                                )
                                messagesMap.getOrPut(onion) { mutableStateListOf() }.add(msg)
                                if (currentScreenState !is Screen.Chat || (currentScreenState as Screen.Chat).peer.onionAddress != onion) unreadCounts[onion] = (unreadCounts[onion] ?: 0) + 1
                                notificationHelper.showChatNotification(peersList.find { it.onionAddress == onion }?.alias ?: "Contatto", "Nuovo messaggio")
                            } catch (e: Exception) {
                                Log.e(TAG, "Decryption error for $onion: ${e.message}")
                            }
                        } else {
                            Log.w(TAG, "No active session for $onion. Dropping packet.")
                        }
                    }
                    else -> {}
                }
            } catch (e: Exception) { Log.e(TAG, "Incoming packet error", e) }
        }
    }

    private fun handleHandshakeReceived(dataB64: String, senderOnion: String) {
        try {
            val myOnion = (torManager.torState.value as? TorState.Running)?.onionAddress ?: return
            val json = String(Base64.getDecoder().decode(dataB64), Charsets.UTF_8)
            val p = Gson().fromJson(json, NetworkPayload::class.java)
            val pts = p.payloadData.split("|")

            if (pts.isEmpty()) return
            val tag = pts.last()

            when (tag) {
                "PFS_INIT" -> {
                    if (pts.size != 5) return
                    val peerEKStr = pts[0]
                    val peerIKStr = pts[1]
                    val peerNonce = Base64.getDecoder().decode(pts[2])
                    val initSig = Base64.getDecoder().decode(pts[3])
                    if (peerNonce.size != 16) return

                    val peerIdentityKey = E2EManager.stringToPublicKey(peerIKStr, Constants.ED25519_ALGO)
                    val existing = peersList.find { it.onionAddress == senderOnion } ?: return

                    // Identity Pinning (TOFU) - Resolves Audit Point 3
                    if (existing.identityPublicKey.isNotEmpty() && existing.identityPublicKey != peerIKStr) {
                        Log.e(TAG, "IDENTITY KEY CHANGED for $senderOnion! Potential MITM.")
                        Toast.makeText(this@MainActivity, "ALERT: Identity Key Changed!", Toast.LENGTH_LONG).show()
                        return
                    }

                    // Verify PFS_INIT Signature (Resolves Audit Point 7)
                    val initTranscript = E2EManager.buildInitTranscript(senderOnion, myOnion, peerIKStr, peerEKStr, peerNonce)
                    if (!E2EManager.verifySignature(initTranscript, initSig, peerIdentityKey)) {
                        Log.w(TAG, "Invalid PFS_INIT signature from $senderOnion")
                        return
                    }

                    // Bob (Responder) - Step 2
                    val myNonce = ByteArray(16).apply { SecureRandom().nextBytes(this) }
                    val respKeyPair = E2EManager.generateEphemeralKeyPair()
                    val respEKStr = E2EManager.publicKeyToString(respKeyPair.public)
                    val myIKStr = E2EManager.publicKeyToString(myIdentityKeyPair!!.public)

                    // Transcript Bob signs: (SenderOnion=Alice, MyOnion=Bob, ikA, eA, ikB, eB, nA, nB)
                    val fullTranscript = E2EManager.buildHandshakeTranscript(senderOnion, myOnion, peerIKStr, peerEKStr, myIKStr, respEKStr, peerNonce, myNonce)
                    val bobSig = Base64.getEncoder().encodeToString(E2EManager.signData(fullTranscript, myIdentityKeyPair!!.private))

                    // Store pending handshake indexed by Alice's nonce
                    val handshakeId = Base64.getEncoder().encodeToString(peerNonce)
                    val pending = PendingHandshake(senderOnion, respKeyPair, myNonce, peerNonce, peerIKStr, peerEKStr, handshakeManager.getCurrentTime())
                    if (!handshakeManager.addPending(handshakeId, pending)) {
                        Log.w(TAG, "Dropped handshake request from $senderOnion (DoS protection)")
                        return
                    }

                    val respData = "$respEKStr|$bobSig|$myIKStr|${Base64.getEncoder().encodeToString(peerNonce)}|${Base64.getEncoder().encodeToString(myNonce)}|PFS_ACCEPT"
                    val respEnc = Base64.getEncoder().encodeToString(Gson().toJson(NetworkPayload(
                        type = PayloadType.SESSION_HANDSHAKE,
                        senderOnion = myOnion,
                        recipientOnion = senderOnion,
                        payloadData = respData
                    )).toByteArray(Charsets.UTF_8))

                    CoroutineScope(Dispatchers.IO).launch {
                        p2pMessenger.sendEncryptedPayload(myOnion, senderOnion, PayloadType.SESSION_HANDSHAKE.ordinal.toByte(), 0, "", 0, 0, respEnc, timeoutMs = 30000)
                    }
                }

                "PFS_ACCEPT" -> {
                    if (pts.size != 6) return
                    val peerEKStr = pts[0]
                    val peerSig = Base64.getDecoder().decode(pts[1])
                    val peerIKStr = pts[2]
                    val nonceAStr = pts[3]
                    val peerNonce = Base64.getDecoder().decode(pts[4])
                    if (peerNonce.size != 16) return

                    val peerIdentityKey = E2EManager.stringToPublicKey(peerIKStr, Constants.ED25519_ALGO)
                    val existing = peersList.find { it.onionAddress == senderOnion } ?: return

                    if (existing.identityPublicKey.isNotEmpty() && existing.identityPublicKey != peerIKStr) return

                    // ATOMIC VERIFY AND CONSUME
                    var capturedPending: PendingHandshake? = null
                    val success = handshakeManager.verifyAndConsume(nonceAStr) { pending ->
                        capturedPending = pending
                        val myIKStr = E2EManager.publicKeyToString(myIdentityKeyPair!!.public)
                        val fullTranscript = E2EManager.buildHandshakeTranscript(myOnion, senderOnion, myIKStr, E2EManager.publicKeyToString(pending.myEphemeralKeys.public), peerIKStr, peerEKStr, pending.myNonce, peerNonce)
                        E2EManager.verifySignature(fullTranscript, peerSig, peerIdentityKey)
                    }

                    if (!success || capturedPending == null) return
                    val pending = capturedPending!!
                    val myEphemeral = pending.myEphemeralKeys
                    val myNonce = pending.myNonce
                    val myIKStr = E2EManager.publicKeyToString(myIdentityKeyPair!!.public)
                    val fullTranscript = E2EManager.buildHandshakeTranscript(myOnion, senderOnion, myIKStr, E2EManager.publicKeyToString(myEphemeral.public), peerIKStr, peerEKStr, myNonce, peerNonce)

                    // Alice signs same transcript - Step 3
                    val aliceSig = Base64.getEncoder().encodeToString(E2EManager.signData(fullTranscript, myIdentityKeyPair!!.private))
                    val finalData = "$aliceSig|${Base64.getEncoder().encodeToString(myNonce)}|${Base64.getEncoder().encodeToString(peerNonce)}|PFS_FINAL"
                    val finalEnc = Base64.getEncoder().encodeToString(Gson().toJson(NetworkPayload(
                        type = PayloadType.SESSION_HANDSHAKE,
                        senderOnion = myOnion,
                        recipientOnion = senderOnion,
                        payloadData = finalData
                    )).toByteArray(Charsets.UTF_8))

                    CoroutineScope(Dispatchers.IO).launch {
                        p2pMessenger.sendEncryptedPayload(myOnion, senderOnion, PayloadType.SESSION_HANDSHAKE.ordinal.toByte(), 0, "", 0, 0, finalEnc, timeoutMs = 30000)
                    }

                    val sharedSecret = E2EManager.calculateSharedSecret(myEphemeral.private, E2EManager.stringToPublicKey(peerEKStr, Constants.X25519_ALGO))
                    val sid = E2EManager.calculateSessionId(fullTranscript)
                    activeSessions[senderOnion] = DoubleRatchetSession(sid, sharedSecret, myEphemeral, E2EManager.stringToPublicKey(peerEKStr, Constants.X25519_ALGO))
                    handshakeLoading[senderOnion] = false
                }

                "PFS_FINAL" -> {
                    if (pts.size != 4) return
                    val aliceSig = Base64.getDecoder().decode(pts[0])
                    val nonceAStr = pts[1]
                    val nonceBStr = pts[2]

                    val peerIdentityKey = E2EManager.stringToPublicKey(peersList.find { it.onionAddress == senderOnion }?.identityPublicKey ?: "", Constants.ED25519_ALGO)

                    var capturedPending: PendingHandshake? = null
                    val success = handshakeManager.verifyAndConsume(nonceAStr) { pending ->
                        capturedPending = pending
                        if (Base64.getEncoder().encodeToString(pending.myNonce) != nonceBStr) return@verifyAndConsume false
                        val myIKStr = E2EManager.publicKeyToString(myIdentityKeyPair!!.public)
                        val fullTranscript = E2EManager.buildHandshakeTranscript(senderOnion, myOnion, pending.peerIdentityKeyStr!!, pending.peerEphemeralKeyStr!!, myIKStr, E2EManager.publicKeyToString(pending.myEphemeralKeys.public), pending.peerNonce!!, pending.myNonce)
                        E2EManager.verifySignature(fullTranscript, aliceSig, peerIdentityKey)
                    }

                    if (!success || capturedPending == null) return
                    val pending = capturedPending!!
                    val myEphemeral = pending.myEphemeralKeys
                    val peerEKStr = pending.peerEphemeralKeyStr!!
                    val myIKStr = E2EManager.publicKeyToString(myIdentityKeyPair!!.public)
                    val fullTranscript = E2EManager.buildHandshakeTranscript(senderOnion, myOnion, pending.peerIdentityKeyStr!!, peerEKStr, myIKStr, E2EManager.publicKeyToString(myEphemeral.public), pending.peerNonce!!, pending.myNonce)

                    val sharedSecret = E2EManager.calculateSharedSecret(myEphemeral.private, E2EManager.stringToPublicKey(peerEKStr, Constants.X25519_ALGO))
                    val sid = E2EManager.calculateSessionId(fullTranscript)
                    val session = DoubleRatchetSession(sid, sharedSecret, myEphemeral)
                    session.BobInit(E2EManager.stringToPublicKey(peerEKStr, Constants.X25519_ALGO))
                    activeSessions[senderOnion] = session
                    Log.i(TAG, "Session established (Responder)")
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Handshake error", e) }
    }


    private fun initializeSystem() {
        torManager = TorManager(this)
        p2pMessenger = P2PMessenger()
        notificationHelper = NotificationHelper(this)
        backupManager = BackupManager(this)
        mediaManager = MediaManager(this)

        val isHardware = E2EManager.isHardwareBacked()
        Log.i(TAG, "Hardware Keystore backed: $isHardware")
    }
    private fun loadPreferences() {
        val p = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        myAlias = p.getString(Constants.KEY_MY_ALIAS, "Amico") ?: "Amico"
        isDarkTheme = p.getBoolean(Constants.KEY_DARK_THEME, true)
        isAutoBackupEnabled = p.getBoolean(Constants.KEY_AUTO_BACKUP, false)
        isTermsAccepted = p.getBoolean(Constants.KEY_TERMS_ACCEPTED, false)
        savedPasswordHash = p.getString(Constants.KEY_PASS_HASH, null)
        failedAttempts = p.getInt(Constants.KEY_FAILED_ATTEMPTS, 0)
        expiryDate = p.getLong(Constants.KEY_EXPIRY, 0L)
        currentScreenState = if (!isTermsAccepted) Screen.TermsOfUse else Screen.Auth
        loadOrGenerateIdentityKeys(p); peersList.clear(); peersList.addAll(loadPeersFromPrefs(p))
    }

    private fun sendMessage(peer: Peer, content: String, messageList: MutableList<Message>) {
        val session = activeSessions[peer.onionAddress] ?: return
        val myOnion = (torManager.torState.value as? TorState.Running)?.onionAddress ?: ""

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sendResult = session.nextSendKey()
                val header = sendResult.header
                val rpkStr = E2EManager.publicKeyToString(header.ratchetPublicKey)
                val seq = networkSequence.getAndIncrement()

                val aad = E2EManager.buildAAD(1, PayloadType.CHAT_MESSAGE.ordinal.toByte(), seq, myOnion, session.sessionId, rpkStr, header.pn, header.n)
                val encrypted = E2EManager.encryptV2(content, sendResult.messageKey, aad)

                withContext(Dispatchers.Main) {
                    val msg = Message(UUID.randomUUID().toString(), myOnion, peer.onionAddress, content, System.currentTimeMillis(), true, false, false, PayloadType.CHAT_MESSAGE, null, seq)
                    messageList.add(msg)

                    CoroutineScope(Dispatchers.IO).launch {
                        val res = p2pMessenger.sendEncryptedPayload(
                            myOnion = myOnion,
                            recipientOnion = peer.onionAddress,
                            type = PayloadType.CHAT_MESSAGE.ordinal.toByte(),
                            sequenceNumber = seq,
                            ratchetPubKey = rpkStr,
                            pn = header.pn,
                            n = header.n,
                            encryptedDataB64 = encrypted
                        )
                        withContext(Dispatchers.Main) {
                            val idx = messageList.indexOfFirst { it.id == msg.id }
                            if (idx != -1) messageList[idx] = messageList[idx].copy(isDelivered = res.isSuccess, isError = !res.isSuccess)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send message error", e)
            }
        }
    }

    private fun handleMediaPick(uri: Uri, isImage: Boolean) {
        val peer = activeChatPeer ?: return
        val messageList = activeChatMessages ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // P1 Fix: Check file size BEFORE reading into memory
                val parcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
                val fileSize = parcelFileDescriptor?.statSize ?: 0L
                parcelFileDescriptor?.close()

                if (fileSize > 1 * 1024 * 1024) { // 1MB Limit
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "File too large (max 1MB)", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val bytes = if (isImage) mediaManager.stripImageMetadata(uri) else mediaManager.getFileBytes(uri)
                bytes?.let {
                    val b64 = Base64.getEncoder().encodeToString(it)
                    withContext(Dispatchers.Main) {
                        sendMessage(peer, b64, messageList)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Media pick error", e)
            }
        }
    }
    private fun saveAttachmentToExternalStorage(f: String, b: String) { try { val bts = Base64.getDecoder().decode(b); val v = ContentValues().apply { put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f) }; val uri = contentResolver.insert(if (Build.VERSION.SDK_INT >= 29) android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Files.getContentUri("external"), v); uri?.let { contentResolver.openOutputStream(it)?.use { os -> os.write(bts) }; CoroutineScope(Dispatchers.Main).launch { Toast.makeText(this@MainActivity, "OK", Toast.LENGTH_SHORT).show() } } } catch (e: Exception) { } }
    private fun sanitizeOnionAddress(o: String): String = o.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")
    private fun loadPeersFromPrefs(p: android.content.SharedPreferences): List<Peer> {
        val d = p.getString(Constants.KEY_SAVED_PEERS, null) ?: return emptyList()
        return try {
            val h = p.getString(Constants.KEY_PASS_HASH, null)
            if (h != null) {
                // If we have a password, we MUST be able to decrypt. No plaintext loading (Audit P1).
                val json = E2EManager.decrypt(d, E2EManager.deriveKeyFromSecret(h, getOrCreateSalt()))
                Gson().fromJson(json, object : TypeToken<List<Peer>>() {}.type)
            } else {
                // If no password yet, we might be in initial setup or migration.
                if (d.startsWith("[")) {
                    Gson().fromJson(d, object : TypeToken<List<Peer>>() {}.type)
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load peers database", e)
            emptyList()
        }
    }

    private fun savePeers() {
        val p = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        val j = Gson().toJson(peersList.toList())
        try {
            val h = p.getString(Constants.KEY_PASS_HASH, null)
            if (h != null) {
                val data = E2EManager.encrypt(j, E2EManager.deriveKeyFromSecret(h, getOrCreateSalt()))
                p.edit { putString(Constants.KEY_SAVED_PEERS, data) }
            } else {
                Log.w(TAG, "Skipping peers save: Password hash not available yet.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to encrypt peers database. Data is NOT saved to disk.", e)
        }
    }

    private fun getOrCreateSalt(): ByteArray {
        val p = getSharedPreferences("secure_prefs_salt", MODE_PRIVATE)
        val sEnc = p.getString("install_salt_enc", null) ?: return generateAndSaveSalt(p)
        return try {
            Base64.getDecoder().decode(E2EManager.decryptWithHardwareKey(sEnc).getOrThrow())
        } catch (e: Exception) {
            throw IllegalStateException("Secure storage (Hardware Keystore) is inaccessible.", e)
        }
    }
    private fun generateAndSaveSalt(p: android.content.SharedPreferences): ByteArray { val s = ByteArray(16).apply { SecureRandom().nextBytes(this) }; val enc = E2EManager.encryptWithHardwareKey(Base64.getEncoder().encodeToString(s)).getOrNull() ?: ""; p.edit { putString("install_salt_enc", enc) }; return s }
    private fun saveThemePreference(d: Boolean) { getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit { putBoolean(Constants.KEY_DARK_THEME, d) } }
    private fun saveAvailabilityPreference(a: Boolean) { getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit { putBoolean("is_available", a) } }
    private fun savePasswordHash(h: String) { getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit { putString(Constants.KEY_PASS_HASH, h) } }
    private fun saveFailedAttempts(a: Int) { getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit { putInt(Constants.KEY_FAILED_ATTEMPTS, a) } }
    private fun saveExpiryDate(d: Long) { getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit { putLong(Constants.KEY_EXPIRY, d) } }
    private fun saveAutoBackupPreference(e: Boolean) { getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit { putBoolean(Constants.KEY_AUTO_BACKUP, e) } }
    private fun saveMyAlias(a: String) { getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit { putString(Constants.KEY_MY_ALIAS, a) } }
    private fun performWipe() {
        try {
            E2EManager.deleteMasterKey()
        } catch (e: Exception) { }

        val prefs = listOf(Constants.PREFS_NAME, "secure_prefs_salt", "pairing_prefs")
        prefs.forEach { name ->
            getSharedPreferences(name, MODE_PRIVATE).edit { clear() }
        }

        val d = File(filesDir, "tor")
        if (d.exists()) d.deleteRecursively()

        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(0)
    }
    private fun loadOrGenerateIdentityKeys(p: android.content.SharedPreferences) {
        val seedEnc = p.getString(Constants.KEY_IDENTITY_SEED_ENC, null)
        if (seedEnc != null) {
            try {
                val seed = Base64.getDecoder().decode(E2EManager.decryptWithHardwareKey(seedEnc).getOrThrow())
                myIdentityKeyPair = E2EManager.ed25519KeyPairFromSeed(seed)
                p.edit().putString(Constants.KEY_PUBLIC_KEY, E2EManager.publicKeyToString(myIdentityKeyPair!!.public)).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore identity from seed", e)
                ensureIdentityLinkedToMnemonic(p)
            }
        } else {
            ensureIdentityLinkedToMnemonic(p)
        }
    }

    /**
     * Ensures that the identity seed is derived from the mnemonic entropy.
     * Resolves Audit Point 5 & 6.
     */
    private fun ensureIdentityLinkedToMnemonic(p: android.content.SharedPreferences) {
        val savedMnemonic = p.getString(Constants.KEY_SAVED_SEED, null)
        val mnemonic = if (savedMnemonic != null) savedMnemonic.split(" ") else {
            val newMnemonic = MnemonicManager.generateMnemonic()
            p.edit().putString(Constants.KEY_SAVED_SEED, newMnemonic.joinToString(" ")).apply()
            newMnemonic
        }

        val entropy = MnemonicManager.mnemonicToEntropy(mnemonic) ?: throw SecurityException("Invalid mnemonic")
        // Derive a 32-byte seed from the mnemonic entropy using HKDF (V2)
        val identitySeed = HKDF.deriveKey(entropy, null, "TorChat/V2/IdentitySeed".toByteArray(), 32)

        myIdentityKeyPair = E2EManager.ed25519KeyPairFromSeed(identitySeed)
        val seedEnc = E2EManager.encryptWithHardwareKey(Base64.getEncoder().encodeToString(identitySeed)).getOrNull() ?: ""

        p.edit().apply {
            putString(Constants.KEY_IDENTITY_SEED_ENC, seedEnc)
            putString(Constants.KEY_PUBLIC_KEY, E2EManager.publicKeyToString(myIdentityKeyPair!!.public))
            apply()
        }
    }
    private fun initiateHandshake(p: Peer) {
        if (p.identityPublicKey.isEmpty()) return
        if (handshakeLoading[p.onionAddress] == true) return
        handshakeLoading[p.onionAddress] = true
        val myOnion = (torManager.torState.value as? TorState.Running)?.onionAddress ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val myEphemeralKeyPair = E2EManager.generateEphemeralKeyPair()
                val myNonce = ByteArray(16).apply { SecureRandom().nextBytes(this) }

                val eKStr = E2EManager.publicKeyToString(myEphemeralKeyPair.public)
                val myIKStr = E2EManager.publicKeyToString(myIdentityKeyPair!!.public)
                val nonceAStr = Base64.getEncoder().encodeToString(myNonce)

                // Alice signs PFS_INIT (Resolves Audit Point 7)
                val initTranscript = E2EManager.buildInitTranscript(myOnion, p.onionAddress, myIKStr, eKStr, myNonce)
                val aliceInitSig = Base64.getEncoder().encodeToString(E2EManager.signData(initTranscript, myIdentityKeyPair!!.private))

                // Alice stores pending handshake keyed by her own nonce
                val pending = PendingHandshake(p.onionAddress, myEphemeralKeyPair, myNonce, null, null, null, handshakeManager.getCurrentTime())
                handshakeManager.addPending(nonceAStr, pending)

                val data = "$eKStr|$myIKStr|$nonceAStr|$aliceInitSig|PFS_INIT"
                val encryptedJson = Base64.getEncoder().encodeToString(Gson().toJson(NetworkPayload(type = PayloadType.SESSION_HANDSHAKE, senderOnion = myOnion, recipientOnion = p.onionAddress, payloadData = data)).toByteArray(Charsets.UTF_8))
                p2pMessenger.sendEncryptedPayload(myOnion, p.onionAddress, PayloadType.SESSION_HANDSHAKE.ordinal.toByte(), 0, "", 0, 0, encryptedJson, timeoutMs = 30000)
            } catch (e: Exception) {
                // Remove on failure
            } finally {
                // Note: handshakeLoading[p.onionAddress] remains true until PFS_ACCEPT is received or timeout
            }
        }
    }

    private fun broadcastMyStatus(a: Boolean) { val my = (torManager.torState.value as? TorState.Running)?.onionAddress ?: ""; if (my.isEmpty()) return; peersList.forEach { val pay = Base64.getEncoder().encodeToString(Gson().toJson(NetworkPayload(type = PayloadType.PONG, senderOnion = my, recipientOnion = it.onionAddress, payloadData = if (a) "ONLINE" else "OFFLINE")).toByteArray(Charsets.UTF_8)); CoroutineScope(Dispatchers.IO).launch { p2pMessenger.sendEncryptedPayload(my, it.onionAddress, PayloadType.PONG.ordinal.toByte(), 0, "", 0, 0, pay, timeoutMs = 10000) } } }
    private fun checkAndRequestPermissions() { val p = mutableListOf(Manifest.permission.CAMERA); if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS); val missing = p.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }; if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) }
}
