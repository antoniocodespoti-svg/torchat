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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.p2p.torchat.crypto.E2EManager
import com.p2p.torchat.crypto.MnemonicManager
import com.p2p.torchat.crypto.RatchetSession
import com.p2p.torchat.model.*
import com.p2p.torchat.service.*
import com.p2p.torchat.ui.screens.*
import com.p2p.torchat.ui.theme.TorP2PChatTheme
import com.p2p.torchat.util.Constants
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val TAG = Constants.TAG
    private lateinit var torManager: TorManager
    private lateinit var p2pMessenger: P2PMessenger
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var backupManager: BackupManager
    private lateinit var mediaManager: MediaManager
    private val totpManager = TotpManager()
    private val timeFetcher = NetworkTimeFetcher()

    private var myIdentityKeyPair: KeyPair? = null
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
    private val orbotLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) res.data?.getStringExtra("onion_address")?.let { torManager.setTorRunning(it) }
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        initializeSystem()
        loadPreferences()

        val localServer = LocalServer(port = Constants.LOCAL_SERVER_PORT, onMessageReceived = { handleIncomingPayload(it) })
        localServer.startServer()

        if (torManager.isOrbotInstalled()) {
            val s = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).getString(Constants.KEY_ONION, null)
            if (s != null) torManager.setTorRunning(s) else orbotLauncher.launch(torManager.getOrbotRequestIntent())
        }

        setContent {
            TorP2PChatTheme(darkTheme = isDarkTheme) {
                val torState by torManager.torState.collectAsState()
                val myOnion = (torState as? TorState.Running)?.onionAddress ?: ""

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (val screen = currentScreenState) {
                        is Screen.Auth -> AuthScreen(
                            mode = if (savedPasswordHash == null) AuthMode.CREATE else AuthMode.LOGIN,
                            attemptsLeft = Constants.MAX_AUTH_ATTEMPTS - failedAttempts,
                            onAuthSuccess = { handleAuthResult(it) }
                        )
                        is Screen.Subscription -> SubscriptionScreen(myOnion) { handleSubscription(it, myOnion) }
                        is Screen.Home -> HomeScreen(
                            torState = torState,
                            myOnionAddress = myOnion,
                            myAlias = myAlias,
                            myPublicKey = myIdentityKeyPair?.let { E2EManager.publicKeyToString(it.public) } ?: "",
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
                            ChatScreen(
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
                        is Screen.Settings -> SettingsScreen(
                            onExportBackup = { handleExportInternal() },
                            onImportBackup = { importBackupLauncher.launch(arrayOf("application/json")) },
                            isAutoBackupEnabled = isAutoBackupEnabled,
                            onToggleAutoBackup = { isAutoBackupEnabled = it; saveAutoBackupPreference(it) },
                            onChangePassword = { currentScreenState = Screen.ChangePassword },
                            onExtendLicense = { currentScreenState = Screen.Subscription },
                            expiryDate = expiryDate,
                            onOpenInfo = { currentScreenState = Screen.Info },
                            onOpenTerms = { currentScreenState = Screen.TermsOfUse },
                            onBack = { currentScreenState = Screen.Home }
                        )
                        is Screen.QRCode -> QRCodeScreen(myOnion, myAlias, myIdentityKeyPair?.let { E2EManager.publicKeyToString(it.public) } ?: "") { currentScreenState = Screen.Home }
                        is Screen.Verification -> VerificationScreen(screen.peer, { handleVerifyPeer(screen.peer) }) { currentScreenState = Screen.Chat(screen.peer) }
                        is Screen.QRScanner -> ClientQRScannerScreen({ handleQRScan(it) }) { currentScreenState = Screen.Home }
                        is Screen.TermsOfUse -> TermsOfUseScreen(isViewOnly = isTermsAccepted, onAccept = { handleTermsAccept() }) { currentScreenState = Screen.Settings }
                        is Screen.Info -> InfoScreen { currentScreenState = Screen.Settings }
                        is Screen.ChangePassword -> AuthScreen(AuthMode.CHANGE, onAuthSuccess = { handleChangePassword(it) }) { currentScreenState = Screen.Settings }
                        is Screen.SeedBackup -> {
                            if (currentSeed.isEmpty()) currentSeed = MnemonicManager.generateMnemonic()
                            SeedScreen(SeedMode.DISPLAY, currentSeed, { handleExportInternal() }, { currentScreenState = Screen.Home }, { currentScreenState = Screen.Home }, { handleRemoveSeed() })
                        }
                        is Screen.SeedRestore -> SeedScreen(SeedMode.INPUT, emptyList<String>(), { handleSeedRestore(it) }, { currentScreenState = Screen.Settings })
                    }
                }
            }
        }

        checkAndRequestPermissions()
    }

    private fun handleAuthResult(password: String): Boolean {
        if (E2EManager.verifyPassword(password, savedPasswordHash ?: "")) {
            isAuthenticated = true; failedAttempts = 0; saveFailedAttempts(0)
            currentScreenState = Screen.Home
            return true
        }
        failedAttempts++
        saveFailedAttempts(failedAttempts)
        if (failedAttempts >= Constants.MAX_AUTH_ATTEMPTS) performWipe()
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
                    Toast.makeText(this@MainActivity, "OK", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleAddPeer(alias: String, onion: String, pubKey: String) {
        val clean = sanitizeOnionAddress(onion)
        if (peersList.any { it.onionAddress == clean }) {
            Toast.makeText(this, "ESISTE", Toast.LENGTH_SHORT).show()
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
        }
        currentScreenState = Screen.Home
    }

    private fun handleQRScan(data: String) {
        val p = data.split("|")
        peerToConfirmWithKey = Triple(sanitizeOnionAddress(p[0]), if (p.size > 1) p[1] else "Amico", if (p.size > 2) p[2] else "")
        currentScreenState = Screen.Home
    }

    private fun handleTermsAccept() {
        isTermsAccepted = true
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(Constants.KEY_TERMS_ACCEPTED, true).apply()
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
            p2pMessenger.sendPayloadOverTor(peer.onionAddress, NetworkPayload(type = PayloadType.SESSION_TERMINATE, senderOnion = my, recipientOnion = peer.onionAddress, payloadData = "TERMINATE"))
        }
        messages.clear()
        activeSessions.remove(peer.onionAddress)
        handshakeLoading.remove(peer.onionAddress)
        currentScreenState = Screen.Home
    }

    private fun handleRemoveSeed() {
        if (currentSeed.isNotEmpty()) {
            getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().remove(Constants.KEY_SAVED_SEED).apply()
            currentSeed = emptyList()
            Toast.makeText(this, "RIMOSSO", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleExportInternal() {
        exportBackupLauncher.launch("torchat_backup.json")
    }

    private fun handleExport(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val salt = getOrCreateSalt()
            val backupJson = backupManager.createEncryptedBackupJson(currentSeed, salt)
            contentResolver.openOutputStream(uri)?.use { it.write(backupJson.toByteArray()) }
            Toast.makeText(this, "OK", Toast.LENGTH_SHORT).show()
            currentScreenState = Screen.Home
        } catch (e: Exception) {
            Log.e(TAG, "Export error", e)
        }
    }

    private fun handleImport(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { i ->
                val pkg = BufferedReader(InputStreamReader(i)).readText()
                if (backupManager.restoreFromEncryptedBackup(pkg, currentSeed)) {
                    finish(); startActivity(intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Import error", e)
        }
    }

    private fun handleIncomingPayload(payload: NetworkPayload) {
        CoroutineScope(Dispatchers.Main).launch {
            when (payload.type) {
                PayloadType.CHAT_MESSAGE, PayloadType.IMAGE, PayloadType.FILE -> {
                    if (isAvailable) try {
                        val session = activeSessions[payload.senderOnion]
                        if (session != null) {
                            val messageKey = session.nextReceiveKey(payload.sequenceNumber)
                            val aad = E2EManager.buildAAD(1, payload.type.ordinal.toByte(), payload.sequenceNumber, payload.senderOnion)
                            val dec = E2EManager.decryptV2(payload.payloadData, messageKey, aad)

                            val incomingMsg = Message(
                                id = payload.id,
                                senderOnion = payload.senderOnion,
                                recipientOnion = payload.recipientOnion,
                                content = dec,
                                timestamp = payload.timestamp,
                                isOutgoing = false,
                                isDelivered = true,
                                isError = false,
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
                    } catch (e: Exception) { Log.e(TAG, "Decryption Error", e) }
                }
                PayloadType.SESSION_HANDSHAKE -> if (isAvailable) handleHandshakeReceived(payload)
                PayloadType.SESSION_TERMINATE -> {
                    activeSessions.remove(payload.senderOnion)
                    messagesMap[payload.senderOnion]?.clear()
                }
                PayloadType.PING -> {
                    val my = (torManager.torState.value as? TorState.Running)?.onionAddress ?: ""
                    launch(Dispatchers.IO) {
                        p2pMessenger.sendPayloadOverTor(payload.senderOnion, NetworkPayload(id = UUID.randomUUID().toString(), type = PayloadType.PONG, senderOnion = my, recipientOnion = payload.senderOnion, payloadData = if (isAvailable) "ONLINE" else "OFFLINE"), timeoutMs = 10000)
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
            val peerIdentityKey = E2EManager.stringToPublicKey(pts[2], Constants.ED25519_ALGO)
            if (existing.identityPublicKey.isNotEmpty() && existing.identityPublicKey != pts[2]) return
            if (!E2EManager.verifyHandshake(pts[0], pts[1], peerIdentityKey)) return
            if (existing.identityPublicKey.isEmpty()) {
                val idx = peersList.indexOf(existing)
                if (idx != -1) { peersList[idx] = existing.copy(identityPublicKey = pts[2]); savePeers() }
            }
            val peerEphemeralPubKey = E2EManager.stringToPublicKey(pts[0], Constants.X25519_ALGO)
            val sharedSecret = E2EManager.calculateSharedSecret(myIdentityKeyPair!!.private, peerEphemeralPubKey)
            activeSessions[p.senderOnion] = RatchetSession(sharedSecret)
            handshakeLoading[p.senderOnion] = false
        } catch (e: Exception) { Log.e(TAG, "Handshake error", e) }
    }

    private fun initializeSystem() {
        torManager = TorManager(this)
        p2pMessenger = P2PMessenger()
        notificationHelper = NotificationHelper(this)
        backupManager = BackupManager(this)
        mediaManager = MediaManager(this)
    }

    private fun loadPreferences() {
        val p = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        myAlias = p.getString(Constants.KEY_MY_ALIAS, "Amico") ?: "Amico"
        isDarkTheme = p.getBoolean(Constants.KEY_DARK_THEME, true)
        isAutoBackupEnabled = p.getBoolean(Constants.KEY_AUTO_BACKUP, false)
        isTermsAccepted = p.getBoolean(Constants.KEY_TERMS_ACCEPTED, false)
        savedPasswordHash = p.getString(Constants.KEY_PASS_HASH, null)
        failedAttempts = p.getInt(Constants.KEY_FAILED_ATTEMPTS, 0)
        expiryDate = p.getLong(Constants.KEY_EXPIRY, 0L)
        currentScreenState = if (!isTermsAccepted) Screen.TermsOfUse else Screen.Auth
        loadOrGenerateIdentityKeys(p)
        peersList.clear()
        peersList.addAll(loadPeersFromPrefs(p))
    }

    private fun sendMessage(peer: Peer, content: String, messageList: MutableList<Message>) {
        if (handshakeLoading[peer.onionAddress] == true) return
        val session = activeSessions[peer.onionAddress] ?: return initiateHandshake(peer)
        val messageKey = session.nextSendKey()
        val myOnion = (torManager.torState.value as? TorState.Running)?.onionAddress ?: ""
        val aad = E2EManager.buildAAD(1, PayloadType.CHAT_MESSAGE.ordinal.toByte(), session.sendSequence, myOnion)
        val encrypted = E2EManager.encryptV2(content, messageKey, aad)
        val msg = Message(id = UUID.randomUUID().toString(), senderOnion = myOnion, recipientOnion = peer.onionAddress, content = content, timestamp = System.currentTimeMillis(), isOutgoing = true, isDelivered = false, isError = false, type = PayloadType.CHAT_MESSAGE, attachment = null, sequenceNumber = session.sendSequence)
        messageList.add(msg)
        CoroutineScope(Dispatchers.IO).launch {
            val payload = NetworkPayload(id = msg.id, type = PayloadType.CHAT_MESSAGE, senderOnion = myOnion, recipientOnion = peer.onionAddress, payloadData = encrypted, sequenceNumber = session.sendSequence)
            val res = p2pMessenger.sendPayloadOverTor(peer.onionAddress, payload)
            val idx = messageList.indexOf(msg)
            if (idx != -1) withContext(Dispatchers.Main) { messageList[idx] = msg.copy(isDelivered = res.isSuccess, isError = !res.isSuccess) }
        }
    }

    private fun handleMediaPick(u: Uri, i: Boolean) {
        val p = activeChatPeer ?: return
        val m = activeChatMessages ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val b = if (i) mediaManager.stripImageMetadata(u) else mediaManager.getFileBytes(u)
            b?.let {
                val b64 = Base64.getEncoder().encodeToString(it)
                withContext(Dispatchers.Main) { sendMessage(p, b64, m) }
            }
        }
    }

    private fun saveAttachmentToExternalStorage(f: String, b: String) {
        try {
            val bts = Base64.getDecoder().decode(b)
            val v = ContentValues().apply { put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f) }
            val uri = contentResolver.insert(if (Build.VERSION.SDK_INT >= 29) android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Files.getContentUri("external"), v)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os -> os.write(bts) }
                CoroutineScope(Dispatchers.Main).launch { Toast.makeText(this@MainActivity, "OK", Toast.LENGTH_SHORT).show() }
            }
        } catch (e: Exception) { Log.e(TAG, "Save error", e) }
    }

    private fun sanitizeOnionAddress(o: String): String = o.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")

    private fun loadPeersFromPrefs(p: android.content.SharedPreferences): List<Peer> {
        val d = p.getString(Constants.KEY_SAVED_PEERS, null) ?: return emptyList()
        return try {
            val json = if (d.startsWith("[")) d else {
                val h = p.getString(Constants.KEY_PASS_HASH, null)
                if (h != null) E2EManager.decrypt(d, E2EManager.deriveKeyFromSecret(h)) else d
            }
            Gson().fromJson(json, object : TypeToken<List<Peer>>() {}.type)
        } catch (e: Exception) { emptyList() }
    }

    private fun savePeers() {
        val p = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val j = Gson().toJson(peersList.toList())
        try {
            val h = p.getString(Constants.KEY_PASS_HASH, null)
            val data = if (h != null) E2EManager.encrypt(j, E2EManager.deriveKeyFromSecret(h)) else j
            p.edit().putString(Constants.KEY_SAVED_PEERS, data).apply()
        } catch (e: Exception) {
            p.edit().putString(Constants.KEY_SAVED_PEERS, j).apply()
        }
    }

    private fun getOrCreateSalt(): ByteArray {
        val saltPrefs = getSharedPreferences("secure_prefs_salt", Context.MODE_PRIVATE)
        val sEnc = saltPrefs.getString("install_salt_enc", null) ?: return generateAndSaveSalt(saltPrefs)
        return try { Base64.getDecoder().decode(E2EManager.decryptWithHardwareKey(sEnc)) } catch (e: Exception) { generateAndSaveSalt(saltPrefs) }
    }

    private fun generateAndSaveSalt(p: android.content.SharedPreferences): ByteArray {
        val s = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val enc = E2EManager.encryptWithHardwareKey(Base64.getEncoder().encodeToString(s))
        p.edit().putString("install_salt_enc", enc).apply()
        return s
    }

    private fun saveThemePreference(d: Boolean) { getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(Constants.KEY_DARK_THEME, d).apply() }
    private fun saveAvailabilityPreference(a: Boolean) { getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean("is_available", a).apply() }
    private fun savePasswordHash(h: String) { getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().putString(Constants.KEY_PASS_HASH, h).apply() }
    private fun saveFailedAttempts(a: Int) { getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(Constants.KEY_FAILED_ATTEMPTS, a).apply() }
    private fun saveExpiryDate(d: Long) { getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().putLong(Constants.KEY_EXPIRY, d).apply() }
    private fun saveAutoBackupPreference(e: Boolean) { getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(Constants.KEY_AUTO_BACKUP, e).apply() }
    private fun saveMyAlias(a: String) { getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().putString(Constants.KEY_MY_ALIAS, a).apply() }

    private fun performWipe() {
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        try { E2EManager.deleteMasterKey() } catch (e: Exception) { }
        val d = File(filesDir, "tor")
        if (d.exists()) d.deleteRecursively()
        System.exit(0)
    }

    private fun loadOrGenerateIdentityKeys(p: android.content.SharedPreferences) {
        val pub = p.getString(Constants.KEY_PUBLIC_KEY, null)
        val privEnc = p.getString(Constants.KEY_PRIVATE_KEY_ENC, null)
        if (pub != null && privEnc != null) try {
            val priv = E2EManager.decryptWithHardwareKey(privEnc)
            val f = KeyFactory.getInstance(Constants.ED25519_ALGO)
            myIdentityKeyPair = KeyPair(
                f.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pub))),
                f.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(priv)))
            )
        } catch (e: Exception) { generateNewIdentityKeys(p) }
        else generateNewIdentityKeys(p)
    }

    private fun generateNewIdentityKeys(p: android.content.SharedPreferences) {
        myIdentityKeyPair = E2EManager.generateIdentityKeyPair()
        val priv = Base64.getEncoder().encodeToString(myIdentityKeyPair!!.private.encoded)
        val enc = E2EManager.encryptWithHardwareKey(priv)
        p.edit().apply {
            putString(Constants.KEY_PUBLIC_KEY, E2EManager.publicKeyToString(myIdentityKeyPair!!.public))
            putString(Constants.KEY_PRIVATE_KEY_ENC, enc)
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
                val sig = E2EManager.signHandshake(eKStr, myIdentityKeyPair!!.private)
                val myIDStr = E2EManager.publicKeyToString(myIdentityKeyPair!!.public)
                val data = "$eKStr|$sig|$myIDStr|PFS"
                val res = p2pMessenger.sendPayloadOverTor(p.onionAddress, NetworkPayload(id = UUID.randomUUID().toString(), type = PayloadType.SESSION_HANDSHAKE, senderOnion = (torManager.torState.value as? TorState.Running)?.onionAddress ?: "", recipientOnion = p.onionAddress, payloadData = data), timeoutMs = 30000)
                if (res.isSuccess) {
                    val pPK = E2EManager.stringToPublicKey(p.identityPublicKey, Constants.X25519_ALGO)
                    val shared = E2EManager.calculateSharedSecret(myIdentityKeyPair!!.private, pPK)
                    activeSessions[p.onionAddress] = RatchetSession(shared)
                }
            } catch (e: Exception) { Log.e(TAG, "Handshake error", e) } finally { handshakeLoading[p.onionAddress] = false }
        }
    }

    private fun broadcastMyStatus(a: Boolean) {
        val my = (torManager.torState.value as? TorState.Running)?.onionAddress ?: ""
        if (my.isEmpty()) return
        peersList.forEach {
            val pay = NetworkPayload(id = UUID.randomUUID().toString(), type = PayloadType.PONG, senderOnion = my, recipientOnion = it.onionAddress, payloadData = if (a) "ONLINE" else "OFFLINE")
            CoroutineScope(Dispatchers.IO).launch { p2pMessenger.sendPayloadOverTor(it.onionAddress, pay, timeoutMs = 10000) }
        }
    }

    private fun checkAndRequestPermissions() {
        val p = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = p.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}
