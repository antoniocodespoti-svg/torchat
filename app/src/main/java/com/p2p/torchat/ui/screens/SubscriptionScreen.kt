package com.p2p.torchat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.p2p.torchat.ui.theme.NeonCyan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    onionAddress: String,
    onActivate: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ATTIVAZIONE LICENZA", color = NeonCyan, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Diamond, null, modifier = Modifier.size(64.dp), tint = NeonCyan)
            Spacer(modifier = Modifier.height(24.dp))
            Text("Per continuare a usare TorP2P Chat è necessaria una licenza attiva.", color = Color.White, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Il tuo indirizzo per l'attivazione:", color = Color.Gray)
            Text(onionAddress, color = NeonCyan)
            Spacer(modifier = Modifier.height(32.dp))
            OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Codice di Attivazione") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = { if (code.isNotBlank()) onActivate(code) }, modifier = Modifier.fillMaxWidth()) { Text("ATTIVA ORA") }
        }
    }
}
