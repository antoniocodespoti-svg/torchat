package com.p2p.torchat

import com.p2p.torchat.crypto.DoubleRatchetSession
import com.p2p.torchat.crypto.E2EManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator

class DoubleRatchetWipeTest {

    @Test
    fun testSessionWipe() = runBlocking {
        val rootKey = ByteArray(32) { 1 }
        val kg = KeyPairGenerator.getInstance("X25519")
        val session = DoubleRatchetSession("test", rootKey, kg.generateKeyPair())

        session.destroy()

        // Use reflection to verify internal fields are wiped (for test only)
        val rootKeyField = DoubleRatchetSession::class.java.getDeclaredField("rootKey")
        rootKeyField.isAccessible = true
        val rootKeyVal = rootKeyField.get(session) as ByteArray

        assertTrue("Root key was not wiped", rootKeyVal.all { it == 0.toByte() })
    }
}
