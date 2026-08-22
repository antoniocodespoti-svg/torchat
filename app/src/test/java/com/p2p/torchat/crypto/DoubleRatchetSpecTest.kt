package com.p2p.torchat.crypto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DoubleRatchetSpecTest {

    @Test
    fun testDoubleRatchetFullSpecScenario() {
        runBlocking {
            val sharedSecret = ByteArray(32) { 0x11.toByte() }
            val sid = "spec-session-id"

            val aliceEK = E2EManager.generateEphemeralKeyPair()
            val bobEK = E2EManager.generateEphemeralKeyPair()

            // Alice starts (Initiator)
            val alice = DoubleRatchetSession(sid, sharedSecret, aliceEK, bobEK.public)

            // Bob starts (Responder)
            val bob = DoubleRatchetSession(sid, sharedSecret, bobEK)
            bob.BobInit(aliceEK.public)

            // Alice sends 3 messages (A1, A2, A3)
            val sA1 = alice.nextSendKey()
            val sA2 = alice.nextSendKey()
            val sA3 = alice.nextSendKey()

            assertEquals(0, sA1.header.n)
            assertEquals(1, sA2.header.n)
            assertEquals(2, sA3.header.n)
            assertEquals(0, sA1.header.pn)

            // Bob receives them
            val dA1 = bob.tryDecrypt(sA1.header, "enc1", ByteArray(0)) { _, _, _ -> "A1" }
            val dA3 = bob.tryDecrypt(sA3.header, "enc3", ByteArray(0)) { _, _, _ -> "A3" } // Out of order
            val dA2 = bob.tryDecrypt(sA2.header, "enc2", ByteArray(0)) { _, _, _ -> "A2" } // From skipped

            assertEquals("A1", dA1)
            assertEquals("A2", dA2)
            assertEquals("A3", dA3)

            // Bob sends 2 messages (B1, B2) -> Triggers DH Ratchet for Bob
            val sB1 = bob.nextSendKey()
            val sB2 = bob.nextSendKey()

            assertEquals(0, sB1.header.n)
            assertEquals(1, sB2.header.n)
            assertEquals(0, sB1.header.pn) // Bob's first sending chain

            // Alice receives B1
            val dB1 = alice.tryDecrypt(sB1.header, "encB1", ByteArray(0)) { _, _, _ -> "B1" }
            assertEquals("B1", dB1)

            // Alice sends A4 -> Triggers another DH Ratchet for Alice
            val sA4 = alice.nextSendKey()
            assertEquals(0, sA4.header.n)
            assertEquals(3, sA4.header.pn) // Alice's previous chain had 3 messages (A1, A2, A3)

            // Bob receives A4
            val dA4 = bob.tryDecrypt(sA4.header, "encA4", ByteArray(0)) { _, _, _ -> "A4" }
            assertEquals("A4", dA4)
        }
    }
}
