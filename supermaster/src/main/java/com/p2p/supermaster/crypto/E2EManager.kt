package com.p2p.supermaster.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.nio.charset.StandardCharsets
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object E2EManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private val argon2 by lazy { Argon2Kt() }

    fun encryptWithHardwareKey(plainText: String): String {
        return try {
            val secretKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size); System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
            Base64.getEncoder().encodeToString(combined)
        } catch (e: Exception) { "" }
    }

    fun decryptWithHardwareKey(encB64: String): String {
        return try {
            val combined = Base64.getDecoder().decode(encB64)
            val iv = combined.sliceArray(0 until 12); val encryptedBytes = combined.sliceArray(12 until combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8)
        } catch (e: Exception) { "" }
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val alias = "supermaster_hardware_key"
        if (!ks.containsAlias(alias)) {
            val kg = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
            kg.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            return kg.generateKey()
        }
        return (ks.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
    }

    fun deleteMasterKey() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.deleteEntry("supermaster_hardware_key")
    }

    fun hashPassword(p: String): String {
        val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        return argon2.hash(Argon2Mode.ARGON2_ID, p.toByteArray(), salt, 2, 65536, 1, 32).encodedOutputAsString()
    }

    fun verifyPassword(p: String, h: String): Boolean = try { argon2.verify(Argon2Mode.ARGON2_ID, h, p.toByteArray()) } catch (e: Exception) { false }

    fun deriveIdentityKeyPair(entropy: ByteArray): KeyPair {
        val seed = MessageDigest.getInstance("SHA-256").digest(entropy)
        val kg = KeyPairGenerator.getInstance("Ed25519")
        val sr = object : SecureRandom() {
            private var pos = 0
            override fun nextBytes(bytes: ByteArray) {
                for (i in bytes.indices) { bytes[i] = seed[pos % seed.size]; pos++ }
            }
        }
        kg.initialize(256, sr)
        return kg.generateKeyPair()
    }

    fun publicKeyToString(pk: PublicKey): String = Base64.getEncoder().encodeToString(pk.encoded)

    fun deriveKeyFromSecret(s: String): SecretKeySpec = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8)), "AES")

    fun encrypt(txt: String, sk: SecretKey): String {
        val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        val cp = Cipher.getInstance("AES/GCM/NoPadding")
        cp.init(Cipher.ENCRYPT_MODE, sk, GCMParameterSpec(128, iv))
        val enc = cp.doFinal(txt.toByteArray(StandardCharsets.UTF_8))
        val res = ByteArray(iv.size + enc.size)
        System.arraycopy(iv, 0, res, 0, iv.size); System.arraycopy(enc, 0, res, iv.size, enc.size)
        return Base64.getEncoder().encodeToString(res)
    }

    fun decrypt(encB64: String, sk: SecretKey): String {
        val comb = Base64.getDecoder().decode(encB64); val iv = comb.sliceArray(0 until 12); val enc = comb.sliceArray(12 until comb.size)
        val cp = Cipher.getInstance("AES/GCM/NoPadding"); cp.init(Cipher.DECRYPT_MODE, sk, GCMParameterSpec(128, iv))
        return String(cp.doFinal(enc), StandardCharsets.UTF_8)
    }
}

