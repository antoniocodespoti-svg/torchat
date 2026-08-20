package com.p2p.torchat.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.p2p.torchat.util.Constants
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * E2EE v2 Manager - Audit Compliant Version.
 * Implements X25519, Ed25519, and Deterministic Identity.
 */
object E2EManager {
    private const val TAG = "E2EManager"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private val argon2 = Argon2Kt()

    // --- HARDWARE KEYSTORE ---

    fun encryptWithHardwareKey(plainText: String): String {
        return try {
            val secretKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance(Constants.AES_GCM_NOPADDING)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
            Base64.getEncoder().encodeToString(combined)
        } catch (e: Exception) {
            Log.e(TAG, "Hardware encryption failed")
            ""
        }
    }

    fun decryptWithHardwareKey(encB64: String): String {
        return try {
            val combined = Base64.getDecoder().decode(encB64)
            val iv = combined.sliceArray(0 until Constants.GCM_IV_LENGTH)
            val encryptedBytes = combined.sliceArray(Constants.GCM_IV_LENGTH until combined.size)
            val cipher = Cipher.getInstance(Constants.AES_GCM_NOPADDING)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateMasterKey(),
                GCMParameterSpec(Constants.GCM_TAG_LENGTH, iv),
            )
            String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Hardware decryption failed")
            ""
        }
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(Constants.KEY_MASTER_KEY_ALIAS)) {
            val kg = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
            kg.init(
                KeyGenParameterSpec.Builder(
                    Constants.KEY_MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(Constants.AES_KEY_SIZE).build(),
            )
            return kg.generateKey()
        }
        return (ks.getEntry(Constants.KEY_MASTER_KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    fun deleteMasterKey() {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(Constants.KEY_MASTER_KEY_ALIAS)) ks.deleteEntry(Constants.KEY_MASTER_KEY_ALIAS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete master key")
        }
    }

    // --- PASSWORD KDF (Argon2id) ---

    fun deriveKeyFromPassword(
        password: String,
        salt: ByteArray,
    ): SecretKeySpec {
        val result =
            argon2.hash(
                Argon2Mode.ARGON2_ID,
                password.toByteArray(),
                salt,
                Constants.ARGON2_ITERATIONS,
                Constants.ARGON2_MEMORY,
                Constants.ARGON2_PARALLELISM,
                Constants.ARGON2_HASH_LENGTH,
            )
        val raw = ByteArray(Constants.ARGON2_HASH_LENGTH)
        result.rawHash.get(raw)
        return SecretKeySpec(raw, "AES")
    }

    // --- DETERMINISTIC IDENTITY ---

    fun deriveIdentityKeyPair(entropy: ByteArray): KeyPair {
        val info = "TorChat/identity/ed25519/v1".toByteArray()
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

    fun generateIdentityKeyPair(): KeyPair = KeyPairGenerator.getInstance(Constants.ED25519_ALGO).generateKeyPair()

    fun signHandshake(
        data: String,
        priv: PrivateKey,
    ): String {
        val sig = Signature.getInstance(Constants.ED25519_ALGO)
        sig.initSign(priv)
        sig.update(data.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    fun verifyHandshake(
        data: String,
        sigStr: String,
        pub: PublicKey,
    ): Boolean =
        try {
            val sig = Signature.getInstance(Constants.ED25519_ALGO)
            sig.initVerify(pub)
            sig.update(data.toByteArray(StandardCharsets.UTF_8))
            sig.verify(Base64.getDecoder().decode(sigStr))
        } catch (e: Exception) {
            Log.e(TAG, "Handshake verification failed")
            false
        }

    fun getFingerprint(pk: PublicKey): String =
        MessageDigest.getInstance(Constants.SHA256_ALGO).digest(pk.encoded)
            .joinToString("") { "%02X".format(it) }.chunked(4).take(6).joinToString("-")

    // --- KEY EXCHANGE (X25519) ---

    fun generateEphemeralKeyPair(): KeyPair = KeyPairGenerator.getInstance(Constants.X25519_ALGO).generateKeyPair()

    fun calculateSharedSecret(
        priv: PrivateKey,
        pub: PublicKey,
    ): ByteArray {
        val ka = KeyAgreement.getInstance(Constants.XDH_ALGO)
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    // --- RATCHET KDF & ENCRYPTION ---

    fun kdfRatchet(
        key: ByteArray,
        info: String,
    ): Pair<ByteArray, ByteArray> {
        val derived = HKDF.deriveKey(key, null, info.toByteArray(StandardCharsets.UTF_8), 64)
        return derived.sliceArray(0..31) to derived.sliceArray(32..63)
    }

    fun buildAAD(
        version: Byte,
        type: Byte,
        sequenceNumber: Int,
        senderOnion: String,
    ): ByteArray {
        val onionBytes = senderOnion.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(1 + 1 + 4 + onionBytes.size)
        buffer.put(version)
        buffer.put(type)
        buffer.putInt(sequenceNumber)
        buffer.put(onionBytes)
        return buffer.array()
    }

    fun encryptV2(
        plaintext: String,
        key: ByteArray,
        aad: ByteArray,
    ): String {
        val cipher = Cipher.getInstance(Constants.AES_GCM_NOPADDING)
        val iv = ByteArray(Constants.GCM_IV_LENGTH).apply { SecureRandom().nextBytes(this) }
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(Constants.GCM_TAG_LENGTH, iv),
        )
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    fun decryptV2(
        encB64: String,
        key: ByteArray,
        aad: ByteArray,
    ): String {
        val combined = Base64.getDecoder().decode(encB64)
        val iv = combined.sliceArray(0 until Constants.GCM_IV_LENGTH)
        val encrypted = combined.sliceArray(Constants.GCM_IV_LENGTH until combined.size)
        val cipher = Cipher.getInstance(Constants.AES_GCM_NOPADDING)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(Constants.GCM_TAG_LENGTH, iv),
        )
        cipher.updateAAD(aad)
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    // --- UTILS ---

    fun hashPassword(p: String): String {
        val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        return argon2.hash(
            Argon2Mode.ARGON2_ID,
            p.toByteArray(),
            salt,
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
            false
        }

    fun publicKeyToString(pk: PublicKey): String = Base64.getEncoder().encodeToString(pk.encoded)

    fun stringToPublicKey(
        b64: String,
        algo: String,
    ): PublicKey {
        val kb = Base64.getDecoder().decode(b64)
        return KeyFactory.getInstance(algo).generatePublic(X509EncodedKeySpec(kb))
    }

    fun deriveKeyFromSecret(s: String): SecretKeySpec =
        SecretKeySpec(
            MessageDigest.getInstance(Constants.SHA256_ALGO).digest(s.toByteArray(StandardCharsets.UTF_8)),
            "AES",
        )

    fun encrypt(
        txt: String,
        sk: SecretKey,
    ): String {
        val iv = ByteArray(Constants.GCM_IV_LENGTH).apply { SecureRandom().nextBytes(this) }
        val cp = Cipher.getInstance(Constants.AES_GCM_NOPADDING)
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
        val cp = Cipher.getInstance(Constants.AES_GCM_NOPADDING)
        cp.init(Cipher.DECRYPT_MODE, sk, GCMParameterSpec(Constants.GCM_TAG_LENGTH, iv))
        return String(cp.doFinal(enc), StandardCharsets.UTF_8)
    }
}
