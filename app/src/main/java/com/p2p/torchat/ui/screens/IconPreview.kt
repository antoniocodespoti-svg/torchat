package com.p2p.torchat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.p2p.torchat.ui.theme.TorP2PChatTheme

@Composable
fun IconPreviewGrid() {
    val icons = listOf(
        Icons.Default.Security to "Security",
        Icons.Default.Settings to "Settings",
        Icons.Default.LightMode to "Light",
        Icons.Default.DarkMode to "Dark",
        Icons.Default.QrCodeScanner to "Scan",
        Icons.Default.QrCode to "QR",
        Icons.Default.Add to "Add",
        Icons.Default.Delete to "Delete",
        Icons.AutoMirrored.Filled.ArrowBack to "Back",
        Icons.Default.Visibility to "Show",
        Icons.Default.VisibilityOff to "Hide",
        Icons.Default.Lock to "Lock",
        Icons.Default.VerifiedUser to "Verified",
        Icons.Default.Warning to "Warning",
        Icons.Default.Image to "Image",
        Icons.Default.Send to "Send"
    )

    LazyVerticalGrid(
        columns = GridCells.Adaptive(80.dp),
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(16.dp)
    ) {
        items(icons) { (icon, name) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(imageVector = icon, contentDescription = name, tint = Color.Cyan, modifier = Modifier.size(32.dp))
                Text(text = name, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}

@Preview
@Composable
fun PreviewIcons() {
    TorP2PChatTheme(darkTheme = true) {
        IconPreviewGrid()
    }
}
