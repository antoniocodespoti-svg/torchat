package com.p2p.torchat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditOnionDialog by remember { mutableStateOf(false) }
    var showEditAliasDialog by remember { mutableStateOf(false) }
    var peerToDelete by remember { mutableStateOf<Peer?>(null) }

    Scaffold(
        topBar = {
            HomeTopBar(
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme,
                onOpenQRScanner = onOpenQRScanner,
                onOpenQRCode = onOpenQRCode,
                onOpenSettings = onOpenSettings,
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
                TorStatusCard(
                    torState = torState,
                    myAlias = myAlias,
                    isAvailable = isAvailable,
                    isDarkTheme = isDarkTheme,
                    expiryDate = expiryDate,
                    myOnionAddress = myOnionAddress,
                    myPublicKey = myPublicKey,
                    onToggleAvailability = onToggleAvailability,
                    onEditAlias = { showEditAliasDialog = true },
                    onEditOnion = { showEditOnionDialog = true },
                )
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = "Conversazioni P2P (${peers.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (peers.isEmpty()) {
                item { EmptyPeersPlaceholder() }
            } else {
                items(peers) { peer ->
                    peerRowItem(
                        peer = peer,
                        unreadCount = unreadCounts[peer.onionAddress] ?: 0,
                        isDarkTheme = isDarkTheme,
                        onClick = { onSelectPeer(peer) },
                        onDelete = { peerToDelete = peer },
                    )
                }
            }

            item { HomeFooter() }
        }
    }

    if (showAddDialog) {
        AddPeerDialog(
            onDismiss = { showAddDialog = false },
            onAddPeer = onAddPeerDirect,
        )
    }

    if (showEditOnionDialog) {
        EditOnionDialog(
            currentOnion = myOnionAddress,
            onDismiss = { showEditOnionDialog = false },
            onUpdateOnion = onUpdateOnionAddress,
        )
    }

    if (peerToDelete != null) {
        DeletePeerDialog(
            peer = peerToDelete!!,
            onDismiss = { peerToDelete = null },
            onDelete = onDeletePeer,
        )
    }

    if (showEditAliasDialog) {
        EditAliasDialog(
            currentAlias = myAlias,
            onDismiss = { showEditAliasDialog = false },
            onUpdateAlias = onUpdateMyAlias,
        )
    }

    if (peerToConfirm != null) {
        ConfirmPeerDialog(
            peerToConfirm = peerToConfirm,
            onDismiss = onConfirmPeerHandled,
            onConfirm = onAddPeerDirect,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenQRScanner: () -> Unit,
    onOpenQRCode: () -> Unit,
    onOpenSettings: () -> Unit,
) {
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
}

@Composable
private fun TorStatusCard(
    torState: TorState,
    myAlias: String,
    isAvailable: Boolean,
    isDarkTheme: Boolean,
    expiryDate: Long,
    myOnionAddress: String,
    myPublicKey: String,
    onToggleAvailability: () -> Unit,
    onEditAlias: () -> Unit,
    onEditOnion: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (isDarkTheme) androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f)) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            TorStatusHeader(torState, myAlias, isAvailable, onToggleAvailability, onEditAlias)
            TorProgressIndicator(torState)
            TorStatusFooter(expiryDate, torState)
            OnionAddressSection(myOnionAddress, myAlias, myPublicKey, onEditOnion)
        }
    }
}

@Composable
private fun TorStatusHeader(
    torState: TorState,
    myAlias: String,
    isAvailable: Boolean,
    onToggleAvailability: () -> Unit,
    onEditAlias: () -> Unit,
) {
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
                Text(text = myAlias, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                AvailabilityStatus(isAvailable)
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
            IconButton(onClick = onEditAlias) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Modifica Nome",
                    tint = CyanPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun AvailabilityStatus(isAvailable: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .background(if (isAvailable) GreenAccent else Color.Gray, RoundedCornerShape(50)),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (isAvailable) "Disponibile" else "Non Disponibile",
            style = MaterialTheme.typography.labelSmall,
            color = if (isAvailable) GreenAccent else Color.Gray,
        )
    }
}

@Composable
private fun TorProgressIndicator(torState: TorState) {
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
}

@Composable
private fun TorStatusFooter(
    expiryDate: Long,
    torState: TorState,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val remainingDays = maxOf(0L, (expiryDate - System.currentTimeMillis()) / (24 * 60 * 60 * 1000))
        val barColor =
            when (torState) {
                is TorState.Running -> GreenAccent
                is TorState.Error -> RedAccent
                else -> NeonCyan
            }
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
}

