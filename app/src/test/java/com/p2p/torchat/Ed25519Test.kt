package com.p2p.torchat

import com.p2p.torchat.crypto.E2EManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Signature

class Ed25519Test {

    @Test
    fun testRFC8032Derivation_Vector1() {
        // RFC 8032 Test Vector 1 (Message: empty)
        val seed = hexToBytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val expectedPubKeyHex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        val expectedSigHex = "e5564300c360ac72908f0c19862b7030911fb22064132470792348574708767746522c16daed577a44c4b693bc2f50d995c6c2a66e6b528a9b70868f0011400a"

        val keyPair = E2EManager.ed25519KeyPairFromSeed(seed)
        val rawPubKey = extractRawPublicKey(keyPair.public.encoded)

        assertEquals("Public key mismatch", expectedPubKeyHex, bytesToHex(rawPubKey))

        // Test Signature for Vector 1 (Empty Message)
        val emptyMessage = ByteArray(0)
        val sig = E2EManager.signData(emptyMessage, keyPair.private)
        assertEquals("Signature mismatch", expectedSigHex, bytesToHex(sig))
        assertTrue("Verification failed", E2EManager.verifySignature(emptyMessage, sig, keyPair.public))
    }

    @Test
    fun testRFC8032Derivation_Vector2() {
        val seed = hexToBytes("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val expectedPubKeyHex = "3d4017c3e843895a92b70644d5f641724a6f23fdff2b43b766ad411f3b3921fe"

        val keyPair = E2EManager.ed25519KeyPairFromSeed(seed)
        val rawPubKey = extractRawPublicKey(keyPair.public.encoded)

        assertEquals(expectedPubKeyHex, bytesToHex(rawPubKey))
    }

    private fun extractRawPublicKey(encoded: ByteArray): ByteArray {
        return if (encoded.size >= 32) {
            encoded.sliceArray(encoded.size - 32 until encoded.size)
        } else {
            encoded
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
