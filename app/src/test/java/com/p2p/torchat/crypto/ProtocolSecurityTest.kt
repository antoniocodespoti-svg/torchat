package com.p2p.torchat.crypto

import com.p2p.torchat.model.PendingHandshake
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProtocolSecurityTest {

    @Test
    fun testAliceAndBobFullHandshakeAndExchange() {
        runBlocking {
            // 1. Setup Identities
            val aliceIKP = E2EManager.ed25519KeyPairFromSeed(E2EManager.generateIdentitySeed())
            val bobIKP = E2EManager.ed25519KeyPairFromSeed(E2EManager.generateIdentitySeed())
            val aliceOnion = "alice777777777777777777777777777777777777777777777777777.onion"
            val bobOnion = "bob8888888888888888888888888888888888888888888888888888.onion"

            val aliceIKStr = E2EManager.publicKeyToString(aliceIKP.public)
            val bobIKStr = E2EManager.publicKeyToString(bobIKP.public)

            // --- STEP 1: Alice -> Bob (PFS_INIT) ---
            val aliceEKP = E2EManager.generateEphemeralKeyPair()
            val aliceEKStr = E2EManager.publicKeyToString(aliceEKP.public)
            val aliceNonce = ByteArray(16) { 0x11.toByte() }

            // Alice signs PFS_INIT
            val initTranscript = E2EManager.buildInitTranscript(aliceOnion, bobOnion, aliceIKStr, aliceEKStr, aliceNonce)
            val aliceInitSig = E2EManager.signData(initTranscript, aliceIKP.private)

            // --- STEP 2: Bob receives PFS_INIT and responds (PFS_ACCEPT) ---
            assertTrue(E2EManager.verifySignature(initTranscript, aliceInitSig, aliceIKP.public))

            val bobNonce = ByteArray(16) { 0x22.toByte() }
            val bobEKP = E2EManager.generateEphemeralKeyPair()
            val bobEKStr = E2EManager.publicKeyToString(bobEKP.public)

            // Bob signs full transcript
            val fullTranscript = E2EManager.buildHandshakeTranscript(aliceOnion, bobOnion, aliceIKStr, aliceEKStr, bobIKStr, bobEKStr, aliceNonce, bobNonce)
            val bobSig = E2EManager.signData(fullTranscript, bobIKP.private)

            // --- STEP 3: Alice receives PFS_ACCEPT and responds (PFS_FINAL) ---
            assertTrue(E2EManager.verifySignature(fullTranscript, bobSig, bobIKP.public))
            val aliceFinalSig = E2EManager.signData(fullTranscript, aliceIKP.private)

            // Alice commits session (Initiator)
            val aliceShared = E2EManager.calculateSharedSecret(aliceEKP.private, bobEKP.public)
            val sid = E2EManager.calculateSessionId(fullTranscript)
            val aliceSession = DoubleRatchetSession(sid, aliceShared, aliceEKP, bobEKP.public)

            // --- STEP 4: Bob receives PFS_FINAL and commits ---
            assertTrue(E2EManager.verifySignature(fullTranscript, aliceFinalSig, aliceIKP.public))
            val bobShared = E2EManager.calculateSharedSecret(bobEKP.private, aliceEKP.public)
            val bobSession = DoubleRatchetSession(sid, bobShared, bobEKP)
            bobSession.BobInit(aliceEKP.public)

            // --- VERIFY EXCHANGE ---
            val msg = "Hello authenticated 3-way handshake!"
            val sendResult = aliceSession.nextSendKey()
            val rpkStr = E2EManager.publicKeyToString(sendResult.header.ratchetPublicKey)
            val aad = E2EManager.buildAAD(1, 0, 1, aliceOnion, sid, rpkStr, sendResult.header.pn, sendResult.header.n)
            val enc = E2EManager.encryptV2(msg, sendResult.messageKey, aad)

            val dec = bobSession.tryDecrypt(sendResult.header, enc, aad) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals(msg, dec)
        }
    }

    @Test
    fun testConcurrentSendsAndReceives() {
        runBlocking {
            val aliceOnion = "alice777777777777777777777777777777777777777777777777777.onion"
            val sharedSecret = ByteArray(32) { 0x42.toByte() }
            val sid = "concurrent-sid"

            val aliceEK = E2EManager.generateEphemeralKeyPair()
            val bobEK = E2EManager.generateEphemeralKeyPair()

            val aliceSession = DoubleRatchetSession(sid, sharedSecret, aliceEK, bobEK.public)
            val bobSession = DoubleRatchetSession(sid, sharedSecret, bobEK)
            bobSession.BobInit(aliceEK.public)

            val messageCount = 100

            val ciphertexts = (1..messageCount).map { i ->
                async {
                    val content = "Message $i"
                    val sendRes = aliceSession.nextSendKey()
                    val rpkStr = E2EManager.publicKeyToString(sendRes.header.ratchetPublicKey)
                    val aad = E2EManager.buildAAD(1, 0, i, aliceOnion, sid, rpkStr, sendRes.header.pn, sendRes.header.n)
                    val enc = E2EManager.encryptV2(content, sendRes.messageKey, aad)
                    Triple(sendRes.header, enc, aad)
                }
            }.awaitAll()

            val results = ciphertexts.shuffled().map { (header, enc, aad) ->
                async {
                    bobSession.tryDecrypt(header, enc, aad) { e, k, a -> E2EManager.decryptV2(e, k, a) }
                }
            }.awaitAll()

            assertEquals(messageCount, results.size)
            results.forEach { msg -> assertTrue(msg.startsWith("Message ")) }
        }
    }

    @Test
    fun testStrongAtomicRollback() {
        runBlocking {
            val aliceOnion = "alice.onion"
            val sharedSecret = ByteArray(32) { 0x42.toByte() }
            val sid = "rollback-sid"
            val bobEK = E2EManager.generateEphemeralKeyPair()
            val aliceEK = E2EManager.generateEphemeralKeyPair()

            val bobSession = DoubleRatchetSession(sid, sharedSecret, bobEK)
            bobSession.BobInit(aliceEK.public)

            // Attempt decryption with error
            val rpkStr = E2EManager.publicKeyToString(aliceEK.public)
            val badHeader = DoubleRatchetSession.RatchetHeader(aliceEK.public, 0, 10)
            val aadBad = E2EManager.buildAAD(1, 0, 10, aliceOnion, sid, rpkStr, 0, 10)

            try {
                bobSession.tryDecrypt(badHeader, "bad", aadBad) { _, _, _ ->
                    throw Exception("Auth Fail")
                }
            } catch (_: Exception) { }

            // Verify next valid packet still works
            val aliceSession = DoubleRatchetSession(sid, sharedSecret, aliceEK, bobEK.public)
            val send1 = aliceSession.nextSendKey()
            val rpk1 = E2EManager.publicKeyToString(send1.header.ratchetPublicKey)
            val aad1 = E2EManager.buildAAD(1, 0, 1, aliceOnion, sid, rpk1, send1.header.pn, send1.header.n)
            val enc1 = E2EManager.encryptV2("Valid 1", send1.messageKey, aad1)

            val dec1 = bobSession.tryDecrypt(send1.header, enc1, aad1) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals("Valid 1", dec1)
        }
    }

    @Test
    fun testMessyNetworkRecovery() {
        runBlocking {
            val sharedSecret = ByteArray(32) { 0x77.toByte() }
            val sid = "messy-sid"
            val aliceEK = E2EManager.generateEphemeralKeyPair()
            val bobEK = E2EManager.generateEphemeralKeyPair()

            val alice = DoubleRatchetSession(sid, sharedSecret, aliceEK, bobEK.public)
            val bob = DoubleRatchetSession(sid, sharedSecret, bobEK)
            bob.BobInit(aliceEK.public)

            // 1. Alice sends 5 messages
            val msgs = (1..5).map { "Msg $it" }
            val pkts = msgs.map { m ->
                val s = alice.nextSendKey()
                val rpk = E2EManager.publicKeyToString(s.header.ratchetPublicKey)
                val aad = E2EManager.buildAAD(1, 0, 0, "a", sid, rpk, s.header.pn, s.header.n)
                val enc = E2EManager.encryptV2(m, s.messageKey, aad)
                Triple(s.header, enc, aad)
            }

            // 2. Bob receives only 1 and 5 (2,3,4 are "lost")
            assertEquals("Msg 1", bob.tryDecrypt(pkts[0].first, pkts[0].second, pkts[0].third) { e, k, a -> E2EManager.decryptV2(e, k, a) })
            assertEquals("Msg 5", bob.tryDecrypt(pkts[4].first, pkts[4].second, pkts[4].third) { e, k, a -> E2EManager.decryptV2(e, k, a) })

            // 3. Bob responds (triggers DH ratchet)
            val sB = bob.nextSendKey()
            val rpkB = E2EManager.publicKeyToString(sB.header.ratchetPublicKey)
            val aadB = E2EManager.buildAAD(1, 0, 0, "b", sid, rpkB, sB.header.pn, sB.header.n)
            val encB = E2EManager.encryptV2("Bob Reply", sB.messageKey, aadB)

            // 4. Alice receives Bob's reply
            assertEquals("Bob Reply", alice.tryDecrypt(sB.header, encB, aadB) { e, k, a -> E2EManager.decryptV2(e, k, a) })

            // 5. Bob finally receives the "delayed" Msg 3
            assertEquals("Msg 3", bob.tryDecrypt(pkts[2].first, pkts[2].second, pkts[2].third) { e, k, a -> E2EManager.decryptV2(e, k, a) })
        }
    }
}
