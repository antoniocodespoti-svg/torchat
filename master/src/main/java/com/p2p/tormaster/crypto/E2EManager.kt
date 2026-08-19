package com.p2p.tormaster.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.nio.charset.StandardCharsets
import java.security.*
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class E2EManager {
    companion object {
        private const val AES_GCM_TAG_LENGTH = 128
        private const val MASTER_KEY_ALIAS = "MasterHardwareKeyV3"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private val argon2 = Argon2Kt()

        fun encryptWithHardwareKey(plainText: String): String {
            val secretKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv; val enc = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            val combined = ByteArray(iv.size + enc.size); System.arraycopy(iv, 0, combined, 0, iv.size); System.arraycopy(enc, 0, combined, iv.size, enc.size)
            return Base64.getEncoder().encodeToString(combined)
        }

        fun decryptWithHardwareKey(encB64: String): String {
            val comb = Base64.getDecoder().decode(encB64); val iv = comb.sliceArray(0 until 12); val enc = comb.sliceArray(12 until comb.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(AES_GCM_TAG_LENGTH, iv))
            return String(cipher.doFinal(enc), StandardCharsets.UTF_8)
        }

        private fun getOrCreateMasterKey(): SecretKey {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!ks.containsAlias(MASTER_KEY_ALIAS)) {
                val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                kg.init(KeyGenParameterSpec.Builder(MASTER_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
                return kg.generateKey()
            }
            return (ks.getEntry(MASTER_KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }

        fun deleteMasterKey() { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null); if (containsAlias(MASTER_KEY_ALIAS)) deleteEntry(MASTER_KEY_ALIAS) } }
        fun hashPassword(p: String): String { val s = ByteArray(16).apply { SecureRandom().nextBytes(this) }; return argon2.hash(Argon2Mode.ARGON2_ID, p.toByteArray(), s, 2, 65536, 1, 32).encodedOutputAsString() }
        fun verifyPassword(p: String, h: String): Boolean = try { argon2.verify(Argon2Mode.ARGON2_ID, h, p.toByteArray()) } catch (e: Exception) { false }

        fun encrypt(txt: String, sk: SecretKey): String {
            val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }; val cp = Cipher.getInstance("AES/GCM/NoPadding")
            cp.init(Cipher.ENCRYPT_MODE, sk, GCMParameterSpec(128, iv)); val enc = cp.doFinal(txt.toByteArray(StandardCharsets.UTF_8))
            val res = ByteArray(iv.size + enc.size); System.arraycopy(iv, 0, res, 0, iv.size); System.arraycopy(enc, 0, res, iv.size, enc.size)
            return Base64.getEncoder().encodeToString(res)
        }

        fun decrypt(encB64: String, sk: SecretKey): String {
            val comb = Base64.getDecoder().decode(encB64); val iv = comb.sliceArray(0 until 12); val enc = comb.sliceArray(12 until comb.size)
            val cp = Cipher.getInstance("AES/GCM/NoPadding"); cp.init(Cipher.DECRYPT_MODE, sk, GCMParameterSpec(128, iv))
            return String(cp.doFinal(enc), StandardCharsets.UTF_8)
        }

        fun deriveKeyFromSecret(s: String): SecretKeySpec = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8)), "AES")
    }
}
