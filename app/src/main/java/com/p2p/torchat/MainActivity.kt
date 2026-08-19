package com.p2p.torchat

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.p2p.torchat.crypto.E2EManager
import com.p2p.torchat.crypto.MnemonicManager
import com.p2p.torchat.crypto.RatchetSession
import com.p2p.torchat.model.*
import com.p2p.torchat.service.*
import com.p2p.torchat.ui.screens.*
import com.p2p.torchat.ui.theme.TorP2PChatTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

sealed class Screen {
    object Auth : Screen()
    object Home : Screen()
    object Settings : Screen()
    object ChangePassword : Screen()
    object SeedBackup : Screen()
    object SeedRestore : Screen()
    object Subscription : Screen()
    object Info : Screen()
    object TermsOfUse : Screen()
    data class Chat(val peer: Peer) : Screen()
    data class Verification(val peer: Peer) : Screen()
    object QRCode : Screen()
    object QRScanner : Screen()
}

class MainActivity : ComponentActivity() {
    companion object { private const val TAG = "MainActivity" }

    private lateinit var torManager: TorManager
    private lateinit var localServer: LocalServer
    private lateinit var p2pMessenger: P2PMessenger
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var backupManager: BackupManager
    private lateinit var mediaManager: MediaManager
    private val totpManager = TotpManager()
    private val timeFetcher = NetworkTimeFetcher()

    private lateinit var myIdentityKeyPair: KeyPair
    private val activeSessions = mutableStateMapOf<String, RatchetSession>()
    private val handshakeLoading = mutableStateMapOf<String, Boolean>()

    private val peersList = mutableStateListOf<Peer>()
    private val messagesMap = mutableStateMapOf<String, MutableList<Message>>()
    private val unreadCounts = mutableStateMapOf<String, Int>()

    private var currentScreenState by mutableStateOf<Screen>(Screen.Auth)
    private var isAuthenticated by mutableStateOf(false)
    private var isTermsAccepted by mutableStateOf(false)
    private var savedPasswordHash by mutableStateOf<String?>(null)
    private var failedAttempts by mutableStateOf(0)
    private var currentSeed by mutableStateOf<List<String>>(emptyList())

    private var myAlias by mutableStateOf("Amico")
    private var isDarkTheme by mutableStateOf(true)
    private var isAutoBackupEnabled by mutableStateOf(false)
    private var isAvailable by mutableStateOf(false)
    private var expiryDate by mutableStateOf(0L)

