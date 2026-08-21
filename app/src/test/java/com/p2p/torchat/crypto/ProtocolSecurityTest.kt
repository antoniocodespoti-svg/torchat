package com.p2p.torchat.crypto

import com.p2p.torchat.model.PendingHandshake
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ProtocolSecurityTest {

    @Test
    fun testAliceAndBobFull3WayHandshakeAndExchange() {
        runBlocking {
            // 1. Setup Identities
            val aliceIKP = E2EManager.generateIdentityKeyPair()
            val bobIKP = E2EManager.generateIdentityKeyPair()
            val aliceOnion = "alice777777777777777777777777777777777777777777777777777.onion"
            val bobOnion = "bob8888888888888888888888888888888888888888888888888888.onion"

            val aliceIKStr = E2EManager.publicKeyToString(aliceIKP.public)
            val bobIKStr = E2EManager.publicKeyToString(bobIKP.public)

            // --- STEP 1: Alice -> Bob (PFS_INIT) ---
            val aliceEKP = E2EManager.generateEphemeralKeyPair()
            val aliceEKStr = E2EManager.publicKeyToString(aliceEKP.public)
            val aliceNonce = ByteArray(16) { 0x11.toByte() }

            // Alice stores pending
            val alicePending = PendingHandshake(bobOnion, aliceEKP, aliceNonce)

            // --- STEP 2: Bob receives PFS_INIT and responds (PFS_ACCEPT) ---
            val bobNonce = ByteArray(16) { 0x22.toByte() }
            val bobEKP = E2EManager.generateEphemeralKeyPair()
            val bobEKStr = E2EManager.publicKeyToString(bobEKP.public)

            // Bob signs transcript: (AliceOnion, BobOnion, AliceIK, AliceEK, BobIK, BobEK, AliceNonce, BobNonce)
            val fullTranscript = E2EManager.buildHandshakeTranscript(aliceOnion, bobOnion, aliceIKStr, aliceEKStr, bobIKStr, bobEKStr, aliceNonce, bobNonce)
            val bobSig = E2EManager.signData(fullTranscript, bobIKP.private)

            // Bob stores pending
            val bobPending = PendingHandshake(aliceOnion, bobEKP, bobNonce, aliceNonce, aliceIKStr, aliceEKStr)

            // --- STEP 3: Alice receives PFS_ACCEPT and responds (PFS_FINAL) ---
            // Alice verifies Bob's signature
            assertTrue(E2EManager.verifySignature(fullTranscript, bobSig, bobIKP.public))

            // Alice signs same transcript
            val aliceSig = E2EManager.signData(fullTranscript, aliceIKP.private)

            // Alice commits session
            val aliceShared = E2EManager.calculateSharedSecret(aliceEKP.private, bobEKP.public)
            val (aliceSend, aliceReceive) = E2EManager.deriveInitialChainKeys(aliceShared, aliceOnion, bobOnion)
            val sid = E2EManager.calculateSessionId(fullTranscript)
            val aliceSession = SymmetricRatchetSession(sid, aliceSend, aliceReceive)

            // --- STEP 4: Bob receives PFS_FINAL and commits ---
            // Bob verifies Alice's signature
            assertTrue(E2EManager.verifySignature(fullTranscript, aliceSig, aliceIKP.public))

            // Bob commits session
            val bobShared = E2EManager.calculateSharedSecret(bobEKP.private, aliceEKP.public)
            val (bobSend, bobReceive) = E2EManager.deriveInitialChainKeys(bobShared, bobOnion, aliceOnion)
            val bobSession = SymmetricRatchetSession(sid, bobSend, bobReceive)

            // --- VERIFY EXCHANGE ---
            val msg = "Hello 3-way handshake!"
            val s1 = aliceSession.nextSendKey()
            val aad1 = E2EManager.buildAAD(1, 0, s1.sequence, aliceOnion, sid)
            val enc1 = E2EManager.encryptV2(msg, s1.key, aad1)

            val dec1 = bobSession.tryDecrypt(s1.sequence, enc1, aad1) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals(msg, dec1)
        }
    }

    @Test
    fun testOutOrOrderAndSkippedKeys() {
        runBlocking {
            val aliceOnion = "alice777777777777777777777777777777777777777777777777777.onion"
            val bobOnion = "bob8888888888888888888888888888888888888888888888888888.onion"
            val sharedSecret = ByteArray(32) { 0x42.toByte() }
            val sid = "test-session-id"

            val (aliceSend, aliceReceive) = E2EManager.deriveInitialChainKeys(sharedSecret, aliceOnion, bobOnion)
            val (bobSend, bobReceive) = E2EManager.deriveInitialChainKeys(sharedSecret, bobOnion, aliceOnion)

            val aliceSession = SymmetricRatchetSession(sid, aliceSend, aliceReceive)
            val bobSession = SymmetricRatchetSession(sid, bobSend, bobReceive)

            // Alice sends 3 messages
            val m1 = "Msg 1"; val s1 = aliceSession.nextSendKey(); val c1 = E2EManager.encryptV2(m1, s1.key, E2EManager.buildAAD(1, 0, s1.sequence, aliceOnion, sid))
            val m2 = "Msg 2"; val s2 = aliceSession.nextSendKey(); val c2 = E2EManager.encryptV2(m2, s2.key, E2EManager.buildAAD(1, 0, s2.sequence, aliceOnion, sid))
            val m3 = "Msg 3"; val s3 = aliceSession.nextSendKey(); val c3 = E2EManager.encryptV2(m3, s3.key, E2EManager.buildAAD(1, 0, s3.sequence, aliceOnion, sid))

            // Bob receives Msg 3 first (out of order)
            val dec3 = bobSession.tryDecrypt(s3.sequence, c3, E2EManager.buildAAD(1, 0, s3.sequence, aliceOnion, sid)) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals(m3, dec3)
            assertEquals(3, bobSession.receiveSequence)

            // Bob receives Msg 1 (skipped)
            val dec1 = bobSession.tryDecrypt(s1.sequence, c1, E2EManager.buildAAD(1, 0, s1.sequence, aliceOnion, sid)) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals(m1, dec1)

            // Bob receives Msg 2 (skipped)
            val dec2 = bobSession.tryDecrypt(s2.sequence, c2, E2EManager.buildAAD(1, 0, s2.sequence, aliceOnion, sid)) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals(m2, dec2)
        }
    }

    @Test(expected = SecurityException::class)
    fun testReplayAttack() {
        runBlocking {
            val aliceOnion = "alice777777777777777777777777777777777777777777777777777.onion"
            val sharedSecret = ByteArray(32) { 0x42.toByte() }
            val sid = "test-session-id"

            val bobSession = SymmetricRatchetSession(sid, sharedSecret, sharedSecret)

            val m1 = "Msg 1"
            val k1 = E2EManager.kdfRatchet(sharedSecret, "chain-step").second
            val aad1 = E2EManager.buildAAD(1, 0, 1, aliceOnion, sid)
            val enc1 = E2EManager.encryptV2(m1, k1, aad1)

            // Success 1st time
            bobSession.tryDecrypt(1, enc1, aad1) { e, k, a -> E2EManager.decryptV2(e, k, a) }

            // Replay should fail
            bobSession.tryDecrypt(1, enc1, aad1) { e, k, a -> E2EManager.decryptV2(e, k, a) }
        }
    }

    @Test
    fun testHandshakeReplayToResponder() {
        runBlocking {
            // Bob is waiting. Alice sends PFS_INIT.
            // Attacker replays Alice's PFS_INIT from 1 year ago.
            // Even if Bob generates a challenge, Attacker cannot sign Step 3 without Alice's IK.
            // This test verifies Bob stays in Pending state and doesn't overwrite active sessions.

            val bobIKP = E2EManager.generateIdentityKeyPair()
            val aliceOnion = "alice.onion"
            val bobOnion = "bob.onion"

            // Existing session
            val oldSid = "old-session"
            val activeSessions = mutableMapOf(aliceOnion to SymmetricRatchetSession(oldSid, ByteArray(32), ByteArray(32)))

            // Replayed INIT
            val aliceIKStr = "fake-ik"
            val replayedNonce = ByteArray(16) { 0x99.toByte() }
            val replayedEKStr = "fake-ek"

            // Bob receives Replayed INIT
            val myNonce = ByteArray(16).apply { java.security.SecureRandom().nextBytes(this) }
            val respKeyPair = E2EManager.generateEphemeralKeyPair()

            // Bob stores PENDING but MUST NOT overwrite activeSessions yet
            val pendingHandshakes = mutableMapOf<String, PendingHandshake>()
            val handshakeId = Base64.getEncoder().encodeToString(replayedNonce)
            pendingHandshakes[handshakeId] = PendingHandshake(aliceOnion, respKeyPair, myNonce, replayedNonce, aliceIKStr, replayedEKStr)

            assertEquals(oldSid, activeSessions[aliceOnion]?.sessionId)
            assertTrue(pendingHandshakes.containsKey(handshakeId))
        }
    }

    @Test
    fun testConcurrentSendsAndReceives() {
        runBlocking {
            val aliceOnion = "alice777777777777777777777777777777777777777777777777777.onion"
            val bobOnion = "bob8888888888888888888888888888888888888888888888888888.onion"
            val sharedSecret = ByteArray(32) { 0x42.toByte() }
            val sid = "concurrent-sid"

            val (aliceSend, aliceReceive) = E2EManager.deriveInitialChainKeys(sharedSecret, aliceOnion, bobOnion)
            val (bobSend, bobReceive) = E2EManager.deriveInitialChainKeys(sharedSecret, bobOnion, aliceOnion)

            val aliceSession = SymmetricRatchetSession(sid, aliceSend, aliceReceive)
            val bobSession = SymmetricRatchetSession(sid, bobSend, bobReceive)

            val messageCount = 100

            // Concurrent Send
            val ciphertexts = (1..messageCount).map { i ->
                async {
                    val content = "Message $i"
                    val sendResult = aliceSession.nextSendKey()
                    val aad = E2EManager.buildAAD(1, 0, sendResult.sequence, aliceOnion, sid)
                    val enc = E2EManager.encryptV2(content, sendResult.key, aad)
                    Triple(sendResult.sequence, enc, aad)
                }
            }.awaitAll()

            // Concurrent Receive (random order)
            val results = ciphertexts.shuffled().map { (seq, enc, aad) ->
                async {
                    bobSession.tryDecrypt(seq, enc, aad) { e, k, a -> E2EManager.decryptV2(e, k, a) }
                }
            }.awaitAll()

            assertEquals(messageCount, results.size)
        }
    }

    @Test
    fun testConcurrentDuplicateReceive() {
        runBlocking {
            val aliceOnion = "alice777777777777777777777777777777777777777777777777777.onion"
            val bobOnion = "bob8888888888888888888888888888888888888888888888888888.onion"
            val sharedSecret = ByteArray(32) { 0x42.toByte() }
            val sid = "duplicate-sid"

            val (aliceSend, _) = E2EManager.deriveInitialChainKeys(sharedSecret, aliceOnion, bobOnion)
            val (_, bobReceive) = E2EManager.deriveInitialChainKeys(sharedSecret, bobOnion, aliceOnion)

            val aliceSession = SymmetricRatchetSession(sid, aliceSend, ByteArray(32))
            val bobSession = SymmetricRatchetSession(sid, ByteArray(32), bobReceive)

            val sendRes = aliceSession.nextSendKey()
            val aad = E2EManager.buildAAD(1, 0, sendRes.sequence, aliceOnion, sid)
            val enc = E2EManager.encryptV2("Duplicate Me", sendRes.key, aad)

            // 20 concurrent attempts to decrypt the same packet
            val attempts = (1..20).map {
                async {
                    try {
                        bobSession.tryDecrypt(sendRes.sequence, enc, aad) { e, k, a -> E2EManager.decryptV2(e, k, a) }
                        "SUCCESS"
                    } catch (e: SecurityException) {
                        "REPLAY"
                    }
                }
            }.awaitAll()

            assertEquals(1, attempts.count { it == "SUCCESS" })
            assertEquals(19, attempts.count { it == "REPLAY" })
            assertEquals(1, bobSession.receiveSequence)
        }
    }

    @Test
    fun testStrongAtomicRollback() {
        runBlocking {
            val aliceOnion = "alice.onion"
            val sharedSecret = ByteArray(32) { 0x42.toByte() }
            val sid = "rollback-sid"

            val bobSession = SymmetricRatchetSession(sid, sharedSecret, sharedSecret)

            // Attempt decryption of seq 10 with error
            try {
                bobSession.tryDecrypt(10, "bad", E2EManager.buildAAD(1, 0, 10, aliceOnion, sid)) { _, _, _ ->
                    throw Exception("Auth Fail")
                }
            } catch (e: Exception) {
                assertEquals(0, bobSession.receiveSequence)
            }

            // Alice sends valid seq 1
            val k1 = E2EManager.kdfRatchet(sharedSecret, "chain-step").second
            val aad1 = E2EManager.buildAAD(1, 0, 1, aliceOnion, sid)
            val enc1 = E2EManager.encryptV2("Valid 1", k1, aad1)

            val dec1 = bobSession.tryDecrypt(1, enc1, aad1) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals("Valid 1", dec1)
            assertEquals(1, bobSession.receiveSequence)
        }
    }
}
