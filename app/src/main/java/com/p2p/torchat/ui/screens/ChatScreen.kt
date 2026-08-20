package com.p2p.torchat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.p2p.torchat.model.Message
import com.p2p.torchat.model.Peer
import com.p2p.torchat.ui.theme.NeonCyan

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
    onBack: () -> Unit
) {
    var textState by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(peer.alias, color = NeonCyan) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = NeonCyan)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenVerification) { Icon(Icons.Default.VerifiedUser, "Verifica", tint = NeonCyan) }
                    IconButton(onClick = onDeleteSession) { Icon(Icons.Default.Delete, "Elimina Sessione", tint = NeonCyan) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isHandshakeLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = NeonCyan)
            }
            LazyColumn(modifier = Modifier.weight(1f).padding(8.dp), reverseLayout = true) {
                items(messages.reversed()) { msg ->
                    MessageBubble(msg)
                }
            }
            Row(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
                IconButton(onClick = onPickImage) { Icon(Icons.Default.Image, null, tint = NeonCyan) }
                OutlinedTextField(
                    value = textState,
                    onValueChange = { textState = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Messaggio cifrato...") }
                )
                IconButton(onClick = { if (textState.isNotBlank()) { onSendMessage(textState); textState = "" } }) {
                    Icon(Icons.Default.Send, null, tint = NeonCyan)
                }
            }
        }
    }
}

@Composable
fun MessageBubble(msg: Message) {
    Surface(
        color = if (msg.isOutgoing) Color(0xFF222222) else Color(0xFF111111),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        modifier = Modifier.padding(4.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(msg.content, color = Color.White)
            Text("${if (msg.isDelivered) "Consegnato" else "Inviato"} • ${msg.sequenceNumber}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}
