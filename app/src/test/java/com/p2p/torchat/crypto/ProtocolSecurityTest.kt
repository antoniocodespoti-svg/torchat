package com.p2p.torchat.crypto

import com.p2p.torchat.model.PendingHandshake
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProtocolSecurityTest {

    @Test
    fun testFullDoubleRatchetExchange() {
        runBlocking {
            val aliceIKP = E2EManager.generateIdentityKeyPair()
            val bobIKP = E2EManager.generateIdentityKeyPair()
            val aliceOnion = "alice777777777777777777777777777777777777777777777777777.onion"
            val bobOnion = "bob8888888888888888888888888888888888888888888888888888.onion"
            val sharedSecret = ByteArray(32) { 0x42.toByte() }
            val sid = "v1-session-id"

            // Alice (Initiator) starts with Bob's first ratchet key (eB)
            val bobEKP = E2EManager.generateEphemeralKeyPair()
            val aliceEKP = E2EManager.generateEphemeralKeyPair()

            val aliceSession = DoubleRatchetSession(sid, sharedSecret, aliceEKP, bobEKP.public)
            val bobSession = DoubleRatchetSession(sid, sharedSecret, bobEKP)
            bobSession.BobInit(aliceEKP.public)

            // Alice sends to Bob
            val msg1 = "Hello Bob (Double Ratchet)!"
            val s1 = aliceSession.nextSendKey()
            val aad1 = E2EManager.buildAAD(1, 0, s1.sequence, aliceOnion, sid, E2EManager.publicKeyToString(s1.ratchetPublicKey))
            val enc1 = E2EManager.encryptV2(msg1, s1.messageKey, aad1)

            val dec1 = bobSession.tryDecrypt(s1.ratchetPublicKey, s1.sequence, enc1, aad1) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals(msg1, dec1)

            // Bob responds to Alice (triggers DH step)
            val msg2 = "Hi Alice, I see your DH key!"
            val s2 = bobSession.nextSendKey()
            val aad2 = E2EManager.buildAAD(1, 0, s2.sequence, bobOnion, sid, E2EManager.publicKeyToString(s2.ratchetPublicKey))
            val enc2 = E2EManager.encryptV2(msg2, s2.messageKey, aad2)

            val dec2 = aliceSession.tryDecrypt(s2.ratchetPublicKey, s2.sequence, enc2, aad2) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals(msg2, dec2)
        }
    }

    @Test
    fun testDoubleRatchetOutOrOrder() {
        runBlocking {
            val sharedSecret = ByteArray(32) { 0x66.toByte() }
            val sid = "out-of-order-sid"
            val aliceEKP = E2EManager.generateEphemeralKeyPair()
            val bobEKP = E2EManager.generateEphemeralKeyPair()

            val aliceSession = DoubleRatchetSession(sid, sharedSecret, aliceEKP, bobEKP.public)
            val bobSession = DoubleRatchetSession(sid, sharedSecret, bobEKP)
            bobSession.BobInit(aliceEKP.public)

            // Alice sends 3 messages
            val m1 = "Msg 1"; val s1 = aliceSession.nextSendKey()
            val m2 = "Msg 2"; val s2 = aliceSession.nextSendKey()
            val m3 = "Msg 3"; val s3 = aliceSession.nextSendKey()

            val c1 = E2EManager.encryptV2(m1, s1.messageKey, E2EManager.buildAAD(1, 0, s1.sequence, "a", sid, E2EManager.publicKeyToString(s1.ratchetPublicKey)))
            val c2 = E2EManager.encryptV2(m2, s2.messageKey, E2EManager.buildAAD(1, 0, s2.sequence, "a", sid, E2EManager.publicKeyToString(s2.ratchetPublicKey)))
            val c3 = E2EManager.encryptV2(m3, s3.messageKey, E2EManager.buildAAD(1, 0, s3.sequence, "a", sid, E2EManager.publicKeyToString(s3.ratchetPublicKey)))

            // Bob receives 3, then 1, then 2
            val aad3 = E2EManager.buildAAD(1, 0, s3.sequence, "a", sid, E2EManager.publicKeyToString(s3.ratchetPublicKey))
            val dec3 = bobSession.tryDecrypt(s3.ratchetPublicKey, s3.sequence, c3, aad3) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals(m3, dec3)

            val aad1 = E2EManager.buildAAD(1, 0, s1.sequence, "a", sid, E2EManager.publicKeyToString(s1.ratchetPublicKey))
            val dec1 = bobSession.tryDecrypt(s1.ratchetPublicKey, s1.sequence, c1, aad1) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals(m1, dec1)

            val aad2 = E2EManager.buildAAD(1, 0, s2.sequence, "a", sid, E2EManager.publicKeyToString(s2.ratchetPublicKey))
            val dec2 = bobSession.tryDecrypt(s2.ratchetPublicKey, s2.sequence, c2, aad2) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals(m2, dec2)
        }
    }

    @Test
    fun testHandshakeDoSProtectionAtomic() {
        runBlocking {
            val maxGlobal = 10
            val manager = HandshakeManager(
                maxPendingPerPeer = 2,
                maxGlobalPending = maxGlobal,
                timeProvider = { 1000L }
            )
            val eKP = E2EManager.generateEphemeralKeyPair()
            val n = ByteArray(16)

            val results = (1..100).map { i ->
                async {
                    manager.addPending("id_$i", PendingHandshake("peer_$i", eKP, n, createdAt = 1000L))
                }
            }.awaitAll()

            assertEquals(maxGlobal, results.count { it })
        }
    }
}
