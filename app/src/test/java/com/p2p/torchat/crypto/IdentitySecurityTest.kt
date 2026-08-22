package com.p2p.torchat.crypto

import org.junit.Assert.*
import org.junit.Test

class IdentitySecurityTest {

    @Test
    fun testDeterministicIdentityDerivation() {
        // Seed A
        val seedA = ByteArray(32) { 0x41.toByte() }
        val pairA1 = E2EManager.ed25519KeyPairFromSeed(seedA)
        val pairA2 = E2EManager.ed25519KeyPairFromSeed(seedA)

        // Verify Seed A consistently produces the same Public Key
        val pubA1 = E2EManager.publicKeyToString(pairA1.public)
        val pubA2 = E2EManager.publicKeyToString(pairA2.public)
        assertEquals("Seed A must always produce the same Public Key", pubA1, pubA2)

        // Verify Seed A consistently produces the same Private Key (encoded form)
        val privA1 = pairA1.private.encoded
        val privA2 = pairA2.private.encoded
        assertArrayEquals("Seed A must always produce the same Private Key", privA1, privA2)

        // Seed B
        val seedB = ByteArray(32) { 0x42.toByte() }
        val pairB = E2EManager.ed25519KeyPairFromSeed(seedB)
        val pubB = E2EManager.publicKeyToString(pairB.public)

        // Verify different seeds produce different keys
        assertNotEquals("Different seeds must produce different Public Keys", pubA1, pubB)
    }

    @Test
    fun testIdentitySeedGeneration() {
        val seed1 = E2EManager.generateIdentitySeed()
        val seed2 = E2EManager.generateIdentitySeed()

        assertEquals(32, seed1.size)
        assertEquals(32, seed2.size)
        assertFalse("Generated seeds should be random", seed1.contentEquals(seed2))
    }

    @Test
    fun testMnemonicToIdentityDerivation() {
        val mnemonic = listOf("abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "abandon", "about")
        val entropy = MnemonicManager.mnemonicToEntropy(mnemonic)!!

        val identitySeed1 = HKDF.deriveKey(entropy, null, "TorChat/V1/IdentitySeed".toByteArray(), 32)
        val pair1 = E2EManager.ed25519KeyPairFromSeed(identitySeed1)

        val identitySeed2 = HKDF.deriveKey(entropy, null, "TorChat/V1/IdentitySeed".toByteArray(), 32)
        val pair2 = E2EManager.ed25519KeyPairFromSeed(identitySeed2)

        assertEquals(E2EManager.publicKeyToString(pair1.public), E2EManager.publicKeyToString(pair2.public))
    }
}
