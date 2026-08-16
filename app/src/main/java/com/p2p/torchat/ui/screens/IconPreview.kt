package com.p2p.torchat.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p2p.torchat.ui.theme.NeonCyan
import com.p2p.torchat.ui.theme.NeonGreen
import com.p2p.torchat.ui.theme.TorP2PChatTheme

@Composable
fun AppIconMockup() {
    Box(
        modifier =
            Modifier
                .size(200.dp)
                .background(Color.Black, RoundedCornerShape(40.dp))
                .border(2.dp, Brush.linearGradient(listOf(NeonGreen, NeonCyan)), RoundedCornerShape(40.dp)),
        contentAlignment = Alignment.Center,
    ) {
        // Background Matrix Effect (Simulated with Canvas)
        Canvas(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            val color = NeonGreen.copy(alpha = 0.2f)
            // Draw some "bits"
            for (i in 0..10) {
                for (j in 0..10) {
                    if ((i + j) % 3 == 0) {
                        drawCircle(color, radius = 2f, center = Offset(i * 50f, j * 50f))
                    }
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // Hooded Silhouette
                Canvas(modifier = Modifier.size(120.dp)) {
                    val hoodPath =
                        Path().apply {
                            moveTo(size.width * 0.2f, size.height * 0.9f)
                            quadraticBezierTo(size.width * 0.2f, size.height * 0.1f, size.width * 0.5f, size.height * 0.05f)
                            quadraticBezierTo(size.width * 0.8f, size.height * 0.1f, size.width * 0.8f, size.height * 0.9f)
                        }
                    drawPath(hoodPath, color = NeonGreen, style = Stroke(width = 4.dp.toPx()))

                    // Circuit lines inside face area
                    drawLine(
                        NeonCyan,
                        Offset(size.width * 0.4f, size.height * 0.4f),
                        Offset(size.width * 0.6f, size.height * 0.4f),
                        strokeWidth = 2f,
                    )
                    drawLine(
                        NeonCyan,
                        Offset(size.width * 0.6f, size.height * 0.4f),
                        Offset(size.width * 0.6f, size.height * 0.6f),
                        strokeWidth = 2f,
                    )
                }

                // Binary text "overlay" in the face
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("1011", color = NeonGreen.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("0101", color = NeonCyan.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "TOR CHAT",
                color = NeonGreen,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF0F0F0)
@Composable
fun PreviewAppIcon() {
    TorP2PChatTheme(darkTheme = true) {
        Box(modifier = Modifier.padding(40.dp)) {
            AppIconMockup()
        }
    }
}
