package com.p2p.supermaster.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.p2p.supermaster.util.Constants
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SuperMaster E2EE Manager - Hardened V3.
 */
object E2EManager {
    private const val TAG = "E2EManager"
    private const val MASTER_KEY_ALIAS = "SuperHardwareKeyV3"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private val argon2 = Argon2Kt()

    // --- HARDWARE KEYSTORE ---

    fun encryptWithHardwareKey(plainText: String): String {
        return try {
            val secretKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val enc = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            val combined = ByteArray(iv.size + enc.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(enc, 0, combined, iv.size, enc.size)
            Base64.getEncoder().encodeToString(combined)
        } catch (e: Exception) {
            Log.e(TAG, "Hardware encryption failed", e)
            throw e
        }
    }

    fun decryptWithHardwareKey(encB64: String): String {
        return try {
            val comb = Base64.getDecoder().decode(encB64)
            val iv = comb.sliceArray(0 until Constants.GCM_IV_LENGTH)
            val enc = comb.sliceArray(Constants.GCM_IV_LENGTH until comb.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateMasterKey(),
                GCMParameterSpec(Constants.GCM_TAG_LENGTH, iv),
            )
            String(cipher.doFinal(enc), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Hardware decryption failed", e)
            throw e
        }
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(MASTER_KEY_ALIAS)) {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            kg.init(
                KeyGenParameterSpec.Builder(
                    MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(Constants.AES_KEY_SIZE).build(),
            )
            return kg.generateKey()
        }
        return (ks.getEntry(MASTER_KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    fun deleteMasterKey() {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
                if (containsAlias(MASTER_KEY_ALIAS)) deleteEntry(MASTER_KEY_ALIAS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete master key", e)
        }
    }

    // --- DETERMINISTIC IDENTITY (HKDF-SHA256) ---

    fun deriveIdentityKeyPair(entropy: ByteArray): KeyPair {
        val info = "TorSuperMaster/identity/ed25519/v1".toByteArray()
        val derivedSeed = HKDF.deriveKey(entropy, null, info, Constants.ARGON2_HASH_LENGTH)
        val kg = KeyPairGenerator.getInstance(Constants.ED25519_ALGO)
        val sr =
            object : SecureRandom() {
                private var pos = 0

                override fun nextBytes(bytes: ByteArray) {
                    for (i in bytes.indices) {
                        bytes[i] = derivedSeed[pos % derivedSeed.size]
                        pos++
                    }
                }
            }
        kg.initialize(Constants.AES_KEY_SIZE, sr)
        return kg.generateKeyPair()
    }

    fun hashPassword(p: String): String {
        val s = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        return argon2.hash(
            Argon2Mode.ARGON2_ID,
            p.toByteArray(),
            s,
            Constants.ARGON2_ITERATIONS,
            Constants.ARGON2_MEMORY,
            Constants.ARGON2_PARALLELISM,
            Constants.ARGON2_HASH_LENGTH,
        ).encodedOutputAsString()
    }

    fun verifyPassword(
        p: String,
        h: String,
    ): Boolean =
        try {
            argon2.verify(Argon2Mode.ARGON2_ID, h, p.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Password verification failed", e)
            false
        }

    fun encrypt(
        txt: String,
        sk: SecretKey,
    ): String {
        val iv = ByteArray(Constants.GCM_IV_LENGTH).apply { SecureRandom().nextBytes(this) }
        val cp = Cipher.getInstance("AES/GCM/NoPadding")
        cp.init(Cipher.ENCRYPT_MODE, sk, GCMParameterSpec(Constants.GCM_TAG_LENGTH, iv))
        val enc = cp.doFinal(txt.toByteArray(StandardCharsets.UTF_8))
        val res = ByteArray(iv.size + enc.size)
        System.arraycopy(iv, 0, res, 0, iv.size)
        System.arraycopy(enc, 0, res, iv.size, enc.size)
        return Base64.getEncoder().encodeToString(res)
    }

    fun decrypt(
        encB64: String,
        sk: SecretKey,
    ): String {
        val comb = Base64.getDecoder().decode(encB64)
        val iv = comb.sliceArray(0 until Constants.GCM_IV_LENGTH)
        val enc = comb.sliceArray(Constants.GCM_IV_LENGTH until comb.size)
        val cp = Cipher.getInstance("AES/GCM/NoPadding")
        cp.init(Cipher.DECRYPT_MODE, sk, GCMParameterSpec(Constants.GCM_TAG_LENGTH, iv))
        return String(cp.doFinal(enc), StandardCharsets.UTF_8)
    }

    fun deriveKeyFromSecret(s: String): SecretKeySpec =
        SecretKeySpec(
            MessageDigest.getInstance(Constants.SHA256_ALGO).digest(s.toByteArray(StandardCharsets.UTF_8)),
            "AES",
        )
}
