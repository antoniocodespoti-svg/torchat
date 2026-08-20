package com.p2p.torchat.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p2p.torchat.model.SeedMode
import com.p2p.torchat.ui.theme.NeonCyan
import com.p2p.torchat.ui.theme.RedAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeedScreen(
    mode: SeedMode,
    seed: List<String>,
    onAction: (List<String>) -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit = {},
    onRemoveSeed: () -> Unit = {},
) {
    val inputWords = remember { mutableStateListOf<String>().apply { repeat(12) { add("") } } }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (mode == SeedMode.DISPLAY) "BACKUP SEED" else "RIPRISTINO SEED", color = NeonCyan) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = NeonCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            if (mode == SeedMode.DISPLAY) {
                Text("Queste 12 parole permettono di recuperare il tuo ID Onion e i tuoi contatti. SCRIVILE SU CARTA!", color = RedAccent, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f)) {
                    itemsIndexed(seed) { index, word ->
                        Card(modifier = Modifier.padding(4.dp)) {
                            Text("${index + 1}. $word", modifier = Modifier.padding(8.dp))
                        }
                    }
                }
                Button(onClick = { onAction(seed) }, modifier = Modifier.fillMaxWidth()) { Text("ESPORTA FILE BACKUP") }
            } else {
                Text("Inserisci le 12 parole del tuo seed per importare il backup:", color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f)) {
                    itemsIndexed(inputWords) { index, _ ->
                        OutlinedTextField(
                            value = inputWords[index],
                            onValueChange = { inputWords[index] = it },
                            label = { Text("${index + 1}") },
                            modifier = Modifier.padding(2.dp)
                        )
                    }
                }
                Button(onClick = { onAction(inputWords.toList()) }, modifier = Modifier.fillMaxWidth()) { Text("PROSEGUI") }
            }
        }
    }
}
