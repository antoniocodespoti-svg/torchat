package com.p2p.torchat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.p2p.torchat.model.Peer
import com.p2p.torchat.service.TorState
import com.p2p.torchat.ui.theme.CyanPrimary
import com.p2p.torchat.ui.theme.GreenAccent
import com.p2p.torchat.ui.theme.NeonCyan
import com.p2p.torchat.ui.theme.NeonMagenta
import com.p2p.torchat.ui.theme.RedAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    torState: TorState,
    myOnionAddress: String,
    myAlias: String,
    myPublicKey: String,
    isDarkTheme: Boolean,
    isAvailable: Boolean,
    expiryDate: Long,
    peers: List<Peer>,
    unreadCounts: Map<String, Int>,
    peerToConfirm: Triple<String, String, String>? = null,
    onToggleTheme: () -> Unit,
    onToggleAvailability: () -> Unit,
    onUpdateMyAlias: (String) -> Unit,
    onAddPeerDirect: (String, String, String) -> Unit,
    onSelectPeer: (Peer) -> Unit,
    onOpenQRCode: () -> Unit,
    onOpenQRScanner: () -> Unit,
    onOpenSettings: () -> Unit,
    onUpdateOnionAddress: (String) -> Unit,
    onDeletePeer: (Peer) -> Unit,
    onConfirmPeerHandled: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val toastContext = android.widget.Toast.makeText(context, "", android.widget.Toast.LENGTH_SHORT).let { context } // Hack to import Toast
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditOnionDialog by remember { mutableStateOf(false) }
    var showEditAliasDialog by remember { mutableStateOf(false) }
    var peerToDelete by remember { mutableStateOf<Peer?>(null) }

    var peerAliasInput by remember { mutableStateOf("") }
    var peerOnionInput by remember { mutableStateOf("") }

    var editOnionInput by remember { mutableStateOf(myOnionAddress) }
    var editAliasInput by remember { mutableStateOf(myAlias) }

    var confirmPeerAliasInput by remember { mutableStateOf("") }

    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tor P2P Chat", fontWeight = FontWeight.ExtraBold) },
                actions = {
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Tema",
                            tint = if (isDarkTheme) NeonCyan else Color.Unspecified,
                        )
                    }
                    IconButton(onClick = onOpenQRScanner) {
                        Icon(imageVector = Icons.Default.QrCodeScanner, contentDescription = "Scansiona QR")
                    }
                    IconButton(onClick = onOpenQRCode) {
                        Icon(imageVector = Icons.Default.QrCode, contentDescription = "QR Code")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Impostazioni")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = CyanPrimary,
                contentColor = Color.White,
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Aggiungi Peer")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                // Tor Status Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border =
                        if (isDarkTheme) {
                            androidx.compose.foundation.BorderStroke(
                                1.dp,
                                NeonCyan.copy(alpha = 0.5f),
                            )
                        } else {
                            null
                        },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = "Tor Status",
                                    tint =
                                        when (torState) {
                                            is TorState.Running -> GreenAccent
                                            is TorState.Starting -> CyanPrimary
                                            else -> RedAccent
                                        },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = myAlias,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .size(8.dp)
                                                    .background(
                                                        if (isAvailable) GreenAccent else Color.Gray,
                                                        RoundedCornerShape(50),
                                                    ),
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isAvailable) "Disponibile" else "Non Disponibile",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isAvailable) GreenAccent else Color.Gray,
                                        )
                                    }
                                }
                            }
                            Row {
                                IconButton(onClick = onToggleAvailability) {
                                    Icon(
                                        imageVector = if (isAvailable) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = "Cambia Stato",
                                        tint = if (isAvailable) GreenAccent else Color.Gray,
                                    )
                                }
                                IconButton(onClick = {
                                    editAliasInput = myAlias
                                    showEditAliasDialog = true
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Modifica Nome",
                                        tint = CyanPrimary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }

                        // PERMANENT CYBER-BAR TOR PROGRESS
                        val progress =
                            when (torState) {
                                is TorState.Starting -> 0.5f
                                is TorState.Running -> 1.0f
                                else -> 0f
                            }
                        val barColor =
                            when (torState) {
                                is TorState.Running -> GreenAccent
                                is TorState.Error -> RedAccent
                                else -> NeonCyan
                            }

                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = barColor,
                            trackColor = Color.DarkGray.copy(alpha = 0.2f),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val remainingDays = maxOf(0L, (expiryDate - System.currentTimeMillis()) / (24 * 60 * 60 * 1000))
                            Text(
                                text =
                                    when (torState) {
                                        is TorState.Running -> "Tor Connesso & Active v3 Service"
                                        is TorState.Starting -> "Avvio Circuito Tor..."
                                        is TorState.Stopped -> "Tor Inattivo"
                                        is TorState.Error -> "Errore Tor"
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = barColor.copy(alpha = 0.8f),
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "Licenza: $remainingDays gg",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (remainingDays <= 3) RedAccent else GreenAccent,
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Il Tuo Indirizzo .onion:",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Gray,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (myOnionAddress.isNotBlank()) myOnionAddress else "Generazione in corso...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(
                                    onClick = {
                                        if (myOnionAddress.isNotBlank()) {
                                            clipboardManager.setText(AnnotatedString(myOnionAddress))
                                        }
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.height(30.dp),
                                ) {
                                    Text(
                                        "COPIA",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        val fullIdentity = "$myOnionAddress|$myAlias|$myPublicKey"
                                        clipboardManager.setText(AnnotatedString(fullIdentity))
                                        android.widget.Toast.makeText(
                                            context,
                                            "Identità Completa Copiata!",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.height(30.dp),
                                ) {
                                    Text(
                                        "ID COMPLETO",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NeonMagenta,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        editOnionInput = myOnionAddress
                                        showEditOnionDialog = true
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.height(30.dp),
                                ) {
                                    Text(
                                        "MODIFICA",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = CyanPrimary,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "Conversazioni P2P (${peers.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            if (peers.isEmpty()) {
                item {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Nessun contatto aggiunto. Scansiona un QR per iniziare!",
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                items(peers) { peer ->
                    PeerRowItem(
                        peer = peer,
                        unreadCount = unreadCounts[peer.onionAddress] ?: 0,
                        isDarkTheme = isDarkTheme,
                        onClick = { onSelectPeer(peer) },
                        onDelete = { peerToDelete = peer },
                    )
                }
            }

            item {
                // VERSION FOOTER
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = "TorP2P Chat v1.0.2 - Stable Edition",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray.copy(alpha = 0.5f),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Aggiungi Peer P2P Manualmente") },
            text = {
                Column {
                    OutlinedTextField(
                        value = peerAliasInput,
                        onValueChange = { peerAliasInput = it },
                        label = { Text("Alias / Nome") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = peerOnionInput,
                        onValueChange = { peerOnionInput = it },
                        label = { Text("Indirizzo .onion del Peer") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (peerAliasInput.isNotBlank() && peerOnionInput.isNotBlank()) {
                            onAddPeerDirect(peerAliasInput.trim(), peerOnionInput.trim(), "")
                            showAddDialog = false
                            peerAliasInput = ""
                            peerOnionInput = ""
                        }
                    },
                ) {
                    Text("Aggiungi")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Annulla")
                }
            },
        )
    }

    if (showEditOnionDialog) {
        AlertDialog(
            onDismissRequest = { showEditOnionDialog = false },
            title = { Text("Imposta Indirizzo Orbot (.onion)") },
            text = {
                Column {
                    Text("Incolla qui l'indirizzo .onion generato da Orbot per questo dispositivo:")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editOnionInput,
                        onValueChange = { editOnionInput = it },
                        label = { Text("Tuo Indirizzo Tor Reale") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editOnionInput.isNotBlank()) {
                            onUpdateOnionAddress(editOnionInput.trim())
                            showEditOnionDialog = false
                        }
                    },
                ) {
                    Text("Salva")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditOnionDialog = false }) {
                    Text("Annulla")
                }
            },
        )
    }

    if (peerToDelete != null) {
        AlertDialog(
            onDismissRequest = { peerToDelete = null },
            title = { Text("Elimina Contatto") },
            text = { Text("Sei sicuro di voler eliminare '${peerToDelete?.alias}'? La cronologia messaggi in memoria andrà persa.") },
            confirmButton = {
                Button(
                    onClick = {
                        peerToDelete?.let { onDeletePeer(it) }
                        peerToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedAccent),
                ) {
                    Text("Elimina")
                }
            },
            dismissButton = {
                TextButton(onClick = { peerToDelete = null }) {
                    Text("Annulla")
                }
            },
        )
    }

    if (showEditAliasDialog) {
        AlertDialog(
            onDismissRequest = { showEditAliasDialog = false },
            title = { Text("Modifica Il Tuo Alias") },
            text = {
                Column {
                    Text("Scegli come vuoi essere visto dai tuoi amici:")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editAliasInput,
                        onValueChange = { editAliasInput = it },
                        label = { Text("Tuo Nome / Alias") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editAliasInput.isNotBlank()) {
                            onUpdateMyAlias(editAliasInput.trim())
                            showEditAliasDialog = false
                        }
                    },
                ) {
                    Text("Salva")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditAliasDialog = false }) {
                    Text("Annulla")
                }
            },
        )
    }

    if (peerToConfirm != null) {
        LaunchedEffect(peerToConfirm) {
            confirmPeerAliasInput = peerToConfirm.second
        }

        AlertDialog(
            onDismissRequest = onConfirmPeerHandled,
            title = { Text("Aggiungi Amico") },
            text = {
                Column {
                    Text("Vuoi aggiungere questo contatto? Puoi modificare il nome suggerito:")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = confirmPeerAliasInput,
                        onValueChange = { confirmPeerAliasInput = it },
                        label = { Text("Nome Amico") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Indirizzo: ${peerToConfirm.first.take(20)}...",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (confirmPeerAliasInput.isNotBlank()) {
                            onAddPeerDirect(confirmPeerAliasInput.trim(), peerToConfirm.first, peerToConfirm.third)
                            onConfirmPeerHandled()
                        }
                    },
                ) {
                    Text("Aggiungi")
                }
            },
            dismissButton = {
                TextButton(onClick = onConfirmPeerHandled) {
                    Text("Annulla")
                }
            },
        )
    }
}

@Composable
fun PeerRowItem(
    peer: Peer,
    unreadCount: Int,
    isDarkTheme: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border =
            if (isDarkTheme) {
                androidx.compose.foundation.BorderStroke(
                    0.5.dp,
                    if (peer.isOnline) GreenAccent.copy(alpha = 0.6f) else NeonMagenta.copy(alpha = 0.3f),
                )
            } else {
                null
            },
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable(onClick = onClick),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                if (peer.isOnline) {
                    Box(
                        modifier =
                            Modifier
                                .size(10.dp)
                                .background(GreenAccent, RoundedCornerShape(50))
                                .padding(end = 12.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = peer.alias, fontWeight = FontWeight.Bold)
                        if (peer.isOnline) {
                            Text(
                                text = " • Online",
                                style = MaterialTheme.typography.labelSmall,
                                color = GreenAccent,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                    Text(text = peer.onionAddress, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }

                if (unreadCount > 0) {
                    Surface(
                        color = RedAccent,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(
                            text = unreadCount.toString(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Elimina",
                    tint = RedAccent.copy(alpha = 0.6f),
                )
            }
        }
    }
}
