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
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * E2EE v2 Manager - Audit Compliant Version.
 */
object E2EManager {
    private const val TAG = "E2EManager"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private val argon2 by lazy { Argon2Kt() }

    fun encryptWithHardwareKey(plainText: String): Result<String> {
        return try {
            val secretKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance(Constants.AES_GCM_NOPADDING)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
            Result.success(Base64.getEncoder().encodeToString(combined))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun decryptWithHardwareKey(encB64: String): Result<String> {
        return try {
            val combined = Base64.getDecoder().decode(encB64)
            if (combined.size < Constants.GCM_IV_LENGTH) return Result.failure(IllegalArgumentException("Invalid encrypted data"))
            val iv = combined.sliceArray(0 until Constants.GCM_IV_LENGTH)
            val encryptedBytes = combined.sliceArray(Constants.GCM_IV_LENGTH until combined.size)
            val cipher = Cipher.getInstance(Constants.AES_GCM_NOPADDING)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(Constants.GCM_TAG_LENGTH, iv))
            Result.success(String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Checks if the master key is hardware-backed.
     */
    fun isHardwareBacked(): Boolean {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val entry = ks.getEntry(Constants.KEY_MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            val key = entry?.secretKey ?: return false
            val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val keyInfo = factory.getKeySpec(key, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
            keyInfo.isInsideSecureHardware
        } catch (e: Exception) {
            false
        }
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(Constants.KEY_MASTER_KEY_ALIAS)) {
            val kg = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
            kg.init(KeyGenParameterSpec.Builder(Constants.KEY_MASTER_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(Constants.AES_KEY_SIZE).build())
            return kg.generateKey()
        }
        return (ks.getEntry(Constants.KEY_MASTER_KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    fun deleteMasterKey() {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(Constants.KEY_MASTER_KEY_ALIAS)) ks.deleteEntry(Constants.KEY_MASTER_KEY_ALIAS)
        } catch (e: Exception) { }
    }

    fun deriveKeyFromPassword(password: String, salt: ByteArray): SecretKeySpec {
        val result = argon2.hash(Argon2Mode.ARGON2_ID, password.toByteArray(), salt, Constants.ARGON2_ITERATIONS, Constants.ARGON2_MEMORY, Constants.ARGON2_PARALLELISM, Constants.ARGON2_HASH_LENGTH)
        val raw = ByteArray(Constants.ARGON2_HASH_LENGTH)
        result.rawHash.get(raw)
        return SecretKeySpec(raw, "AES")
    }

    fun deriveIdentityKeyPair(entropy: ByteArray): KeyPair {
        val seed = HKDF.deriveKey(entropy, null, "TorChat/identity/ed25519/v1".toByteArray(), 32)
        val kg = KeyPairGenerator.getInstance(Constants.ED25519_ALGO)
        val sr = object : SecureRandom() {
            private var pos = 0
            override fun nextBytes(bytes: ByteArray) {
                for (i in bytes.indices) { bytes[i] = seed[pos % seed.size]; pos++ }
            }
        }
        kg.initialize(256, sr)
        return kg.generateKeyPair()
    }

    fun generateIdentityKeyPair(): KeyPair = KeyPairGenerator.getInstance(Constants.ED25519_ALGO).generateKeyPair()

    fun signData(data: ByteArray, priv: PrivateKey): ByteArray {
        val sig = Signature.getInstance(Constants.ED25519_ALGO)
        sig.initSign(priv)
        sig.update(data)
        return sig.sign()
    }

    fun verifySignature(data: ByteArray, signature: ByteArray, pub: PublicKey): Boolean = try {
        val sig = Signature.getInstance(Constants.ED25519_ALGO)
        sig.initVerify(pub)
        sig.update(data)
        sig.verify(signature)
    } catch (e: Exception) { false }

    fun generateEphemeralKeyPair(): KeyPair = KeyPairGenerator.getInstance(Constants.X25519_ALGO).generateKeyPair()

    fun calculateSharedSecret(priv: PrivateKey, pub: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance(Constants.XDH_ALGO)
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    fun kdfRatchet(key: ByteArray, label: String): Pair<ByteArray, ByteArray> {
        val derived = HKDF.deriveKey(key, null, "TorChat/v2/ratchet/$label".toByteArray(StandardCharsets.UTF_8), 64)
        return derived.sliceArray(0..31) to derived.sliceArray(32..63)
    }

    fun deriveInitialChainKeys(sharedSecret: ByteArray, myOnion: String, peerOnion: String): Pair<ByteArray, ByteArray> {
        val infoSend = "TorChat/v2/chain/$myOnion->$peerOnion".toByteArray()
        val infoReceive = "TorChat/v2/chain/$peerOnion->$myOnion".toByteArray()

        val sendChain = HKDF.deriveKey(sharedSecret, null, infoSend, 32)
        val receiveChain = HKDF.deriveKey(sharedSecret, null, infoReceive, 32)

        return sendChain to receiveChain
    }

    fun buildHandshakeTranscript(
        initiatorOnion: String,
        responderOnion: String,
        initiatorIK: String,
        initiatorEK: String,
        responderIK: String,
        responderEK: String,
        initiatorNonce: ByteArray,
        responderNonce: ByteArray
    ): ByteArray {
        val domain = "v2/hand".toByteArray(StandardCharsets.UTF_8)
        val iO = initiatorOnion.toByteArray(StandardCharsets.UTF_8)
        val rO = responderOnion.toByteArray(StandardCharsets.UTF_8)
        val iIK = initiatorIK.toByteArray(StandardCharsets.UTF_8)
        val iEK = initiatorEK.toByteArray(StandardCharsets.UTF_8)
        val rIK = responderIK.toByteArray(StandardCharsets.UTF_8)
        val rEK = responderEK.toByteArray(StandardCharsets.UTF_8)

        val totalSize = domain.size + 4 + iO.size + 4 + rO.size + 4 + iIK.size + 4 + iEK.size + 4 + rIK.size + 4 + rEK.size + 4 + initiatorNonce.size + 4 + responderNonce.size
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.put(domain)
        buffer.putInt(iO.size); buffer.put(iO)
        buffer.putInt(rO.size); buffer.put(rO)
        buffer.putInt(iIK.size); buffer.put(iIK)
        buffer.putInt(iEK.size); buffer.put(iEK)
        buffer.putInt(rIK.size); buffer.put(rIK)
        buffer.putInt(rEK.size); buffer.put(rEK)
        buffer.putInt(initiatorNonce.size); buffer.put(initiatorNonce)
        buffer.putInt(responderNonce.size); buffer.put(responderNonce)

        return buffer.array()
    }

    /**
     * Transcript for the first message (PFS_INIT) authentication.
     * Resolves Audit Point 7 (PFS_INIT authentication).
     */
    fun buildInitTranscript(
        initiatorOnion: String,
        responderOnion: String,
        initiatorIK: String,
        initiatorEK: String,
        initiatorNonce: ByteArray
    ): ByteArray {
        val domain = "v2/init".toByteArray(StandardCharsets.UTF_8)
        val iO = initiatorOnion.toByteArray(StandardCharsets.UTF_8)
        val rO = responderOnion.toByteArray(StandardCharsets.UTF_8)
        val iIK = initiatorIK.toByteArray(StandardCharsets.UTF_8)
        val iEK = initiatorEK.toByteArray(StandardCharsets.UTF_8)

        val totalSize = domain.size + 4 + iO.size + 4 + rO.size + 4 + iIK.size + 4 + iEK.size + 4 + initiatorNonce.size
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.put(domain)
        buffer.putInt(iO.size); buffer.put(iO)
        buffer.putInt(rO.size); buffer.put(rO)
        buffer.putInt(iIK.size); buffer.put(iIK)
        buffer.putInt(iEK.size); buffer.put(iEK)
        buffer.putInt(initiatorNonce.size); buffer.put(initiatorNonce)

        return buffer.array()
    }

    fun calculateSessionId(transcript: ByteArray): String {
        val hash = MessageDigest.getInstance(Constants.SHA256_ALGO).digest(transcript)
        return Base64.getEncoder().encodeToString(hash)
    }

    fun buildAAD(version: Byte, type: Byte, seq: Int, sender: String, sessionId: String): ByteArray {
        val onionBytes = sender.toByteArray(StandardCharsets.UTF_8)
        val sidBytes = sessionId.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(1 + 1 + 4 + 4 + onionBytes.size + 4 + sidBytes.size)
        buffer.put(version)
        buffer.put(type)
        buffer.putInt(seq)
        buffer.putInt(onionBytes.size); buffer.put(onionBytes)
        buffer.putInt(sidBytes.size); buffer.put(sidBytes)
        return buffer.array()
    }

    fun encryptV2(plaintext: String, key: ByteArray, aad: ByteArray): String {
        val cipher = Cipher.getInstance(Constants.AES_GCM_NOPADDING)
        val iv = ByteArray(Constants.GCM_IV_LENGTH).apply { SecureRandom().nextBytes(this) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(Constants.GCM_TAG_LENGTH, iv))
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    fun decryptV2(encB64: String, key: ByteArray, aad: ByteArray): String {
        val combined = Base64.getDecoder().decode(encB64)
        val iv = combined.sliceArray(0 until Constants.GCM_IV_LENGTH)
        val encryptedBytes = combined.sliceArray(Constants.GCM_IV_LENGTH until combined.size)
        val cipher = Cipher.getInstance(Constants.AES_GCM_NOPADDING)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(Constants.GCM_TAG_LENGTH, iv))
        cipher.updateAAD(aad)
        return String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8)
    }

    fun hashPassword(p: String): String {
        val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        return argon2.hash(Argon2Mode.ARGON2_ID, p.toByteArray(), salt, Constants.ARGON2_ITERATIONS, Constants.ARGON2_MEMORY, Constants.ARGON2_PARALLELISM, Constants.ARGON2_HASH_LENGTH).encodedOutputAsString()
    }

    fun verifyPassword(p: String, h: String): Boolean = try { argon2.verify(Argon2Mode.ARGON2_ID, h, p.toByteArray()) } catch (e: Exception) { false }

    fun publicKeyToString(pk: PublicKey): String = Base64.getEncoder().encodeToString(pk.encoded)

    fun stringToPublicKey(b64: String, algo: String): PublicKey {
        val kb = Base64.getDecoder().decode(b64)
        return KeyFactory.getInstance(algo).generatePublic(X509EncodedKeySpec(kb))
    }

    fun deriveKeyFromSecret(s: String, salt: ByteArray): SecretKeySpec {
        val derived = HKDF.deriveKey(s.toByteArray(StandardCharsets.UTF_8), salt, "TorChat/v2/storage/peer".toByteArray(), 32)
        return SecretKeySpec(derived, "AES")
    }

    fun encrypt(txt: String, sk: SecretKey): String {
        val iv = ByteArray(Constants.GCM_IV_LENGTH).apply { SecureRandom().nextBytes(this) }
        val cp = Cipher.getInstance(Constants.AES_GCM_NOPADDING)
        cp.init(Cipher.ENCRYPT_MODE, sk, GCMParameterSpec(Constants.GCM_TAG_LENGTH, iv))
        val enc = cp.doFinal(txt.toByteArray(StandardCharsets.UTF_8))
        val res = ByteArray(iv.size + enc.size)
        System.arraycopy(iv, 0, res, 0, iv.size); System.arraycopy(enc, 0, res, iv.size, enc.size)
        return Base64.getEncoder().encodeToString(res)
    }

    fun decrypt(encB64: String, sk: SecretKey): String {
        val comb = Base64.getDecoder().decode(encB64); val iv = comb.sliceArray(0 until Constants.GCM_IV_LENGTH); val enc = comb.sliceArray(Constants.GCM_IV_LENGTH until comb.size)
        val cp = Cipher.getInstance(Constants.AES_GCM_NOPADDING); cp.init(Cipher.DECRYPT_MODE, sk, GCMParameterSpec(Constants.GCM_TAG_LENGTH, iv))
        return String(cp.doFinal(enc), StandardCharsets.UTF_8)
    }

    fun getFingerprint(pk: PublicKey): String = MessageDigest.getInstance(Constants.SHA256_ALGO).digest(pk.encoded).joinToString("") { "%02X".format(it) }.chunked(4).take(6).joinToString("-")
}
