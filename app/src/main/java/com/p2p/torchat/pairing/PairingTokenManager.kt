package com.p2p.torchat.pairing

import android.content.Context
import com.google.gson.Gson
import com.p2p.torchat.crypto.E2EManager
import com.p2p.torchat.model.Peer
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

class PairingTokenManager(private val context: Context) {
    private val gson = Gson()

    fun generateToken(myOnion: String, myAlias: String, myPubKey: String): String {
        val data = "$myOnion|$myAlias|$myPubKey"
        return Base64.getEncoder().encodeToString(data.toByteArray())
    }

    fun parseToken(token: String): Peer? {
        return try {
            val decoded = String(Base64.getDecoder().decode(token))
            val parts = decoded.split("|")
            if (parts.size >= 3) {
                Peer(
                    onionAddress = parts[0],
                    alias = parts[1],
                    identityPublicKey = parts[2]
                )
            } else null
        } catch (e: Exception) { null }
    }
}
