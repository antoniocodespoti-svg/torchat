package com.p2p.torchat.crypto

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
            val aliceIKP = E2EManager.generateIdentityKeyPair()
            val bobIKP = E2EManager.generateIdentityKeyPair()
            val aliceOnion = "alice777777777777777777777777777777777777777777777777777.onion"
            val bobOnion = "bob8888888888888888888888888888888888888888888888888888.onion"

            // 2. Alice (Initiator) generates Ephemeral
            val aliceEKP = E2EManager.generateEphemeralKeyPair()
            val aliceEKStr = E2EManager.publicKeyToString(aliceEKP.public)
            val aliceIKStr = E2EManager.publicKeyToString(aliceIKP.public)

            // Alice signs transcript
            val aliceTranscript = E2EManager.buildHandshakeTranscript(aliceOnion, bobOnion, aliceIKStr, aliceEKStr, E2EManager.publicKeyToString(bobIKP.public), "")
            val aliceSig = E2EManager.signData(aliceTranscript, aliceIKP.private)

            // 3. Bob (Responder) receives and verifies
            val bobRebuiltAliceTranscript = E2EManager.buildHandshakeTranscript(aliceOnion, bobOnion, aliceIKStr, aliceEKStr, E2EManager.publicKeyToString(bobIKP.public), "")
            assertTrue(E2EManager.verifySignature(bobRebuiltAliceTranscript, aliceSig, aliceIKP.public))

            // Bob generates his Ephemeral
            val bobEKP = E2EManager.generateEphemeralKeyPair()
            val bobEKStr = E2EManager.publicKeyToString(bobEKP.public)
            val bobIKStr = E2EManager.publicKeyToString(bobIKP.public)

            // Bob signs transcript
            val bobTranscript = E2EManager.buildHandshakeTranscript(aliceOnion, bobOnion, aliceIKStr, aliceEKStr, bobIKStr, bobEKStr)
            val bobSig = E2EManager.signData(bobTranscript, bobIKP.private)

            // Bob derives keys
            val bobShared = E2EManager.calculateSharedSecret(bobEKP.private, aliceEKP.public)
            val (bobSend, bobReceive) = E2EManager.deriveInitialChainKeys(bobShared, bobOnion, aliceOnion)
            val bobSession = SymmetricRatchetSession(bobSend, bobReceive)

            // 4. Alice receives and verifies Bob's response
            val aliceRebuiltBobTranscript = E2EManager.buildHandshakeTranscript(aliceOnion, bobOnion, aliceIKStr, aliceEKStr, bobIKStr, bobEKStr)
            assertTrue(E2EManager.verifySignature(aliceRebuiltBobTranscript, bobSig, bobIKP.public))

            // Alice derives keys
            val aliceShared = E2EManager.calculateSharedSecret(aliceEKP.private, bobEKP.public)
            val (aliceSend, aliceReceive) = E2EManager.deriveInitialChainKeys(aliceShared, aliceOnion, bobOnion)
            val aliceSession = SymmetricRatchetSession(aliceSend, aliceReceive)

            // 5. Exchange Messages
            val msg1 = "Hello Bob!"
            val send1 = aliceSession.nextSendKey()
            val aad1 = E2EManager.buildAAD(1, 0, send1.sequence, aliceOnion)
            val enc1 = E2EManager.encryptV2(msg1, send1.key, aad1)

            val dec1 = bobSession.tryDecrypt(send1.sequence, enc1, aad1) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals(msg1, dec1)

            val msg2 = "Hi Alice!"
            val send2 = bobSession.nextSendKey()
            val aad2 = E2EManager.buildAAD(1, 0, send2.sequence, bobOnion)
            val enc2 = E2EManager.encryptV2(msg2, send2.key, aad2)

            val dec2 = aliceSession.tryDecrypt(send2.sequence, enc2, aad2) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals(msg2, dec2)
        }
    }

    @Test
    fun testOutOrOrderAndSkippedKeys() {
        runBlocking {
            val aliceOnion = "alice777777777777777777777777777777777777777777777777777.onion"
            val bobOnion = "bob8888888888888888888888888888888888888888888888888888.onion"
            val sharedSecret = ByteArray(32) { 0x42.toByte() }

            // Proper directional keys for consistency
            val (aliceSend, aliceReceive) = E2EManager.deriveInitialChainKeys(sharedSecret, aliceOnion, bobOnion)
            val (bobSend, bobReceive) = E2EManager.deriveInitialChainKeys(sharedSecret, bobOnion, aliceOnion)

            val aliceSession = SymmetricRatchetSession(aliceSend, aliceReceive)
            val bobSession = SymmetricRatchetSession(bobSend, bobReceive)

            // Alice sends 3 messages
            val m1 = "Msg 1"; val s1 = aliceSession.nextSendKey(); val c1 = E2EManager.encryptV2(m1, s1.key, E2EManager.buildAAD(1, 0, s1.sequence, aliceOnion))
            val m2 = "Msg 2"; val s2 = aliceSession.nextSendKey(); val c2 = E2EManager.encryptV2(m2, s2.key, E2EManager.buildAAD(1, 0, s2.sequence, aliceOnion))
            val m3 = "Msg 3"; val s3 = aliceSession.nextSendKey(); val c3 = E2EManager.encryptV2(m3, s3.key, E2EManager.buildAAD(1, 0, s3.sequence, aliceOnion))

            // Bob receives Msg 3 first (out of order)
            val dec3 = bobSession.tryDecrypt(s3.sequence, c3, E2EManager.buildAAD(1, 0, s3.sequence, aliceOnion)) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals(m3, dec3)
            assertEquals(3, bobSession.receiveSequence)

            // Bob receives Msg 1 (skipped)
            val dec1 = bobSession.tryDecrypt(s1.sequence, c1, E2EManager.buildAAD(1, 0, s1.sequence, aliceOnion)) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals(m1, dec1)

            // Bob receives Msg 2 (skipped)
            val dec2 = bobSession.tryDecrypt(s2.sequence, c2, E2EManager.buildAAD(1, 0, s2.sequence, aliceOnion)) { e, k, a -> E2EManager.decryptV2(e, k, a) }
            assertEquals(m2, dec2)
        }
    }

    @Test(expected = SecurityException::class)
    fun testReplayAttack() {
        runBlocking {
            val aliceOnion = "alice777777777777777777777777777777777777777777777777777.onion"
            val bobOnion = "bob8888888888888888888888888888888888888888888888888888.onion"
            val sharedSecret = ByteArray(32) { 0x42.toByte() }

            val (aliceSend, aliceReceive) = E2EManager.deriveInitialChainKeys(sharedSecret, aliceOnion, bobOnion)
            val (bobSend, bobReceive) = E2EManager.deriveInitialChainKeys(sharedSecret, bobOnion, aliceOnion)

            val aliceSession = SymmetricRatchetSession(aliceSend, aliceReceive)
            val bobSession = SymmetricRatchetSession(bobSend, bobReceive)

            val m1 = "Msg 1"; val s1 = aliceSession.nextSendKey(); val c1 = E2EManager.encryptV2(m1, s1.key, E2EManager.buildAAD(1, 0, s1.sequence, aliceOnion))

            // Success 1st time
            bobSession.tryDecrypt(s1.sequence, c1, E2EManager.buildAAD(1, 0, s1.sequence, aliceOnion)) { e, k, a -> E2EManager.decryptV2(e, k, a) }

            // Replay should fail
            bobSession.tryDecrypt(s1.sequence, c1, E2EManager.buildAAD(1, 0, s1.sequence, aliceOnion)) { e, k, a -> E2EManager.decryptV2(e, k, a) }
        }
    }

    @Test
    fun testConcurrentSendsAndReceives() {
        runBlocking {
            val aliceOnion = "alice777777777777777777777777777777777777777777777777777.onion"
            val bobOnion = "bob8888888888888888888888888888888888888888888888888888.onion"
            val sharedSecret = ByteArray(32) { 0x42.toByte() }

            val (aliceSend, aliceReceive) = E2EManager.deriveInitialChainKeys(sharedSecret, aliceOnion, bobOnion)
            val (bobSend, bobReceive) = E2EManager.deriveInitialChainKeys(sharedSecret, bobOnion, aliceOnion)

            val aliceSession = SymmetricRatchetSession(aliceSend, aliceReceive)
            val bobSession = SymmetricRatchetSession(bobSend, bobReceive)

            val messageCount = 100

            // Concurrent Send
            val ciphertexts = (1..messageCount).map { i ->
                async {
                    val content = "Message $i"
                    val sendResult = aliceSession.nextSendKey()
                    val aad = E2EManager.buildAAD(1, 0, sendResult.sequence, aliceOnion)
                    val enc = E2EManager.encryptV2(content, sendResult.key, aad)
                    Triple(sendResult.sequence, enc, aad)
                }
            }.awaitAll()

            // Verify sequences are unique and from 1 to 100
            val sequences = ciphertexts.map { it.first }.sorted()
            assertEquals(messageCount, sequences.size)
            assertEquals(1, sequences.first())
            assertEquals(messageCount, sequences.last())

            // Concurrent Receive (random order)
            val results = ciphertexts.shuffled().map { (seq, enc, aad) ->
                async {
                    bobSession.tryDecrypt(seq, enc, aad) { e, k, a -> E2EManager.decryptV2(e, k, a) }
                }
            }.awaitAll()

            assertEquals(messageCount, results.size)
            results.forEach { msg -> assertTrue(msg.startsWith("Message ")) }
        }
    }

    @Test(expected = Exception::class)
    fun testAtomicRatchetRollback() {
        runBlocking {
            val aliceOnion = "alice777777777777777777777777777777777777777777777777777.onion"
            val shared = ByteArray(32) { 0x42.toByte() }
            val bobSession = SymmetricRatchetSession(shared, shared)

            // Try decrypting with wrong key/aad (simulated by sequence mismatch or whatever)
            try {
                bobSession.tryDecrypt(10, "invalidBase64", E2EManager.buildAAD(1, 0, 10, aliceOnion)) { _, _, _ ->
                    throw Exception("Decryption Failed")
                }
            } catch (e: Exception) {
                // Sequence should NOT have advanced
                assertEquals(0, bobSession.receiveSequence)
                throw e
            }
        }
    }
}
