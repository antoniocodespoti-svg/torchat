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
            val pkts = (1..3).map { i ->
                val s = alice.nextSendKey()
                val rpk = E2EManager.publicKeyToString(s.header.ratchetPublicKey)
                val aad = E2EManager.buildAAD(1, 0, i, "alice", sid, rpk, s.header.pn, s.header.n)
                val enc = E2EManager.encryptV2("Msg $i", s.messageKey, aad)
                Triple(s.header, enc, aad)
            }

            // Bob receives them (Out of order)
            assertEquals("Msg 1", bob.tryDecrypt(pkts[0].first, pkts[0].second, pkts[0].third) { e, k, a -> E2EManager.decryptV2(e, k, a) })
            assertEquals("Msg 3", bob.tryDecrypt(pkts[2].first, pkts[2].second, pkts[2].third) { e, k, a -> E2EManager.decryptV2(e, k, a) })
            assertEquals("Msg 2", bob.tryDecrypt(pkts[1].first, pkts[1].second, pkts[1].third) { e, k, a -> E2EManager.decryptV2(e, k, a) })

            // Bob sends 2 messages (B1, B2) -> Triggers DH Ratchet for Bob
            val sB1 = bob.nextSendKey()
            val rpkB1 = E2EManager.publicKeyToString(sB1.header.ratchetPublicKey)
            val aadB1 = E2EManager.buildAAD(1, 0, 1, "bob", sid, rpkB1, sB1.header.pn, sB1.header.n)
            val encB1 = E2EManager.encryptV2("Bob 1", sB1.messageKey, aadB1)

            assertEquals(0, sB1.header.n)
            assertEquals(0, sB1.header.pn)

            // Alice receives B1
            val dB1 = alice.tryDecrypt(sB1.header, encB1, aadB1) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals("Bob 1", dB1)

            // Alice sends A4 -> Triggers another DH Ratchet for Alice
            val sA4 = alice.nextSendKey()
            val rpkA4 = E2EManager.publicKeyToString(sA4.header.ratchetPublicKey)
            val aadA4 = E2EManager.buildAAD(1, 0, 4, "alice", sid, rpkA4, sA4.header.pn, sA4.header.n)
            val encA4 = E2EManager.encryptV2("Alice 4", sA4.messageKey, aadA4)

            assertEquals(0, sA4.header.n)
            assertEquals(3, sA4.header.pn)

            // Bob receives A4
            val dA4 = bob.tryDecrypt(sA4.header, encA4, aadA4) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals("Alice 4", dA4)
        }
    }
}
