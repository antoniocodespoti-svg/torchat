package com.p2p.torchat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Gavel
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermsOfUseScreen(
    isViewOnly: Boolean = false,
    onAccept: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    var accepted by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TERMINI D'USO", fontWeight = FontWeight.ExtraBold) },
                navigationIcon = {
                    if (isViewOnly) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                        }
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
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Gavel,
                contentDescription = null,
                tint = NeonCyan,
                modifier = Modifier.size(48.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "CONDIZIONI DI UTILIZZO",
                style = MaterialTheme.typography.titleLarge,
                color = NeonCyan,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF111111), MaterialTheme.shapes.medium)
                        .padding(16.dp),
            ) {
                Column(
                    modifier =
                        Modifier
                            .verticalScroll(rememberScrollState()),
                ) {
                    LegalSection("⚠️ Avviso Legale e Condizioni di Utilizzo", "")
                    LegalSection(
                        "1. Natura dello Strumento e Missione",
                        "Questa applicazione è uno strumento di comunicazione peer-to-peer (P2P) decentralizzato, progettato esclusivamente per tutelare la privacy, la libertà di espressione e la sicurezza di giornalisti, attivisti, dissidenti e cittadini operanti in contesti ad alto rischio. L'architettura \"serverless\" garantisce che i dati non risiedano su alcun server centrale, rendendo le comunicazioni resistenti alla censura e alle intercettazioni di massa.",
                    )
                    LegalSection(
                        "2. Impossibilità Tecnica di Monitoraggio",
                        "L'utente riconosce ed accetta che, a causa della crittografia end-to-end e della natura decentralizzata dell'app:\n\n• Lo sviluppatore non ha accesso tecnico alle chiavi di crittografia, ai messaggi, ai file o ai metadati scambiati.\n• È tecnicamente impossibile per lo sviluppatore monitorare, censurare, rimuovere contenuti o collaborare con autorità fornendo dati di comunicazione, poiché tali dati non esistono in alcun database sotto il nostro controllo.\n• L'assenza di un intermediario tecnico esonera lo sviluppatore da qualsiasi responsabilità riguardante i contenuti generati dagli utenti.",
                    )
                    LegalSection(
                        "3. Divieto di Uso Illecito (Acceptable Use Policy)",
                        "Sebbene l'app sia progettata per la protezione dei diritti umani, è severamente vietato utilizzarla per attività illegali:\n\n• Traffico di sostanze stupefacenti, armi o esseri umani.\n• Terrorismo, riciclaggio di denaro o frodi finanziarie.\n• Sfruttamento sessuale minorile o diffusione di materiale pedopornografico.\n• Cyberattacchi, hacking non autorizzato o diffusione di malware.",
                    )
                    LegalSection(
                        "4. Responsabilità Esclusiva dell'Utente",
                        "L'utente è l'unico e assoluto responsabile penale e civile di ogni contenuto inviato o ricevuto tramite questa applicazione. Utilizzando questo software, l'utente dichiara di agire in conformità con le leggi del proprio paese e si assume ogni rischio derivante dal suo utilizzo.",
                    )
                    LegalSection(
                        "5. Clausola di Indennizzo (Hold Harmless)",
                        "L'utente accetta di indennizzare, difendere e tenere indenne lo sviluppatore, i collaboratori e i contributori del progetto da qualsiasi azione legale, indagine, danno, perdita, costo o spesa (incluse le ragionevoli parcelle legali) che dovesse derivare, direttamente o indirettamente, dall'uso o dall'abuso di questa applicazione da parte dell'utente.",
                    )
                    LegalSection(
                        "6. Accettazione",
                        "L'accesso e l'utilizzo dell'applicazione costituiscono accettazione piena e incondizionata di questi termini. Se non si è d'accordo con quanto sopra, si prega di disinstallare immediatamente il software.",
                    )
                }
            }

            if (!isViewOnly) {
                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = accepted,
                        onCheckedChange = { accepted = it },
                        colors = CheckboxDefaults.colors(checkedColor = NeonCyan),
                    )
                    Text(
                        text = "Ho letto e accetto i termini di utilizzo",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onAccept,
                    enabled = accepted,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            disabledContainerColor = Color.DarkGray,
                        ),
                ) {
                    Text("ACCETTA E PROCEDI", color = if (accepted) Color.Black else Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun LegalSection(
    title: String,
    content: String,
) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = NeonCyan,
            fontWeight = FontWeight.Bold,
        )
        if (content.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Justify,
                lineHeight = 18.sp,
            )
        }
    }
}
