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

class E2EManager {
    companion object {
        private const val AES_GCM_TAG_LENGTH = 128
        private const val IV_LENGTH = 12
        private const val MASTER_KEY_ALIAS = "HardwareMasterKey"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        // Argon2id Parameters
        private const val ARGON2_ITERATIONS = 2
        private const val ARGON2_MEMORY = 65536 // 64 MB
        private const val ARGON2_PARALLELISM = 1
        private const val ARGON2_HASH_LENGTH = 32

        private val argon2 = Argon2Kt()

        /**
         * Encrypts sensitive data using a hardware-backed Master Key.
         * Resolves Audit Point 4.
         */
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

        /**
         * Decrypts data using the hardware-backed Master Key.
         */
        fun decryptWithHardwareKey(encryptedBase64: String): String {
            val combined = Base64.getDecoder().decode(encryptedBase64)
            val iv = combined.sliceArray(0 until 12)
            val encryptedBytes = combined.sliceArray(12 until combined.size)

            val secretKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(AES_GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            return String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8)
        }

        private fun getOrCreateMasterKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec =
                    KeyGenParameterSpec.Builder(
                        MASTER_KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                keyGenerator.init(spec)
                return keyGenerator.generateKey()
            }
            val entry = keyStore.getEntry(MASTER_KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }

        /**
         * Wipes the hardware master key.
         */
        fun deleteMasterKey() {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.deleteEntry(MASTER_KEY_ALIAS)
        }

        /**
         * Generates a human-readable fingerprint from a public key
         */
        fun getFingerprint(publicKey: PublicKey): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(publicKey.encoded)
            // Use full SHA-256 fingerprint for security (Audit Point 9)
            return hash.joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")
        }

        fun deriveKeyArgon2id(
            secret: String,
            salt: ByteArray,
        ): SecretKeySpec {
            val result =
                argon2.hash(
                    mode = Argon2Mode.ARGON2_ID,
                    password = secret.toByteArray(StandardCharsets.UTF_8),
                    salt = salt,
                    tCostInIterations = ARGON2_ITERATIONS,
                    mCostInKibibyte = ARGON2_MEMORY,
                    parallelism = ARGON2_PARALLELISM,
                    hashLengthInBytes = ARGON2_HASH_LENGTH,
                )
            return SecretKeySpec(result.rawHashAsByteArray(), "AES")
        }

        fun generateSecretKey(): SecretKey {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            return keyGen.generateKey()
        }

        fun generateECDHKeyPair(): KeyPair {
            val keyPairGen = KeyPairGenerator.getInstance("EC")
            keyPairGen.initialize(256)
            return keyPairGen.generateKeyPair()
        }

        fun getSharedSecret(
            privateKey: PrivateKey,
            publicKey: PublicKey,
        ): SecretKeySpec {
            val keyAgreement = KeyAgreement.getInstance("ECDH")
            keyAgreement.init(privateKey)
            keyAgreement.doPhase(publicKey, true)
            val sharedSecret = keyAgreement.generateSecret()
            val digest = MessageDigest.getInstance("SHA-256")
            return SecretKeySpec(digest.digest(sharedSecret), "AES")
        }

        fun stringToPublicKey(base64Key: String): PublicKey {
            val keyBytes = Base64.getDecoder().decode(base64Key)
            return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
        }

        fun publicKeyToString(publicKey: PublicKey): String = Base64.getEncoder().encodeToString(publicKey.encoded)

        fun deriveKeyFromSecret(sharedSecret: String): SecretKeySpec {
            val digest = MessageDigest.getInstance("SHA-256")
            return SecretKeySpec(digest.digest(sharedSecret.toByteArray(StandardCharsets.UTF_8)), "AES")
        }

        fun hashPassword(password: String): String {
            val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
            val result =
                argon2.hash(
                    mode = Argon2Mode.ARGON2_ID,
                    password = password.toByteArray(StandardCharsets.UTF_8),
                    salt = salt,
                    tCostInIterations = ARGON2_ITERATIONS,
                    mCostInKibibyte = ARGON2_MEMORY,
                    parallelism = ARGON2_PARALLELISM,
                    hashLengthInBytes = ARGON2_HASH_LENGTH,
                )
            return result.encodedOutputAsString()
        }

        fun verifyPassword(
            password: String,
            encodedHash: String,
        ): Boolean {
            if (!encodedHash.startsWith("$")) return false
            return try {
                argon2.verify(
                    mode = Argon2Mode.ARGON2_ID,
                    encoded = encodedHash,
                    password = password.toByteArray(StandardCharsets.UTF_8),
                )
            } catch (e: Exception) {
                false
            }
        }

        fun encrypt(
            plainText: String,
            secretKey: SecretKey,
        ): String {
            val iv = ByteArray(IV_LENGTH).apply { SecureRandom().nextBytes(this) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(AES_GCM_TAG_LENGTH, iv))
            val cipherText = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
            return Base64.getEncoder().encodeToString(combined)
        }

        fun decrypt(
            encryptedBase64: String,
            secretKey: SecretKey,
        ): String {
            val combined = Base64.getDecoder().decode(encryptedBase64)
            val iv = combined.sliceArray(0 until IV_LENGTH)
            val cipherText = combined.sliceArray(IV_LENGTH until combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(AES_GCM_TAG_LENGTH, iv))
            return String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
        }
    }
}