    private var peerToConfirmWithKey by mutableStateOf<Triple<String, String, String>?>(null)
    private var activeChatPeer: Peer? = null
    private var activeChatMessages: MutableList<Message>? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> u?.let { handleMediaPick(it, true) } }
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { u -> u?.let { handleMediaPick(it, false) } }
    private val exportBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u -> u?.let { handleExport(it) } }
    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { handleImport(it) } }
    private val orbotLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res -> if (res.resultCode == RESULT_OK) res.data?.getStringExtra("onion_address")?.let { torManager.setTorRunning(it) } }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        initializeSystem()
        loadPreferences()

        localServer = LocalServer(port = 8080, onMessageReceived = { handleIncomingPayload(it) })
        if (torManager.isOrbotInstalled()) {
            val s = getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).getString("saved_onion_address", null)
            if (s != null) torManager.setTorRunning(s) else orbotLauncher.launch(torManager.getOrbotRequestIntent())
        }
        localServer.startServer()
        startPresenceLoop()

        setContent {
            TorP2PChatTheme(darkTheme = isDarkTheme) {
                AppContent()
            }
        }
    }

    @Composable
    private fun AppContent() {
        val torState by torManager.torState.collectAsState()
        val myOnion = (torState as? TorState.Running)?.onionAddress ?: ""

        LaunchedEffect(isAuthenticated, torState) {
            if (isAuthenticated && torState is TorState.Running) {
                val nt = timeFetcher.fetchTimeViaTor() ?: System.currentTimeMillis()
                if (nt > expiryDate && expiryDate != 0L) {
                    currentScreenState = Screen.Subscription
                }
            }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val screen = currentScreenState) {
                is Screen.Auth -> com.p2p.torchat.ui.screens.AuthScreen(
                    mode = if (savedPasswordHash == null) AuthMode.CREATE else AuthMode.LOGIN,
                    attemptsLeft = 3 - failedAttempts,
                    onAuthSuccess = { handleAuthResult(it) }
                )
                is Screen.Subscription -> com.p2p.torchat.ui.screens.SubscriptionScreen(
                    onionAddress = myOnion,
                    onActivate = { handleSubscription(it, myOnion) }
                )
                is Screen.Home -> com.p2p.torchat.ui.screens.HomeScreen(
                    torState = torState,
                    myOnionAddress = myOnion,
                    myAlias = myAlias,
                    myPublicKey = E2EManager.publicKeyToString(myIdentityKeyPair.public),
                    isDarkTheme = isDarkTheme,
                    isAvailable = isAvailable,
                    expiryDate = expiryDate,
                    peers = peersList,
                    unreadCounts = unreadCounts,
                    peerToConfirm = peerToConfirmWithKey,
                    onToggleTheme = { isDarkTheme = !isDarkTheme; saveThemePreference(isDarkTheme) },
                    onToggleAvailability = { isAvailable = !isAvailable; saveAvailabilityPreference(isAvailable); broadcastMyStatus(isAvailable) },
                    onUpdateMyAlias = { myAlias = it; saveMyAlias(it) },
                    onAddPeerDirect = { a, o, k -> handleAddPeer(a, o, k) },
                    onSelectPeer = { handleSelectPeer(it) },
                    onOpenQRCode = { currentScreenState = Screen.QRCode },
                    onOpenQRScanner = { currentScreenState = Screen.QRScanner },
                    onOpenSettings = { currentScreenState = Screen.Settings },
                    onUpdateOnionAddress = { torManager.setTorRunning(it) },
                    onDeletePeer = { peersList.remove(it); savePeers() },
                    onConfirmPeerHandled = { peerToConfirmWithKey = null }
                )
                is Screen.Chat -> {
                    val ms = messagesMap.getOrPut(screen.peer.onionAddress) { mutableStateListOf() }
                    LaunchedEffect(screen.peer.onionAddress) {
                        if (!activeSessions.containsKey(screen.peer.onionAddress)) initiateHandshake(screen.peer)
                    }
                    com.p2p.torchat.ui.screens.ChatScreen(
                        peer = screen.peer,
                        messages = ms,
                        isHandshakeLoading = handshakeLoading[screen.peer.onionAddress] ?: false,
                        onSendMessage = { sendMessage(screen.peer, it, ms) },
                        onPickImage = { activeChatPeer = screen.peer; activeChatMessages = ms; pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onPickFile = { activeChatPeer = screen.peer; activeChatMessages = ms; pickFileLauncher.launch("*/*") },
                        onSaveAttachment = { f, b -> saveAttachmentToExternalStorage(f, b) },
                        onDeleteSession = { handleDeleteSession(screen.peer, ms) },
                        onOpenVerification = { currentScreenState = Screen.Verification(screen.peer) },
                        onBack = { currentScreenState = Screen.Home }
                    )
                }
                is Screen.QRCode -> com.p2p.torchat.ui.screens.QRCodeScreen(myOnion, myAlias, E2EManager.publicKeyToString(myIdentityKeyPair.public)) { currentScreenState = Screen.Home }
                is Screen.Verification -> com.p2p.torchat.ui.screens.VerificationScreen(screen.peer, { handleVerifyPeer(screen.peer) }) { currentScreenState = Screen.Chat(screen.peer) }
                is Screen.QRScanner -> com.p2p.torchat.ui.screens.ClientQRScannerScreen({ handleQRScan(it) }) { currentScreenState = Screen.Home }
                is Screen.Settings -> com.p2p.torchat.ui.screens.SettingsScreen({ currentScreenState = Screen.SeedBackup }, { currentScreenState = Screen.SeedRestore }, isAutoBackupEnabled, { isAutoBackupEnabled = it; saveAutoBackupPreference(it) }, { currentScreenState = Screen.ChangePassword }, { currentScreenState = Screen.Subscription }, expiryDate, { currentScreenState = Screen.Info }, { currentScreenState = Screen.TermsOfUse }, { currentScreenState = Screen.Home })
                is Screen.TermsOfUse -> com.p2p.torchat.ui.screens.TermsOfUseScreen(isViewOnly = isTermsAccepted, onAccept = { handleTermsAccept() }) { currentScreenState = Screen.Settings }
                is Screen.Info -> com.p2p.torchat.ui.screens.InfoScreen { currentScreenState = Screen.Settings }
                is Screen.ChangePassword -> com.p2p.torchat.ui.screens.AuthScreen(mode = AuthMode.CHANGE, onAuthSuccess = { handleChangePassword(it) }) { currentScreenState = Screen.Settings }
                is Screen.SeedBackup -> {
                    if (currentSeed.isEmpty()) {
                        val p = getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE)
                        val saved = p.getString("saved_seed", null)
                        currentSeed = if (saved != null) saved.split(" ") else MnemonicManager.generateMnemonic()
                    }
                    com.p2p.torchat.ui.screens.SeedScreen(SeedMode.DISPLAY, currentSeed, { exportBackupLauncher.launch("torchat_backup.json") }, { currentScreenState = Screen.Home }, { currentScreenState = Screen.Home }, { handleRemoveSeed() })
                }
                is Screen.SeedRestore -> com.p2p.torchat.ui.screens.SeedScreen(SeedMode.INPUT, emptyList(), { handleSeedRestore(it) }, { currentScreenState = Screen.Settings })
            }
        }
    }

    private fun handleAuthResult(password: String): Boolean {
        if (E2EManager.verifyPassword(password, savedPasswordHash ?: "")) {
            isAuthenticated = true; failedAttempts = 0; saveFailedAttempts(0)
            currentScreenState = Screen.Home
            return true
        }
        failedAttempts++
        saveFailedAttempts(failedAttempts)
        if (failedAttempts >= 3) performWipe()
        return false
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
                    Toast.makeText(this@MainActivity, "Abbonamento Attivato", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleAddPeer(alias: String, onion: String, pubKey: String) {
        val clean = sanitizeOnionAddress(onion)
        if (peersList.any { it.onionAddress == clean }) {
            Toast.makeText(this, "Contatto già esistente", Toast.LENGTH_SHORT).show()
        } else {
            peersList.add(Peer(clean, alias, identityPublicKey = pubKey))
            savePeers()
        }
    }

    private fun handleSelectPeer(peer: Peer) {
        unreadCounts[peer.onionAddress] = 0
        if (!activeSessions.containsKey(peer.onionAddress)) initiateHandshake(peer)
        currentScreenState = Screen.Chat(peer)
    }

    private fun handleVerifyPeer(peer: Peer) {
        val idx = peersList.indexOfFirst { it.onionAddress == peer.onionAddress }
        if (idx != -1) {
            peersList[idx] = peersList[idx].copy(isVerified = true)
            savePeers()
        }; currentScreenState = Screen.Home
    }

    private fun handleQRScan(data: String) {
        val p = data.split("|")
        peerToConfirmWithKey = Triple(sanitizeOnionAddress(p[0]), if (p.size > 1) p[1] else "Amico", if (p.size > 2) p[2] else "")
        currentScreenState = Screen.Home
    }

    private fun handleTermsAccept() {
        isTermsAccepted = true
        getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("is_terms_accepted", true)
            .putLong("terms_accepted_timestamp", System.currentTimeMillis()).apply()
        currentScreenState = Screen.Auth
    }

    private fun handleChangePassword(data: String): Boolean {
        val p = data.removePrefix("VERIFY:").split("|")
        if (E2EManager.verifyPassword(p[0], savedPasswordHash ?: "")) {
            val h = E2EManager.hashPassword(p[1])
            savePasswordHash(h); savedPasswordHash = h
            currentScreenState = Screen.Settings
            return true
        } else return false
    }

    private fun handleSeedRestore(seed: List<String>) {
        if (MnemonicManager.isValidMnemonic(seed)) {
            currentSeed = seed
            importBackupLauncher.launch(arrayOf("application/json"))
        } else {
            Toast.makeText(this, "Seed non valido", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDeleteSession(peer: Peer, messages: MutableList<Message>) {
        CoroutineScope(Dispatchers.IO).launch {
            val my = (torManager.torState.value as? TorState.Running)?.onionAddress ?: ""
            p2pMessenger.sendPayloadOverTor(peer.onionAddress, NetworkPayload(PayloadType.SESSION_TERMINATE, my, peer.onionAddress, "TERMINATE"))
        }
        messages.clear()
        activeSessions.remove(peer.onionAddress)
        handshakeLoading.remove(peer.onionAddress)
        currentScreenState = Screen.Home
    }

    private fun handleRemoveSeed() {
        if (currentSeed.isNotEmpty()) {
            getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().apply {
                remove("saved_seed")
                apply()
            }
            currentSeed = emptyList()
            Toast.makeText(this, "Seed rimosso", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleExport(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val salt = getOrCreateSalt()
            val backupJson = backupManager.createEncryptedBackupJson(currentSeed, salt)
            contentResolver.openOutputStream(uri)?.use { it.write(backupJson.toByteArray()) }
            Toast.makeText(this, "Backup Esportato", Toast.LENGTH_SHORT).show()
            currentScreenState = Screen.Home
        } catch (e: Exception) {
            Toast.makeText(this, "Errore export", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleImport(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { i ->
                val pkg = BufferedReader(InputStreamReader(i)).readText()
                if (backupManager.restoreFromEncryptedBackup(pkg, currentSeed)) {
                    finish(); startActivity(intent)
                } else {
                    Toast.makeText(this, "Errore Ripristino", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) { }
    }

    private fun handleIncomingPayload(payload: NetworkPayload) {
        CoroutineScope(Dispatchers.Main).launch {
            when (payload.type) {
                PayloadType.CHAT_MESSAGE, PayloadType.IMAGE, PayloadType.FILE -> {
                    if (isAvailable) try {
                        val session = activeSessions[payload.senderOnion]
                        if (session != null) {
                            val messageKey = session.nextReceiveKey()
                            val ctx = session.getSessionContext(payload.sequenceNumber)
                            val dec = E2EManager.decryptV2(payload.payloadData, messageKey, ctx)

                            val incomingMsg = Message(
                                senderOnion = payload.senderOnion,
                                recipientOnion = payload.recipientOnion,
                                content = dec,
                                isOutgoing = false,
                                isDelivered = true,
                                type = payload.type,
                                attachment = payload.attachmentMetadata,
                                sequenceNumber = payload.sequenceNumber
                            )

                            messagesMap.getOrPut(payload.senderOnion) { mutableStateListOf() }.add(incomingMsg)
                            if (currentScreenState !is Screen.Chat || (currentScreenState as Screen.Chat).peer.onionAddress != payload.senderOnion) {
                                unreadCounts[payload.senderOnion] = (unreadCounts[payload.senderOnion] ?: 0) + 1
                            }
                            notificationHelper.showChatNotification(peersList.find { it.onionAddress == payload.senderOnion }?.alias ?: "Contatto", dec)
                        }
                    } catch (e: Exception) { Log.e(TAG, "Decryption Error") }
                }
                PayloadType.SESSION_HANDSHAKE -> if (isAvailable) handleHandshakeReceived(payload)
                PayloadType.SESSION_TERMINATE -> {
                    activeSessions.remove(payload.senderOnion)
                    messagesMap[payload.senderOnion]?.clear()
                }
                PayloadType.PING -> {
                    val my = (torManager.torState.value as? TorState.Running)?.onionAddress ?: ""
                    launch(Dispatchers.IO) {
                        p2pMessenger.sendPayloadOverTor(payload.senderOnion, NetworkPayload(PayloadType.PONG, my, payload.senderOnion, if (isAvailable) "ONLINE" else "OFFLINE"), timeoutMs = 10000)
                    }
                }
                PayloadType.PONG -> {
                    val idx = peersList.indexOfFirst { it.onionAddress == payload.senderOnion }
                    if (idx != -1) {
                        peersList[idx] = peersList[idx].copy(isOnline = payload.payloadData == "ONLINE", lastSeenTimestamp = System.currentTimeMillis())
                    }
                }
                else -> {}
            }
        }
    }

    private fun handleHandshakeReceived(p: NetworkPayload) {
        try {
            val pts = p.payloadData.split("|"); if (pts.size < 4) return
            val existing = peersList.find { it.onionAddress == p.senderOnion } ?: return
            val peerIdentityKey = E2EManager.stringToPublicKey(pts[2], "Ed25519")

            if (existing.identityPublicKey.isNotEmpty() && existing.identityPublicKey != pts[2]) {
                CoroutineScope(Dispatchers.Main).launch { Toast.makeText(this@MainActivity, "SECURITY ALERT: Chiave cambiata!", Toast.LENGTH_LONG).show() }
                return
            }

            if (!E2EManager.verifyHandshake(pts[0], pts[1], peerIdentityKey)) return

            if (existing.identityPublicKey.isEmpty()) {
                val idx = peersList.indexOf(existing)
                if (idx != -1) {
                    peersList[idx] = existing.copy(identityPublicKey = pts[2])
                    savePeers()
                }
            }

            val peerEphemeralPubKey = E2EManager.stringToPublicKey(pts[0], "X25519")
            val myEphemeralKeyPair = E2EManager.generateEphemeralKeyPair()
            val sharedSecret = E2EManager.calculateSharedSecret(myEphemeralKeyPair.private, peerEphemeralPubKey)

            activeSessions[p.senderOnion] = RatchetSession(sharedSecret)
            handshakeLoading[p.senderOnion] = false
        } catch (e: Exception) { Log.e(TAG, "Handshake error") }
    }

    private fun initializeSystem() {
        checkAndRequestPermissions()
        torManager = TorManager(this)
        p2pMessenger = P2PMessenger()
        notificationHelper = NotificationHelper(this)
        backupManager = BackupManager(this)
        mediaManager = MediaManager(this)
    }

    private fun loadPreferences() {
        val p = getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE)
        myAlias = p.getString("my_alias", "Amico") ?: "Amico"
        isDarkTheme = p.getBoolean("is_dark_theme", true)
        isAutoBackupEnabled = p.getBoolean("is_auto_backup_enabled", false)
        isTermsAccepted = p.getBoolean("is_terms_accepted", false)
        savedPasswordHash = p.getString("app_password_hash", null)
        failedAttempts = p.getInt("failed_attempts", 0)
        expiryDate = p.getLong("account_expiry_date", 0L)

        currentScreenState = if (!isTermsAccepted) Screen.TermsOfUse else Screen.Auth

        loadOrGenerateIdentityKeys(p)
        peersList.clear()
        peersList.addAll(loadPeersFromPrefs(p))
    }

    private fun sendMessage(peer: Peer, content: String, messageList: MutableList<Message>) {
        if (handshakeLoading[peer.onionAddress] == true) return
        val session = activeSessions[peer.onionAddress] ?: return initiateHandshake(peer)

        val messageKey = session.nextSendKey()
        val ctx = session.getSessionContext(session.sendSequence)
        val encrypted = E2EManager.encryptV2(content, messageKey, ctx)
        val my = (torManager.torState.value as? TorState.Running)?.onionAddress ?: ""

        val msg = Message(senderOnion = my, recipientOnion = peer.onionAddress, content = content, isOutgoing = true, type = PayloadType.CHAT_MESSAGE, sequenceNumber = session.sendSequence)
        CoroutineScope(Dispatchers.Main).launch { messageList.add(msg) }

        CoroutineScope(Dispatchers.IO).launch {
            val payload = NetworkPayload(
                type = PayloadType.CHAT_MESSAGE,
                senderOnion = my,
                recipientOnion = peer.onionAddress,
                payloadData = encrypted,
                sequenceNumber = session.sendSequence
            )
            val res = p2pMessenger.sendPayloadOverTor(peer.onionAddress, payload)
            val idx = messageList.indexOf(msg)
            if (idx != -1) withContext(Dispatchers.Main) {
                messageList[idx] = msg.copy(isDelivered = res.isSuccess, isError = !res.isSuccess)
            }
        }
    }

    private fun handleMediaPick(u: Uri, i: Boolean) {
        val p = activeChatPeer ?: return
        val m = activeChatMessages ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val b = if (i) mediaManager.stripImageMetadata(u) else mediaManager.getFileBytes(u)
            b?.let {
                val b64 = Base64.getEncoder().encodeToString(it)
                sendMessage(p, b64, m)
            }
        }
    }

    private fun saveAttachmentToExternalStorage(f: String, b: String) {
        try {
            val bts = Base64.getDecoder().decode(b)
            val v = ContentValues().apply { put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f) }
            val uri = contentResolver.insert(if (android.os.Build.VERSION.SDK_INT >= 29) android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Files.getContentUri("external"), v)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os -> os.write(bts) }
                CoroutineScope(Dispatchers.Main).launch { Toast.makeText(this@MainActivity, "Allegato salvato", Toast.LENGTH_SHORT).show() }
            }
        } catch (e: Exception) { }
    }

    private fun sanitizeOnionAddress(o: String): String = o.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")

    private fun loadPeersFromPrefs(p: android.content.SharedPreferences): List<Peer> {
        val d = p.getString("saved_peers", null) ?: return emptyList()
        return try {
            val json = if (d.startsWith("[")) d else {
                val h = p.getString("app_password_hash", null)
                if (h != null) E2EManager.decrypt(d, E2EManager.deriveKeyFromSecret(h)) else d
            }
            Gson().fromJson(json, object : TypeToken<List<Peer>>() {}.type)
        } catch (e: Exception) { emptyList() }
    }

    private fun savePeers() {
        val p = getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE)
        val j = Gson().toJson(peersList.toList())
        try {
            val h = p.getString("app_password_hash", null)
            val data = if (h != null) E2EManager.encrypt(j, E2EManager.deriveKeyFromSecret(h)) else j
            p.edit().putString("saved_peers", data).apply()
        } catch (e: Exception) {
            p.edit().putString("saved_peers", j).apply()
        }
    }

    private fun getOrCreateSalt(): ByteArray {
        val p = getSharedPreferences("secure_prefs_salt", Context.MODE_PRIVATE)
        val sEnc = p.getString("install_salt_enc", null)
        return if (sEnc != null) {
            try { Base64.getDecoder().decode(E2EManager.decryptWithHardwareKey(sEnc)) } catch (e: Exception) { generateAndSaveSalt(p) }
        } else generateAndSaveSalt(p)
    }

    private fun generateAndSaveSalt(p: android.content.SharedPreferences): ByteArray {
        val s = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val enc = E2EManager.encryptWithHardwareKey(Base64.getEncoder().encodeToString(s))
        p.edit().putString("install_salt_enc", enc).apply()
        return s
    }

    private fun saveMyAlias(a: String) { getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().putString("my_alias", a).apply() }
    private fun saveThemePreference(d: Boolean) { getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_dark_theme", d).apply() }
    private fun saveAvailabilityPreference(a: Boolean) { getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_available", a).apply() }
    private fun savePasswordHash(h: String) { getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().putString("app_password_hash", h).apply() }
    private fun saveFailedAttempts(a: Int) { getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().putInt("failed_attempts", a).apply() }
    private fun saveExpiryDate(d: Long) { getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().putLong("account_expiry_date", d).apply() }
    private fun saveAutoBackupPreference(e: Boolean) { getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_auto_backup_enabled", e).apply() }

    private fun performWipe() {
        getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        try { E2EManager.deleteMasterKey() } catch (e: Exception) { }
        val d = File(filesDir, "tor")
        if (d.exists()) d.deleteRecursively()
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(this@MainActivity, "WIPE ESEGUITO", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun loadOrGenerateIdentityKeys(p: android.content.SharedPreferences) {
        val pub = p.getString("my_public_key", null)
        val privEnc = p.getString("my_private_key_enc", null)
        if (pub != null && privEnc != null) try {
            val priv = E2EManager.decryptWithHardwareKey(privEnc)
            val f = KeyFactory.getInstance("Ed25519")
            myIdentityKeyPair = KeyPair(
                f.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pub))),
                f.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(priv)))
            )
        } catch (e: Exception) { generateNewIdentityKeys(p) }
        else generateNewIdentityKeys(p)
    }

    private fun generateNewIdentityKeys(p: android.content.SharedPreferences) {
        myIdentityKeyPair = if (currentSeed.isNotEmpty()) {
            E2EManager.deriveIdentityKeyPair(MnemonicManager.mnemonicToEntropy(currentSeed)!!)
        } else {
            E2EManager.generateIdentityKeyPair()
        }
        val priv = Base64.getEncoder().encodeToString(myIdentityKeyPair.private.encoded)
        val enc = E2EManager.encryptWithHardwareKey(priv)
        p.edit().apply {
            putString("my_public_key", E2EManager.publicKeyToString(myIdentityKeyPair.public))
            putString("my_private_key_enc", enc)
            remove("my_private_key")
            apply()
        }
    }

    private fun initiateHandshake(p: Peer) {
        if (p.identityPublicKey.isEmpty()) return
        if (handshakeLoading[p.onionAddress] == true) return
        handshakeLoading[p.onionAddress] = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val myEphemeralKeyPair = E2EManager.generateEphemeralKeyPair()
                val eKStr = E2EManager.publicKeyToString(myEphemeralKeyPair.public)
                val sig = E2EManager.signHandshake(eKStr, myIdentityKeyPair.private)
                val myIDStr = E2EManager.publicKeyToString(myIdentityKeyPair.public)
                val data = "$eKStr|$sig|$myIDStr|PFS"
                val res = p2pMessenger.sendPayloadOverTor(p.onionAddress, NetworkPayload(type = PayloadType.SESSION_HANDSHAKE, senderOnion = (torManager.torState.value as? TorState.Running)?.onionAddress ?: "", recipientOnion = p.onionAddress, payloadData = data), timeoutMs = 30000)
                if (res.isSuccess) {
                    val pPK = E2EManager.stringToPublicKey(p.identityPublicKey, "X25519")
                    val shared = E2EManager.calculateSharedSecret(myEphemeralKeyPair.private, pPK)
                    activeSessions[p.onionAddress] = RatchetSession(shared)
                }
            } catch (e: Exception) { } finally { handshakeLoading[p.onionAddress] = false }
        }
    }

    private fun startPresenceLoop() {
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val my = (torManager.torState.value as? TorState.Running)?.onionAddress ?: ""
                if (my.isNotEmpty()) peersList.forEach {
                    val ping = NetworkPayload(type = PayloadType.PING, senderOnion = my, recipientOnion = it.onionAddress, payloadData = "")
                    val res = p2pMessenger.sendPayloadOverTor(it.onionAddress, ping, timeoutMs = 15000)
                    if (res.isFailure) {
                        val idx = peersList.indexOfFirst { p -> p.onionAddress == it.onionAddress }
                        if (idx != -1) withContext(Dispatchers.Main) { peersList[idx] = peersList[idx].copy(isOnline = false) }
                    }
                }; delay(60_000L)
            }
        }
    }

    private fun broadcastMyStatus(a: Boolean) {
        val my = (torManager.torState.value as? TorState.Running)?.onionAddress ?: ""
        if (my.isEmpty()) return
        peersList.forEach {
            val pay = NetworkPayload(type = PayloadType.PONG, senderOnion = my, recipientOnion = it.onionAddress, payloadData = if (a) "ONLINE" else "OFFLINE")
            CoroutineScope(Dispatchers.IO).launch { p2pMessenger.sendPayloadOverTor(it.onionAddress, pay, timeoutMs = 10000) }
        }
    }

    private fun checkAndRequestPermissions() {
        val p = mutableListOf(Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = p.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}
