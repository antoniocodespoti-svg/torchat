package com.p2p.torchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class Ed25519CompatibilityTest {

    @Test
    fun testRfc8032Vector1() {
        // Test Vector 1 from RFC 8032
        // SECRET KEY (seed): 9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60
        // PUBLIC KEY: d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a

        val seed = hexToBytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val expectedPubKeyHex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"

        val keyPair = E2EManager.ed25519KeyPairFromSeed(seed)

        // E2EManager.publicKeyToString returns Base64 of encoded key (X.509)
        // We need the raw 32-byte public key to compare with the test vector.
        // In Java, Ed25519PublicKey.getEncoded() returns X.509 format.
        // The raw key is the last 32 bytes of the X.509 encoding for Ed25519.
        val encoded = keyPair.public.encoded
        val rawPubKey = encoded.sliceArray(encoded.size - 32 until encoded.size)
        val actualPubKeyHex = bytesToHex(rawPubKey)

        assertEquals("Public key mismatch for RFC 8032 Vector 1", expectedPubKeyHex, actualPubKeyHex)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (i in 0 until hex.length step 2) {
            bytes[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
        }
        return bytes
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
