package com.p2p.torchat.crypto

import com.p2p.torchat.model.PendingHandshake
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

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

            // PFS_INIT Sig (Resolves Audit Point 7)
            val initTranscript = E2EManager.buildInitTranscript(aliceOnion, bobOnion, aliceIKStr, aliceEKStr, aliceNonce)
            val aliceInitSig = E2EManager.signData(initTranscript, aliceIKP.private)

            // --- STEP 2: Bob receives PFS_INIT and responds (PFS_ACCEPT) ---
            // Bob verifies Alice's INIT signature early
            assertTrue(E2EManager.verifySignature(initTranscript, aliceInitSig, aliceIKP.public))

            val bobNonce = ByteArray(16) { 0x22.toByte() }
            val bobEKP = E2EManager.generateEphemeralKeyPair()
            val bobEKStr = E2EManager.publicKeyToString(bobEKP.public)

            // Bob signs transcript: (AliceOnion, BobOnion, AliceIK, AliceEK, BobIK, BobEK, AliceNonce, BobNonce)
            val fullTranscript = E2EManager.buildHandshakeTranscript(aliceOnion, bobOnion, aliceIKStr, aliceEKStr, bobIKStr, bobEKStr, aliceNonce, bobNonce)
            val bobSig = E2EManager.signData(fullTranscript, bobIKP.private)

            // Bob stores pending
            val bobPending = PendingHandshake(aliceOnion, bobEKP, bobNonce, aliceNonce, aliceIKStr, aliceEKStr, 1000L)

            // --- STEP 3: Alice receives PFS_ACCEPT and responds (PFS_FINAL) ---
            // Alice verifies Bob's signature
            assertTrue(E2EManager.verifySignature(fullTranscript, bobSig, bobIKP.public))

            // Alice signs same full transcript
            val aliceFinalSig = E2EManager.signData(fullTranscript, aliceIKP.private)

            // Alice commits session
            val aliceShared = E2EManager.calculateSharedSecret(aliceEKP.private, bobEKP.public)
            val (aliceSend, aliceReceive) = E2EManager.deriveInitialChainKeys(aliceShared, aliceOnion, bobOnion)
            val sid = E2EManager.calculateSessionId(fullTranscript)
            val aliceSession = SymmetricRatchetSession(sid, aliceSend, aliceReceive)

            // --- STEP 4: Bob receives PFS_FINAL and commits ---
            // Bob verifies Alice's final signature
            assertTrue(E2EManager.verifySignature(fullTranscript, aliceFinalSig, aliceIKP.public))

            // Bob commits session
            val bobShared = E2EManager.calculateSharedSecret(bobEKP.private, aliceEKP.public)
            val (bobSend, bobReceive) = E2EManager.deriveInitialChainKeys(bobShared, bobOnion, aliceOnion)
            val bobSession = SymmetricRatchetSession(sid, bobSend, bobReceive)

            // --- VERIFY EXCHANGE ---
            val msg = "Hello authenticated 3-way handshake!"
            val s1 = aliceSession.nextSendKey()
            val aad1 = E2EManager.buildAAD(1, 0, s1.sequence, aliceOnion, sid)
            val enc1 = E2EManager.encryptV2(msg, s1.key, aad1)

            val dec1 = bobSession.tryDecrypt(1, enc1, aad1) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals(msg, dec1)
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

            // 100 concurrent attempts to add handshakes for different peers
            val results = (1..100).map { i ->
                async {
                    manager.addPending("id_$i", PendingHandshake("peer_$i", eKP, n, createdAt = 1000L))
                }
            }.awaitAll()

            // Verify global limit is strictly enforced (exactly maxGlobal success)
            assertEquals(maxGlobal, results.count { it })
            assertEquals(maxGlobal, manager.getPendingCount())
        }
    }

    @Test
    fun testHandshakePerPeerLimitAtomic() {
        runBlocking {
            val maxPerPeer = 3
            val manager = HandshakeManager(
                maxPendingPerPeer = maxPerPeer,
                maxGlobalPending = 50,
                timeProvider = { 1000L }
            )
            val eKP = E2EManager.generateEphemeralKeyPair()
            val n = ByteArray(16)

            // 100 concurrent attempts for the SAME peer
            val results = (1..100).map { i ->
                async {
                    manager.addPending("id_$i", PendingHandshake("samePeer", eKP, n, createdAt = 1000L))
                }
            }.awaitAll()

            // Verify per-peer limit is strictly enforced
            assertEquals(maxPerPeer, results.count { it })
        }
    }

    @Test
    fun testHandshakeNoOverwrite() {
        val manager = HandshakeManager(timeProvider = { 1000L })
        val eKP1 = E2EManager.generateEphemeralKeyPair()
        val eKP2 = E2EManager.generateEphemeralKeyPair()
        val n = ByteArray(16) { 0x11.toByte() }
        val id = "same_id"

        assertTrue(manager.addPending(id, PendingHandshake("peer", eKP1, n, createdAt = 1000L)))
        // Attempt to overwrite with same ID must fail
        assertFalse(manager.addPending(id, PendingHandshake("peer", eKP2, n, createdAt = 1000L)))

        // Verify original state preserved
        val retrieved = manager.getAndRemove(id)
        assertEquals(eKP1.public, retrieved?.myEphemeralKeys?.public)
    }

    @Test
    fun testHandshakeTimeoutMonotonic() {
        var currentTime = 1000L
        val manager = HandshakeManager(timeoutMs = 5000L, timeProvider = { currentTime })
        val eKP = E2EManager.generateEphemeralKeyPair()

        manager.addPending("h1", PendingHandshake("peer", eKP, ByteArray(16), createdAt = 1000L))

        currentTime = 7000L // 6 seconds later
        assertNull(manager.getAndRemove("h1"))
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

            val ciphertexts = (1..messageCount).map { i ->
                async {
                    val content = "Message $i"
                    val sendResult = aliceSession.nextSendKey()
                    val aad = E2EManager.buildAAD(1, 0, sendResult.sequence, aliceOnion, sid)
                    val enc = E2EManager.encryptV2(content, sendResult.key, aad)
                    Triple(sendResult.sequence, enc, aad)
                }
            }.awaitAll()

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
            val sharedSecret = ByteArray(32) { 0x42.toByte() }
            val sid = "duplicate-sid"
            val bobSession = SymmetricRatchetSession(sid, ByteArray(32), sharedSecret)

            val k1 = E2EManager.kdfRatchet(sharedSecret, "chain-step").second
            val aad = E2EManager.buildAAD(1, 0, 1, aliceOnion, sid)
            val enc = E2EManager.encryptV2("Duplicate Me", k1, aad)

            val attempts = (1..20).map {
                async {
                    try {
                        bobSession.tryDecrypt(1, enc, aad) { e, k, a -> E2EManager.decryptV2(e, k, a) }
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

            try {
                bobSession.tryDecrypt(10, "bad", E2EManager.buildAAD(1, 0, 10, aliceOnion, sid)) { _, _, _ ->
                    throw Exception("Auth Fail")
                }
            } catch (e: Exception) {
                assertEquals(0, bobSession.receiveSequence)
            }

            val k1 = E2EManager.kdfRatchet(sharedSecret, "chain-step").second
            val aad1 = E2EManager.buildAAD(1, 0, 1, aliceOnion, sid)
            val enc1 = E2EManager.encryptV2("Valid 1", k1, aad1)

            val dec1 = bobSession.tryDecrypt(1, enc1, aad1) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals("Valid 1", dec1)
            assertEquals(1, bobSession.receiveSequence)
        }
    }
}
