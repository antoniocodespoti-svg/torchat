package com.p2p.torchat

import com.p2p.torchat.crypto.DoubleRatchetSession
import com.p2p.torchat.crypto.E2EManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator

class RatchetRegressionTest {

    @Test
    fun testSkippedMessageKeysSurviveCommit_Finding001() = runBlocking {
        // Setup Alice and Bob
        val sharedRoot = ByteArray(32) { 1 }
        val kg = KeyPairGenerator.getInstance("X25519")

        val alicePair = kg.generateKeyPair()
        val bobPair = kg.generateKeyPair()

        val alice = DoubleRatchetSession("sid", sharedRoot.copyOf(), alicePair, bobPair.public)
        val bob = DoubleRatchetSession("sid", sharedRoot.copyOf(), bobPair, alicePair.public)

        // 1. Alice sends 3 messages
        val s1 = alice.nextSendKey()
        val s2 = alice.nextSendKey()
        val s3 = alice.nextSendKey()

        // 2. Bob receives msg 1
        bob.tryDecrypt(s1.header, "enc".toByteArray(), "aad".toByteArray()) { _, _, _ -> "dec1".toByteArray() }

        // 3. Bob receives msg 3 (skipping msg 2)
        bob.tryDecrypt(s3.header, "enc".toByteArray(), "aad".toByteArray()) { _, _, _ -> "dec3".toByteArray() }

        // 4. Bob receives msg 2 (using skipped key)
        // If FINDING-001 is present, this will fail because the key for msg 2 was zeroed in Bob's memory.
        val dec2 = bob.tryDecrypt(s2.header, "enc".toByteArray(), "aad".toByteArray()) { _, key, _ ->
            // Return 'key' length or something that proves it's not all zeros
            if (key.all { it == 0.toByte() }) throw SecurityException("KEY_IS_ZEROED")
            "dec2".toByteArray()
        }

        assertEquals("dec2", String(dec2))
    }
}
