package com.p2p.torchat.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
 * Updated in v7 for RFC 8032 compliance and best-effort secret wiping.
 */
object E2EManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private val argon2 by lazy { Argon2Kt() }

    fun encryptWithHardwareKey(plainText: ByteArray): Result<ByteArray> {
        return try {
            val secretKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance(Constants.AES_GCM_NOPADDING)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText)
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
            Result.success(combined)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun decryptWithHardwareKey(combined: ByteArray): Result<ByteArray> {
        return try {
            if (combined.size < Constants.GCM_IV_LENGTH) return Result.failure(IllegalArgumentException("Invalid encrypted data"))
            val iv = combined.sliceArray(0 until Constants.GCM_IV_LENGTH)
            val encryptedBytes = combined.sliceArray(Constants.GCM_IV_LENGTH until combined.size)
            val cipher = Cipher.getInstance(Constants.AES_GCM_NOPADDING)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(Constants.GCM_TAG_LENGTH, iv))
            Result.success(cipher.doFinal(encryptedBytes))
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
            kg.init(
                KeyGenParameterSpec.Builder(Constants.KEY_MASTER_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(Constants.AES_KEY_SIZE)
                    .setUserAuthenticationRequired(true)
                    .build()
            )
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

    fun deriveKeyFromPassword(password: CharArray, salt: ByteArray): SecretKeySpec {
        val passBytes = password.toUtf8ByteArray()
        try {
            val result = argon2.hash(Argon2Mode.ARGON2_ID, passBytes, salt, Constants.ARGON2_ITERATIONS, Constants.ARGON2_MEMORY, Constants.ARGON2_PARALLELISM, Constants.ARGON2_HASH_LENGTH)
            val raw = ByteArray(Constants.ARGON2_HASH_LENGTH)
            result.rawHash[raw]
            return SecretKeySpec(raw, "AES")
        } finally {
            passBytes.fill(0)
        }
    }

    /**
     * Generates a 32-byte random seed for identity derivation.
     */
    fun generateIdentitySeed(): ByteArray = ByteArray(32).apply { SecureRandom().nextBytes(this) }

    /**
     * Derives an Ed25519 KeyPair deterministically from a 32-byte seed.
     * RFC 8032 compliant derivation using Bouncy Castle.
     * Resolves Audit Point 7 (Provider-independent Ed25519).
     */
    fun ed25519KeyPairFromSeed(seed: ByteArray): KeyPair {
        require(seed.size == 32) { "Seed must be 32 bytes" }

        // Use Bouncy Castle for standard-compliant scalar multiplication
        val bcPriv = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
        val bcPub = bcPriv.generatePublicKey()

        val pubBytes = bcPub.getEncoded()
        val privBytes = bcPriv.getEncoded()

        val kf = KeyFactory.getInstance(Constants.ED25519_ALGO)

        // Construct PKCS#8 for JCA PrivateKey
        val pkcs8Prefix = byteArrayOf(
            0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20
        )
        val pkcs8Bytes = ByteArray(pkcs8Prefix.size + seed.size)
        System.arraycopy(pkcs8Prefix, 0, pkcs8Bytes, 0, pkcs8Prefix.size)
        System.arraycopy(seed, 0, pkcs8Bytes, pkcs8Prefix.size, seed.size)
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8Bytes))

        // Construct X.509 for JCA PublicKey
        // Prefix: 30 2a 30 05 06 03 2b 65 70 03 21 00
        val x509Prefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
        )
        val x509Bytes = ByteArray(x509Prefix.size + pubBytes.size)
        System.arraycopy(x509Prefix, 0, x509Bytes, 0, x509Prefix.size)
        System.arraycopy(pubBytes, 0, x509Bytes, x509Prefix.size, pubBytes.size)
        val pub = kf.generatePublic(X509EncodedKeySpec(x509Bytes))

        return KeyPair(pub, priv)
    }

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

    fun kdfRoot(rootKey: ByteArray, dhOutput: ByteArray): Pair<ByteArray, ByteArray> {
        val derived = HKDF.deriveKey(dhOutput, rootKey, "TorChat/v2/dr/root".toByteArray(StandardCharsets.UTF_8), 64)
        return derived.sliceArray(0..31) to derived.sliceArray(32..63)
    }

    fun kdfChain(chainKey: ByteArray, label: String): Pair<ByteArray, ByteArray> {
        val derived = HKDF.deriveKey(chainKey, null, "TorChat/v2/dr/chain/$label".toByteArray(StandardCharsets.UTF_8), 64)
        return derived.sliceArray(0..31) to derived.sliceArray(32..63)
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

    fun buildAAD(
        version: Byte,
        type: Byte,
        seq: Int,
        sender: String,
        sessionId: String,
        ratchetPublicKey: String,
        pn: Int,
        n: Int
    ): ByteArray {
        val onionBytes = sender.toByteArray(StandardCharsets.UTF_8)
        val sidBytes = sessionId.toByteArray(StandardCharsets.UTF_8)
        val rpkBytes = ratchetPublicKey.toByteArray(StandardCharsets.UTF_8)

        val buffer = ByteBuffer.allocate(1 + 1 + 4 + 4 + onionBytes.size + 4 + sidBytes.size + 4 + rpkBytes.size + 4 + 4)
        buffer.put(version)
        buffer.put(type)
        buffer.putInt(seq)
        buffer.putInt(onionBytes.size); buffer.put(onionBytes)
        buffer.putInt(sidBytes.size); buffer.put(sidBytes)
        buffer.putInt(rpkBytes.size); buffer.put(rpkBytes)
        buffer.putInt(pn)
        buffer.putInt(n)
        return buffer.array()
    }

    private val PADDING_BUCKETS = listOf(4096, 8192, 16384, 32768, 65536, 131072, 262144, 524288, 1048576)

    private fun addPadding(data: ByteArray): ByteArray {
        val targetSize = PADDING_BUCKETS.find { it >= data.size + 4 } ?: (data.size + 4)
        val padded = ByteArray(targetSize)
        val buffer = ByteBuffer.wrap(padded)
        buffer.putInt(data.size)
        buffer.put(data)
        if (targetSize > data.size + 4) {
            val padding = ByteArray(targetSize - data.size - 4)
            SecureRandom().nextBytes(padding)
            buffer.put(padding)
        }
        return padded
    }

    private fun removePadding(padded: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(padded)
        val len = buffer.getInt()
        if (len < 0 || len > padded.size - 4) throw SecurityException("Invalid padding length")
        val data = ByteArray(len)
        buffer.get(data)
        return data
    }

    fun encryptV2(plaintext: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        val padded = addPadding(plaintext)
        val cipher = Cipher.getInstance(Constants.AES_GCM_NOPADDING)
        val iv = ByteArray(Constants.GCM_IV_LENGTH).apply { SecureRandom().nextBytes(this) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(Constants.GCM_TAG_LENGTH, iv))
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(padded)
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return combined
    }

    fun decryptV2(combined: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        val iv = combined.sliceArray(0 until Constants.GCM_IV_LENGTH)
        val encryptedBytes = combined.sliceArray(Constants.GCM_IV_LENGTH until combined.size)
        val cipher = Cipher.getInstance(Constants.AES_GCM_NOPADDING)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(Constants.GCM_TAG_LENGTH, iv))
        cipher.updateAAD(aad)
        val padded = cipher.doFinal(encryptedBytes)
        return removePadding(padded)
    }

    fun hashPassword(p: CharArray): String {
        val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val passBytes = p.toUtf8ByteArray()
        try {
            return argon2.hash(Argon2Mode.ARGON2_ID, passBytes, salt, Constants.ARGON2_ITERATIONS, Constants.ARGON2_MEMORY, Constants.ARGON2_PARALLELISM, Constants.ARGON2_HASH_LENGTH).encodedOutputAsString()
        } finally {
            passBytes.fill(0)
        }
    }

    fun verifyPassword(p: CharArray, h: String): Boolean = try {
        val passBytes = p.toUtf8ByteArray()
        try {
            argon2.verify(Argon2Mode.ARGON2_ID, h, passBytes)
        } finally {
            passBytes.fill(0)
        }
    } catch (e: Exception) { false }

    fun publicKeyToString(pk: PublicKey): String = Base64.getEncoder().encodeToString(pk.encoded)

    fun stringToPublicKey(b64: String, algo: String): PublicKey {
        val kb = Base64.getDecoder().decode(b64)
        return KeyFactory.getInstance(algo).generatePublic(X509EncodedKeySpec(kb))
    }

    fun deriveKeyFromSecret(s: String, salt: ByteArray): SecretKeySpec {
        val derived = HKDF.deriveKey(s.toByteArray(StandardCharsets.UTF_8), salt, "TorChat/v2/storage/peer".toByteArray(), 32)
        return SecretKeySpec(derived, "AES")
    }

    /**
     * Derives the master vault key using Argon2id.
     * Resolves Audit Point 6 (Memory-hard vault protection).
     */
    fun deriveVaultKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val passBytes = password.toUtf8ByteArray()
        try {
            // Using same parameters as password hashing for consistency and cost
            val result = argon2.hash(
                Argon2Mode.ARGON2_ID,
                passBytes,
                salt,
                Constants.ARGON2_ITERATIONS,
                Constants.ARGON2_MEMORY,
                Constants.ARGON2_PARALLELISM,
                Constants.ARGON2_HASH_LENGTH
            )
            val raw = ByteArray(Constants.ARGON2_HASH_LENGTH)
            result.rawHash[raw]

            // Domain separation via HKDF after Argon2
            val finalKey = HKDF.deriveKey(raw, null, "TorChat/v2/vault/master".toByteArray(), 32)
            raw.fill(0)

            return SecretKeySpec(finalKey, "AES")
        } finally {
            passBytes.fill(0)
        }
    }

    private fun CharArray.toUtf8ByteArray(): ByteArray {
        val charBuffer = java.nio.CharBuffer.wrap(this)
        val byteBuffer = StandardCharsets.UTF_8.encode(charBuffer)
        val bytes = ByteArray(byteBuffer.remaining())
        byteBuffer.get(bytes)
        return bytes
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

    fun getFingerprint(pk: PublicKey): String = MessageDigest.getInstance(Constants.SHA256_ALGO).digest(pk.encoded)
        .joinToString("") { "%02X".format(it) }
        .chunked(4)
        .asSequence()
        .take(6)
        .joinToString("-")
}
