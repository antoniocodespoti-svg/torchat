package com.p2p.torchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class Ed25519VectorTest {

    @Test
    fun testRFC8032Vector1() {
        // RFC 8032 Section 7.1 Test Case 1
        val seedHex = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
        val expectedPubHex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"

        val seed = hexToBytes(seedHex)
        val pair = E2EManager.ed25519KeyPairFromSeed(seed)

        // Java's publicKey.encoded returns the X.509 SubjectPublicKeyInfo
        // For Ed25519, the last 32 bytes are the raw public key
        val actualPubBytes = pair.public.encoded
        val actualRawPub = actualPubBytes.takeLast(32).toByteArray()

        assertEquals("Public key must match RFC 8032 vector", expectedPubHex, bytesToHex(actualRawPub))
    }

    @Test
    fun testRFC8032Vector2() {
        // RFC 8032 Section 7.1 Test Case 2
        val seedHex = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"
        val expectedPubHex = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"

        val seed = hexToBytes(seedHex)
        val pair = E2EManager.ed25519KeyPairFromSeed(seed)

        val actualRawPub = pair.public.encoded.takeLast(32).toByteArray()
        assertEquals("Public key must match RFC 8032 vector", expectedPubHex, bytesToHex(actualRawPub))
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
