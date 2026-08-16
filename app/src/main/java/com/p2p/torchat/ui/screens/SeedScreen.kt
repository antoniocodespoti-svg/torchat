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
import com.p2p.torchat.ui.theme.NeonCyan
import com.p2p.torchat.ui.theme.RedAccent

enum class SeedMode {
    DISPLAY,
    INPUT,
}

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
    val inputWords = remember { mutableStateListOf(*Array(12) { "" }) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("RIMUOVERE IL SEED?") },
            text = { Text("Le 12 parole verranno cancellate permanentemente dal telefono. Assicurati di averle scritte su carta!") },
            confirmButton = {
                Button(onClick = {
                    onRemoveSeed()
                    showRemoveConfirm = false
                }, colors = ButtonDefaults.buttonColors(containerColor = RedAccent)) {
                    Text("RIMUOVI ORA")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text("ANNULLA") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (mode == SeedMode.DISPLAY) "SEED DI SICUREZZA" else "RIPRISTINO SEED") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (mode == SeedMode.DISPLAY) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = RedAccent, modifier = Modifier.size(48.dp))
                Text(
                    text = "SCRIVI QUESTE 12 PAROLE SU CARTA",
                    color = RedAccent,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Text(
                    text = "Sono l'unico modo per recuperare i tuoi dati in caso di smarrimento password o wipe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = "Inserisci le tue 12 parole di sicurezza nell'ordine esatto.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (mode == SeedMode.DISPLAY) {
                if (seed.isEmpty()) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text("SEED RIMOSSO PER SICUREZZA", color = NeonCyan, fontWeight = FontWeight.Bold)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        itemsIndexed(seed) { index, word ->
                            SeedWordItem(index + 1, word)
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(12) { index ->
                        OutlinedTextField(
                            value = inputWords[index],
                            onValueChange = { inputWords[index] = it.trim().lowercase() },
                            label = { Text("${index + 1}", fontSize = 10.sp) },
                            singleLine = true,
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NeonCyan,
                                    unfocusedBorderColor = Color.Gray,
                                ),
                        )
                    }
                }
            }

            errorMessage?.let {
                Text(it, color = RedAccent, style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (mode == SeedMode.DISPLAY && seed.isNotEmpty()) {
                Button(
                    onClick = { showRemoveConfirm = true },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                ) {
                    Text("RIMUOVI SEED DAL DISPOSITIVO", color = Color.White)
                }

                OutlinedButton(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray),
                ) {
                    Text("SALTA PER ORA (VAI ALLA HOME)", color = Color.White)
                }
            }

            Button(
                onClick = {
                    if (mode == SeedMode.DISPLAY) {
                        onAction(seed)
                    } else {
                        if (inputWords.any { it.isBlank() }) {
                            errorMessage = "Inserisci tutte le 12 parole."
                        } else {
                            onAction(inputWords.toList())
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
            ) {
                Text(
                    text = if (mode == SeedMode.DISPLAY) "HO SCRITTO TUTTO (BACKUP)" else "VERIFICA E RIPRISTINA",
                    color = Color.Black,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}

@Composable
fun SeedWordItem(
    index: Int,
    word: String,
) {
    Box(
        modifier =
            Modifier
                .border(1.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(12.dp),
    ) {
        Row {
            Text(text = "$index.", color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = word, color = Color.White, fontWeight = FontWeight.Medium)
        }
    }
}
