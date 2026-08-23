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
    fun testVaultAtomicSave() {
        val context = mock(Context::class.java)
        val tempDir = Files.createTempDirectory("vault_test").toFile()
        `when`(context.filesDir).thenReturn(tempDir)

        val data = VaultData(myAlias = "TestUser")
        val entropy = ByteArray(16) { 1 }
        val key = SecretKeySpec(ByteArray(32) { 2 }, "AES")

        // 1. Initial Save
        SecureVault.save(context, data, entropy, key)

        val vaultFile = File(tempDir, "vault.json.enc")
        assertTrue(vaultFile.exists())

        // 2. Load and verify
        val loaded = SecureVault.load(context, key)
        assertNotNull(loaded)
        assertTrue(loaded!!.first.myAlias == "TestUser")
        assertTrue(loaded.second.contentEquals(entropy))

        // 3. Verify Backup exists after second save
        val updatedData = data.copy(myAlias = "UpdatedUser")
        SecureVault.save(context, updatedData, entropy, key)

        val backupFile = File(tempDir, "vault.json.enc.bak")
        assertTrue(backupFile.exists())

        val loadedUpdated = SecureVault.load(context, key)
        assertTrue(loadedUpdated!!.first.myAlias == "UpdatedUser")

        tempDir.deleteRecursively()
    }
}
