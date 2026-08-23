package com.p2p.torchat

import com.p2p.torchat.crypto.E2EManager
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class Ed25519Test {

    @Test
    fun testRFC8032Derivation() {
        // Test Vector from RFC 8032 (Section 7.1)
        // Seed (k): 9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60
        // Public Key (A): d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a

        val seed = hexToBytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val expectedPubKeyHex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"

        val keyPair = E2EManager.ed25519KeyPairFromSeed(seed)

        // JCA encoding for Ed25519 Public Key (X.509) is not raw bytes.
        // It includes OID prefix. Raw key is at the end (32 bytes).
        val encoded = keyPair.public.encoded
        val rawPubKey = encoded.sliceArray(encoded.size - 32 until encoded.size)

        assertEquals(expectedPubKeyHex, bytesToHex(rawPubKey))
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
