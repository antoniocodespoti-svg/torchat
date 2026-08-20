package com.p2p.torchat.ui.screens

import com.p2p.torchat.model.Peer

sealed class Screen {
    object Auth : Screen()
    object Home : Screen()
    object Settings : Screen()
    object ChangePassword : Screen()
    object SeedBackup : Screen()
    object SeedRestore : Screen()
    object Subscription : Screen()
    object Info : Screen()
    object TermsOfUse : Screen()
    data class Chat(val peer: Peer) : Screen()
    data class Verification(val peer: Peer) : Screen()
    object QRCode : Screen()
    object QRScanner : Screen()
}
