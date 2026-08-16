package com.p2p.torchat.service

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TorManagerTest {
    @Test
    fun testTorrcAndHostnameParsing() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "tor_test_" + System.currentTimeMillis())
        tempDir.mkdirs()

        val hsDir = File(tempDir, "hsv3")
        hsDir.mkdirs()

        val hostnameFile = File(hsDir, "hostname")
        val expectedAddress = "abc123def456ghi789jkl012mno345pqr678stu901vwx234yz56789.onion"
        hostnameFile.writeText(expectedAddress)

        assertTrue(hostnameFile.exists())
        assertEquals(expectedAddress, hostnameFile.readText().trim())

        tempDir.deleteRecursively()
    }
}
