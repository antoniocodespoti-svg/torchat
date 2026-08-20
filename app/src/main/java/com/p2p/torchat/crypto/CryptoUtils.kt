package com.p2p.torchat.crypto

import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object CryptoUtils {
    fun publicKeyToString(pk: PublicKey): String = Base64.getEncoder().encodeToString(pk.encoded)

    fun stringToPublicKey(
        b64: String,
        algo: String,
    ): PublicKey {
        val kb = Base64.getDecoder().decode(b64)
        return KeyFactory.getInstance(algo).generatePublic(X509EncodedKeySpec(kb))
    }
}