@Composable
private fun OnionAddressSection(
    myOnionAddress: String,
    myAlias: String,
    myPublicKey: String,
    onEditOnion: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Spacer(modifier = Modifier.height(12.dp))
    Text(text = "Il Tuo Indirizzo .onion:", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (myOnionAddress.isNotBlank()) myOnionAddress else "Generazione in corso...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { if (myOnionAddress.isNotBlank()) clipboardManager.setText(AnnotatedString(myOnionAddress)) },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text("COPIA", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            TextButton(
                onClick = {
                    val fullIdentity = "$myOnionAddress|$myAlias|$myPublicKey"
                    clipboardManager.setText(AnnotatedString(fullIdentity))
                    android.widget.Toast.makeText(context, "Identità Completa Copiata!", android.widget.Toast.LENGTH_SHORT).show()
                },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text("ID COMPLETO", style = MaterialTheme.typography.labelSmall, color = NeonMagenta, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onEditOnion, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(30.dp)) {
                Text("MODIFICA", style = MaterialTheme.typography.labelLarge, color = CyanPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EmptyPeersPlaceholder() {
    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text("Nessun contatto aggiunto. Scansiona un QR per iniziare!", color = Color.Gray, textAlign = TextAlign.Center)
    }
}

@Composable
private fun HomeFooter() {
    Spacer(modifier = Modifier.height(32.dp))
    Text(
        text = "TorP2P Chat v1.0.2 - Stable Edition",
        style = MaterialTheme.typography.labelSmall,
        color = Color.Gray.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun AddPeerDialog(
    onDismiss: () -> Unit,
    onAddPeer: (String, String, String) -> Unit,
) {
    var alias by remember { mutableStateOf("") }
    var onion by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aggiungi Peer P2P Manualmente") },
        text = {
            Column {
                OutlinedTextField(value = alias, onValueChange = { alias = it }, label = { Text("Alias / Nome") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = onion, onValueChange = { onion = it }, label = { Text("Indirizzo .onion del Peer") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = {
                if (alias.isNotBlank() && onion.isNotBlank()) {
                    onAddPeer(alias.trim(), onion.trim(), "")
                    onDismiss()
                }
            }) { Text("Aggiungi") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun EditOnionDialog(
    currentOnion: String,
    onDismiss: () -> Unit,
    onUpdateOnion: (String) -> Unit,
) {
    var onion by remember { mutableStateOf(currentOnion) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Imposta Indirizzo Orbot (.onion)") },
        text = {
            Column {
                Text("Incolla qui l'indirizzo .onion generato da Orbot per questo dispositivo:")
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = onion, onValueChange = { onion = it }, label = { Text("Tuo Indirizzo Tor Reale") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (onion.isNotBlank()) {
                    onUpdateOnion(onion.trim())
                    onDismiss()
                }
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun DeletePeerDialog(
    peer: Peer,
    onDismiss: () -> Unit,
    onDelete: (Peer) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Elimina Contatto") },
        text = { Text("Sei sicuro di voler eliminare '${peer.alias}'? La cronologia messaggi in memoria andrà persa.") },
        confirmButton = {
            Button(onClick = {
                onDelete(peer)
                onDismiss()
            }, colors = ButtonDefaults.buttonColors(containerColor = RedAccent)) { Text("Elimina") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun EditAliasDialog(
    currentAlias: String,
    onDismiss: () -> Unit,
    onUpdateAlias: (String) -> Unit,
) {
    var alias by remember { mutableStateOf(currentAlias) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifica Il Tuo Alias") },
        text = {
            Column {
                Text("Scegli come vuoi essere visto dai tuoi amici:")
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = alias, onValueChange = { alias = it }, label = { Text("Tuo Nome / Alias") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (alias.isNotBlank()) {
                    onUpdateAlias(alias.trim())
                    onDismiss()
                }
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun ConfirmPeerDialog(
    peerToConfirm: Triple<String, String, String>,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var alias by remember { mutableStateOf(peerToConfirm.second) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aggiungi Amico") },
        text = {
            Column {
                Text("Vuoi aggiungere questo contatto? Puoi modificare il nome suggerito:")
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = alias, onValueChange = { alias = it }, label = { Text("Nome Amico") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Indirizzo: ${peerToConfirm.first.take(20)}...", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (alias.isNotBlank()) {
                    onConfirm(alias.trim(), peerToConfirm.first, peerToConfirm.third)
                    onDismiss()
                }
            }) { Text("Aggiungi") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
fun peerRowItem(
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
