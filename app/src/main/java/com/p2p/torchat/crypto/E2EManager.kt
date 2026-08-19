package com.p2p.torchat.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
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
    private const val AES_GCM_TAG_LENGTH = 128
    private const val MASTER_KEY_ALIAS = "HardwareMasterKeyV3"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private val argon2 = Argon2Kt()

    // --- HARDWARE KEYSTORE ---

    fun encryptWithHardwareKey(plainText: String): String {
        val secretKey = getOrCreateMasterKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(iv.size + encryptedBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    fun decryptWithHardwareKey(encB64: String): String {
        val combined = Base64.getDecoder().decode(encB64)
        val iv = combined.sliceArray(0 until 12)
        val encryptedBytes = combined.sliceArray(12 until combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(AES_GCM_TAG_LENGTH, iv))
        return String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8)
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(MASTER_KEY_ALIAS)) {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            kg.init(KeyGenParameterSpec.Builder(MASTER_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            return kg.generateKey()
        }
        return (ks.getEntry(MASTER_KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    fun deleteMasterKey() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(MASTER_KEY_ALIAS)) ks.deleteEntry(MASTER_KEY_ALIAS)
    }

    // --- PASSWORD KDF (Argon2id) ---

    fun deriveKeyFromPassword(password: String, salt: ByteArray): SecretKeySpec {
        val result = argon2.hash(Argon2Mode.ARGON2_ID, password.toByteArray(), salt, 2, 65536, 1, 32)
        val raw = ByteArray(32)
        result.rawHash.get(raw)
        return SecretKeySpec(raw, "AES")
    }

    // --- IDENTITY & SIGNATURES (Ed25519) ---

    fun deriveIdentityKeyPair(entropy: ByteArray): KeyPair {
        val seed = MessageDigest.getInstance("SHA-256").digest(entropy)
        val kg = KeyPairGenerator.getInstance("Ed25519")
        val sr = SecureRandom.getInstance("SHA1PRNG")
        sr.setSeed(seed)
        kg.initialize(256, sr)
        return kg.generateKeyPair()
    }

    fun generateIdentityKeyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun signHandshake(data: String, priv: PrivateKey): String {
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(priv)
        sig.update(data.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    fun verifyHandshake(data: String, sigStr: String, pub: PublicKey): Boolean = try {
        val sig = Signature.getInstance("Ed25519")
        sig.initVerify(pub)
        sig.update(data.toByteArray(StandardCharsets.UTF_8))
        sig.verify(Base64.getDecoder().decode(sigStr))
    } catch (e: Exception) { false }

    fun getFingerprint(pk: PublicKey): String = MessageDigest.getInstance("SHA-256").digest(pk.encoded)
        .joinToString("") { "%02X".format(it) }.chunked(4).take(6).joinToString("-")

    // --- KEY EXCHANGE (X25519) ---

    fun generateEphemeralKeyPair(): KeyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair()

    fun calculateSharedSecret(priv: PrivateKey, pub: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("XDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    // --- RATCHET KDF & ENCRYPTION ---

    fun kdfRatchet(key: ByteArray, info: String): Pair<ByteArray, ByteArray> {
        val derived = HKDF.deriveKey(key, null, info.toByteArray(StandardCharsets.UTF_8), 64)
        return derived.sliceArray(0..31) to derived.sliceArray(32..63)
    }

    fun encryptV2(plaintext: String, key: ByteArray, associatedData: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    fun decryptV2(encB64: String, key: ByteArray, associatedData: String): String {
        val combined = Base64.getDecoder().decode(encB64)
        val iv = combined.sliceArray(0 until 12)
        val encrypted = combined.sliceArray(12 until combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    // --- UTILS ---

    fun hashPassword(p: String): String {
        val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        return argon2.hash(Argon2Mode.ARGON2_ID, p.toByteArray(), salt, 2, 65536, 1, 32).encodedOutputAsString()
    }

    fun verifyPassword(p: String, h: String): Boolean = try {
        argon2.verify(Argon2Mode.ARGON2_ID, h, p.toByteArray())
    } catch (e: Exception) { false }

    fun publicKeyToString(pk: PublicKey): String = Base64.getEncoder().encodeToString(pk.encoded)

    fun stringToPublicKey(b64: String, algo: String): PublicKey {
        val kb = Base64.getDecoder().decode(b64)
        return KeyFactory.getInstance(algo).generatePublic(X509EncodedKeySpec(kb))
    }

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
