package com.p2p.torchat.crypto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AdversarialProtocolTest {

    @Test
    fun testActiveMITMRollback() {
        runBlocking {
            val sharedSecret = ByteArray(32) { 0x66.toByte() }
            val sid = "mitm-sid"
            val aliceEK = E2EManager.generateEphemeralKeyPair()
            val bobEK = E2EManager.generateEphemeralKeyPair()

            val alice = DoubleRatchetSession(sid, sharedSecret, aliceEK, bobEK.public)
            val bob = DoubleRatchetSession(sid, sharedSecret, bobEK)
            bob.BobInit(aliceEK.public)

            // 1. Alice sends valid Msg 1
            val s1 = alice.nextSendKey()
            val rpk1 = E2EManager.publicKeyToString(s1.header.ratchetPublicKey)
            val aad1 = E2EManager.buildAAD(1, 0, 1, "a", sid, rpk1, s1.header.pn, s1.header.n)
            val enc1 = E2EManager.encryptV2("Valid 1", s1.messageKey, aad1)

            assertEquals("Valid 1", bob.tryDecrypt(s1.header, enc1, aad1) { e, k, a -> E2EManager.decryptV2(e, k, a) })

            // 2. MITM: Modified ciphertext
            val s2 = alice.nextSendKey()
            val rpk2 = E2EManager.publicKeyToString(s2.header.ratchetPublicKey)
            val aad2 = E2EManager.buildAAD(1, 0, 2, "a", sid, rpk2, s2.header.pn, s2.header.n)
            val enc2 = E2EManager.encryptV2("Valid 2", s2.messageKey, aad2)
            val modifiedEnc2 = enc2.reversed() // Corrupt Base64/Tag

            try {
                bob.tryDecrypt(s2.header, modifiedEnc2, aad2) { e, k, a -> E2EManager.decryptV2(e, k, a) }
                fail("Should have thrown AEAD exception")
            } catch (_: Exception) {
                // Expected rollback
            }

            // 3. Verify Bob's state did NOT advance. Re-sending valid Msg 2 should work.
            assertEquals("Valid 2", bob.tryDecrypt(s2.header, enc2, aad2) { e, k, a -> E2EManager.decryptV2(e, k, a) })
        }
    }

    @Test
    fun testReplayAttackPrevention() {
        runBlocking {
            val sharedSecret = ByteArray(32) { 0x88.toByte() }
            val sid = "replay-sid"
            val aliceEK = E2EManager.generateEphemeralKeyPair()
            val bobEK = E2EManager.generateEphemeralKeyPair()

            val alice = DoubleRatchetSession(sid, sharedSecret, aliceEK, bobEK.public)
            val bob = DoubleRatchetSession(sid, sharedSecret, bobEK)
            bob.BobInit(aliceEK.public)

            val s1 = alice.nextSendKey()
            val rpk1 = E2EManager.publicKeyToString(s1.header.ratchetPublicKey)
            val aad1 = E2EManager.buildAAD(1, 0, 1, "a", sid, rpk1, s1.header.pn, s1.header.n)
            val enc1 = E2EManager.encryptV2("Msg 1", s1.messageKey, aad1)

            // First time: Success
            bob.tryDecrypt(s1.header, enc1, aad1) { e, k, a -> E2EManager.decryptV2(e, k, a) }

            // Replay same packet: Should fail with "Old message"
            try {
                bob.tryDecrypt(s1.header, enc1, aad1) { e, k, a -> E2EManager.decryptV2(e, k, a) }
                fail("Should have rejected replay")
            } catch (e: SecurityException) {
                assertTrue(e.message!!.contains("Old or replayed"))
            }
        }
    }

    @Test
    fun testExtremeGapDoSRejection() {
        runBlocking {
            val sharedSecret = ByteArray(32) { 0x99.toByte() }
            val sid = "dos-sid"
            val aliceEK = E2EManager.generateEphemeralKeyPair()
            val bobEK = E2EManager.generateEphemeralKeyPair()
            val bob = DoubleRatchetSession(sid, sharedSecret, bobEK)
            bob.BobInit(aliceEK.public)

            // Attacker sends header with N=5000 (huge gap)
            val fakeHeader = DoubleRatchetSession.RatchetHeader(aliceEK.public, 0, 5000)
            try {
                bob.tryDecrypt(fakeHeader, "enc", ByteArray(0)) { _, _, _ -> "" }
                fail("Should have rejected large gap")
            } catch (e: SecurityException) {
                assertTrue(e.message!!.contains("gap too large"))
            }
        }
    }
}
