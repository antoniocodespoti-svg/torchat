package com.p2p.torchat

import com.p2p.torchat.crypto.E2EManager
import org.junit.Assert.assertEquals
import org.junit.Test

class Ed25519Test {

    @Test
    fun testRFC8032Derivation_Vector1() {
        // Test Vector 1 from RFC 8032
        val seed = hexToBytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val expectedPubKeyHex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"

        val keyPair = E2EManager.ed25519KeyPairFromSeed(seed)
        val rawPubKey = extractRawPublicKey(keyPair.public.encoded)

        assertEquals(expectedPubKeyHex, bytesToHex(rawPubKey))
    }

    @Test
    fun testRFC8032Derivation_Vector2() {
        // Test Vector 2 from RFC 8032
        val seed = hexToBytes("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val expectedPubKeyHex = "3d4017c3e843895a92b70644d5f641724a6f23fdff2b43b766ad411f3b3921fe"

        val keyPair = E2EManager.ed25519KeyPairFromSeed(seed)
        val rawPubKey = extractRawPublicKey(keyPair.public.encoded)

        assertEquals(expectedPubKeyHex, bytesToHex(rawPubKey))
    }

    private fun extractRawPublicKey(encoded: ByteArray): ByteArray {
        // Ed25519 X.509 encoding is 44 bytes long, raw key is the last 32 bytes.
        return encoded.sliceArray(encoded.size - 32 until encoded.size)
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
