package com.p2p.torchat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p2p.torchat.ui.theme.NeonCyan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("INFORMAZIONI", fontWeight = FontWeight.ExtraBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
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
                    .background(Color.Black)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
        ) {
            Icon(
                Icons.Default.Shield,
                contentDescription = null,
                tint = NeonCyan,
                modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "MANIFESTO DI SICUREZZA",
                style = MaterialTheme.typography.titleLarge,
                color = NeonCyan,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(32.dp))

            InfoSection(
                title = "1. ARCHITETTURA PURE P2P",
                content = "TorP2P Chat è un’architettura pura Peer-to-Peer che elimina alla radice il concetto di server centrale. Non esiste un database remoto che possa essere hackerato, né un punto centrale di controllo. Poiché non conserviamo alcuna informazione dell'utente, nessuna autorità può ordinare la consegna dei tuoi dati: semplicemente, essi non esistono al di fuori del tuo dispositivo. La comunicazione e la proprietà dei dati appartengono esclusivamente a te e al tuo interlocutore.",
            )

            InfoSection(
                title = "2. INVISIBILITÀ: MODELLO FULL-ONION",
                content = "A differenza delle implementazioni standard di Tor che utilizzano 'nodi di uscita' verso il web in chiaro — esponendo potenzialmente il traffico al monitoraggio — la nostra piattaforma opera integralmente tramite Hidden Services v3. Il segnale non abbandona mai il perimetro crittografato della rete Onion: è una comunicazione a circuito chiuso che garantisce l'anonimato assoluto e l'invulnerabilità a intercettazioni esterne.",
            )

            InfoSection(
                title = "3. FILOSOFIA ZERO-TRACE",
                content = "I tuoi messaggi non vengono mai salvati nella memoria permanente del telefono. Le conversazioni vivono esclusivamente nella RAM: una volta chiusa l'app o spento il dispositivo, ogni traccia svanisce per sempre. Nessun database, nessuna prova residua.",
            )

            InfoSection(
                title = "4. LAVAGGIO METADATI",
                content = "Ogni immagine inviata viene 'ripulita' automaticamente. L'app distrugge ogni metadato EXIF (GPS, modello fotocamera, ora dello scatto), rendendo impossibile risalire alla tua posizione operativa o al momento esatto dell'invio.",
            )

            Spacer(modifier = Modifier.height(48.dp))

            HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(bottom = 16.dp))

            Text(
                text = "TorP2P Chat v1.0.1 - Stable Edition",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "Proteggi la tua libertà. Sempre.",
                style = MaterialTheme.typography.bodySmall,
                color = NeonCyan.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun InfoSection(
    title: String,
    content: String,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = NeonCyan,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Justify,
            lineHeight = 22.sp,
        )
    }
}
