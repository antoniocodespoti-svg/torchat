package com.p2p.torchat

import android.content.Context
import com.p2p.torchat.crypto.SecureVault
import com.p2p.torchat.crypto.VaultData
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec

class SecureVaultTest {

    @Test
    fun testVaultJournalingRecovery() {
        val context = mock(Context::class.java)
        val tempDir = Files.createTempDirectory("vault_journal_test").toFile()
        `when`(context.filesDir).thenReturn(tempDir)

        val data = VaultData(myAlias = "JournalUser")
        val entropy = ByteArray(16) { 1 }
        val key = SecretKeySpec(ByteArray(32) { 2 }, "AES")

        // 1. Manually create a PENDING file without a VAULT file (simulating crash during move)
        val envelope = com.p2p.torchat.crypto.VaultEnvelope(data, entropy)
        val json = com.google.gson.Gson().toJson(envelope)
        val encrypted = com.p2p.torchat.crypto.E2EManager.encrypt(json, key)

        val pendingFile = File(tempDir, "vault.json.enc.pending")
        pendingFile.writeText(encrypted)

        val vaultFile = File(tempDir, "vault.json.enc")
        assertTrue(!vaultFile.exists())

        // 2. Load should recover from pending
        val loaded = SecureVault.load(context, key)
        assertNotNull(loaded)
        assertTrue(loaded!!.first.myAlias == "JournalUser")
        assertTrue(vaultFile.exists())
        assertTrue(!pendingFile.exists())

        tempDir.deleteRecursively()
    }
}
