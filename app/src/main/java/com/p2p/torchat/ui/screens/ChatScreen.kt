package com.p2p.torchat.ui.screens

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.p2p.torchat.model.Message
import com.p2p.torchat.model.PayloadType
import com.p2p.torchat.model.Peer
import com.p2p.torchat.ui.theme.CyanPrimary
import com.p2p.torchat.ui.theme.GreenAccent
import com.p2p.torchat.ui.theme.NeonCyan
import com.p2p.torchat.ui.theme.RedAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    peer: Peer,
    messages: List<Message>,
    isHandshakeLoading: Boolean,
    onSendMessage: (String) -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onSaveAttachment: (String, String) -> Unit,
    onDeleteSession: () -> Unit,
    onOpenVerification: () -> Unit,
    onBack: () -> Unit,
) {
    var inputText by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var fullScreenImage by remember { mutableStateOf<ByteArray?>(null) }
    val isDark = MaterialTheme.colorScheme.background == Color.Black

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(isHandshakeLoading) {
        if (isHandshakeLoading) {
            snackbarHostState.showSnackbar(
                message = "Negoziazione chiave sicura in corso...",
                duration = SnackbarDuration.Indefinite,
            )
        } else {
            snackbarHostState.currentSnackbarData?.dismiss()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { onOpenVerification() }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = peer.alias)
                            if (peer.isVerified) {
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.VerifiedUser, null, tint = GreenAccent, modifier = Modifier.size(16.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (peer.isVerified) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = "Security Status",
                                tint = if (peer.isVerified) GreenAccent else Color.Yellow,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (peer.isVerified) "E2EE Tor P2P | VERIFICATO" else "E2EE Tor P2P | NON VERIFICATO",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (peer.isVerified) Color.Gray else Color.Yellow,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = "Elimina Sessione",
                            tint = RedAccent,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            LazyColumn(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                reverseLayout = true,
            ) {
                items(messages.reversed()) { msg ->
                    MessageBubble(
                        message = msg,
                        onImageClick = { fullScreenImage = it },
                        onDownloadClick = { fileName, base64Data ->
                            onSaveAttachment(fileName, base64Data)
                        },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Attachment Options Menu
            if (showAttachmentMenu) {
                Surface(
                    tonalElevation = 8.dp,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                    ) {
                        AttachmentOption(Icons.Default.Image, "Galleria", NeonCyan) {
                            showAttachmentMenu = false
                            onPickImage()
                        }
                        AttachmentOption(Icons.Default.Description, "File", Color.LightGray) {
                            showAttachmentMenu = false
                            onPickFile()
                        }
                    }
                }
            }

            // Input Bar
            Surface(
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface,
                border = if (isDark) androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.2f)) else null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { showAttachmentMenu = !showAttachmentMenu }) {
                        Icon(
                            imageVector = if (showAttachmentMenu) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = "Allegati",
                            tint = NeonCyan,
                        )
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Scrivi un messaggio...") },
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                        enabled = !isHandshakeLoading,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                onSendMessage(inputText.trim())
                                inputText = ""
                            }
                        },
                        enabled = !isHandshakeLoading && inputText.isNotBlank(),
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                containerColor = CyanPrimary,
                                contentColor = Color.White,
                                disabledContainerColor = Color.Gray,
                                disabledContentColor = Color.White.copy(alpha = 0.5f),
                            ),
                    ) {
                        Icon(imageVector = Icons.Default.Send, contentDescription = "Invia")
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Elimina Sessione") },
            text = { Text("Vuoi eliminare questa sessione? I file e messaggi non salvati andranno persi.") },
            confirmButton = {
                Button(onClick = onDeleteSession, colors = ButtonDefaults.buttonColors(containerColor = RedAccent)) {
                    Text("Elimina Tutto")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Annulla")
                }
            },
        )
    }

    if (fullScreenImage != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { fullScreenImage = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable { fullScreenImage = null },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = fullScreenImage,
                    contentDescription = "Zoom Immagine",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
fun AttachmentOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = color.copy(alpha = 0.1f),
            modifier = Modifier.size(50.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = null, tint = color)
            }
        }
        Text(text = label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun MessageBubble(
    message: Message,
    onImageClick: (ByteArray) -> Unit,
    onDownloadClick: (String, String) -> Unit,
) {
    val isOutgoing = message.isOutgoing
    val isDark = MaterialTheme.colorScheme.background == Color.Black

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Card(
            shape =
                RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isOutgoing) 16.dp else 0.dp,
                    bottomEnd = if (isOutgoing) 0.dp else 16.dp,
                ),
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        if (isOutgoing) {
                            if (isDark) NeonCyan.copy(alpha = 0.15f) else CyanPrimary
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                ),
            border =
                if (isDark) {
                    androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isOutgoing) NeonCyan.copy(alpha = 0.5f) else Color.Gray.copy(alpha = 0.3f),
                    )
                } else {
                    null
                },
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                when (message.type) {
                    PayloadType.IMAGE -> {
                        val imageBytes =
                            try {
                                Base64.decode(message.content, Base64.DEFAULT)
                            } catch (e: Exception) {
                                null
                            }
                        imageBytes?.let {
                            AsyncImage(
                                model = it,
                                contentDescription = "Immagine",
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .clickable { onImageClick(it) },
                                contentScale = ContentScale.Crop,
                            )
                            IconButton(
                                onClick = { onDownloadClick("immagine_${message.timestamp}.jpg", message.content) },
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Scarica Immagine", tint = if (isDark) NeonCyan else Color.White)
                            }
                        } ?: Text("Errore caricamento immagine", color = RedAccent)
                    }
                    PayloadType.FILE -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier.clickable {
                                    onDownloadClick(message.attachment?.fileName ?: "file", message.content)
                                },
                        ) {
                            Icon(Icons.Default.FilePresent, contentDescription = null, tint = NeonCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = message.attachment?.fileName ?: "File", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(
                                    text = "${(message.attachment?.fileSize ?: 0) / 1024} KB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray,
                                )
                            }
                            Icon(Icons.Default.Download, contentDescription = "Scarica File", tint = Color.Gray)
                        }
                    }
                    else -> {
                        Text(
                            text = message.content,
                            color =
                                if (isOutgoing) {
                                    if (isDark) NeonCyan else Color.White
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text =
                        when {
                            message.isDelivered -> "Consegnato via Tor"
                            message.isError -> "Errore di invio"
                            else -> "Inviando..."
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        when {
                            message.isError -> RedAccent
                            isOutgoing -> if (isDark) NeonCyan.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.7f)
                            else -> Color.Gray
                        },
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}
