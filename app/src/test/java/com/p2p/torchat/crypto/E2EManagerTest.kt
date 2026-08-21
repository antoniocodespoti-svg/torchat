package com.p2p.torchat.crypto

import org.junit.Assert.*
import org.junit.Test

class E2EManagerTest {
    @Test
    fun testEncryptionAndDecryption() {
        val salt = ByteArray(16)
        val secretKey = E2EManager.deriveKeyFromSecret("TestSecretKey2026", salt)
        val originalMessage = "Ciao! Questo è un messaggio segreto P2P via Tor .onion!"

        val encryptedBase64 = E2EManager.encrypt(originalMessage, secretKey)
        assertNotNull(encryptedBase64)
        assertNotEquals(originalMessage, encryptedBase64)

        val decryptedMessage = E2EManager.decrypt(encryptedBase64, secretKey)
        assertEquals(originalMessage, decryptedMessage)
    }

    @Test(expected = Exception::class)
    fun testTamperedPayloadFailsDecryption() {
        val salt = ByteArray(16)
        val secretKey = E2EManager.deriveKeyFromSecret("TestSecretKey2026", salt)
        val originalMessage = "Test Payload"

        val encryptedBase64 = E2EManager.encrypt(originalMessage, secretKey)
        val tamperedBase64 = encryptedBase64.substring(0, encryptedBase64.length - 4) + "AAAA"

        E2EManager.decrypt(tamperedBase64, secretKey)
    }
}
