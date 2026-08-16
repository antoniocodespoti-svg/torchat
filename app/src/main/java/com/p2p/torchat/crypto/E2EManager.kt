package com.p2p.torchat.crypto

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

        // Argon2id Parameters
        private const val ARGON2_ITERATIONS = 2
        private const val ARGON2_MEMORY = 65536 // 64 MB
        private const val ARGON2_PARALLELISM = 1
        private const val ARGON2_HASH_LENGTH = 32

        private val argon2 = Argon2Kt()

        /**
         * Generates a human-readable fingerprint from a public key
         */
        fun getFingerprint(publicKey: PublicKey): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(publicKey.encoded)
            return hash.take(8).joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")
        }

        /**
         * Derives a 256-bit key from a secret string using Argon2id
         */
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
            val hashBytes = result.rawHashAsByteArray()
            return SecretKeySpec(hashBytes, "AES")
        }

        /**
         * Generates a random 256-bit AES SecretKey
         */
        fun generateSecretKey(): SecretKey {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            return keyGen.generateKey()
        }

        /**
         * Generates an ECDH KeyPair (secp256r1 / P-256)
         */
        fun generateECDHKeyPair(): KeyPair {
            val keyPairGen = KeyPairGenerator.getInstance("EC")
            keyPairGen.initialize(256)
            return keyPairGen.generateKeyPair()
        }

        /**
         * Derives a shared AES key using ECDH
         */
        fun getSharedSecret(
            privateKey: PrivateKey,
            publicKey: PublicKey,
        ): SecretKeySpec {
            val keyAgreement = KeyAgreement.getInstance("ECDH")
            keyAgreement.init(privateKey)
            keyAgreement.doPhase(publicKey, true)
            val sharedSecret = keyAgreement.generateSecret()

            // Hash the shared secret to get a fixed 256-bit key
            val digest = MessageDigest.getInstance("SHA-256")
            val keyBytes = digest.digest(sharedSecret)
            return SecretKeySpec(keyBytes, "AES")
        }

        /**
         * Converts a Base64 string to a PublicKey
         */
        fun stringToPublicKey(base64Key: String): PublicKey {
            val keyBytes = Base64.getDecoder().decode(base64Key)
            val keyFactory = KeyFactory.getInstance("EC")
            return keyFactory.generatePublic(X509EncodedKeySpec(keyBytes))
        }

        /**
         * Converts a PublicKey to a Base64 string
         */
        fun publicKeyToString(publicKey: PublicKey): String {
            return Base64.getEncoder().encodeToString(publicKey.encoded)
        }

        /**
         * Derives a deterministic 256-bit secret key from a shared secret string
         */
        fun deriveKeyFromSecret(sharedSecret: String): SecretKeySpec {
            val digest = MessageDigest.getInstance("SHA-256")
            val keyBytes = digest.digest(sharedSecret.toByteArray(StandardCharsets.UTF_8))
            return SecretKeySpec(keyBytes, "AES")
        }

        fun hashPassword(password: String): String {
            val salt = ByteArray(16)
            SecureRandom().nextBytes(salt)

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

        /**
         * Verifies a password against an Argon2id encoded hash
         */
        fun verifyPassword(
            password: String,
            encodedHash: String,
        ): Boolean {
            // Handle old SHA-256 hashes for migration
            if (!encodedHash.startsWith("\$argon2id")) {
                val oldHash = hashPasswordOld(password)
                return oldHash == encodedHash
            }

            return argon2.verify(
                mode = Argon2Mode.ARGON2_ID,
                encoded = encodedHash,
                password = password.toByteArray(StandardCharsets.UTF_8),
            )
        }

        private fun hashPasswordOld(password: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(password.toByteArray(StandardCharsets.UTF_8))
            return Base64.getEncoder().encodeToString(hashBytes)
        }

        /**
         * Encrypts plaintext using AES-256-GCM
         */
        fun encrypt(
            plainText: String,
            secretKey: SecretKey,
        ): String {
            val iv = ByteArray(IV_LENGTH)
            SecureRandom().nextBytes(iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val parameterSpec = GCMParameterSpec(AES_GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)

            val cipherTextBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))

            // Combine IV + CipherText
            val combined = ByteArray(iv.size + cipherTextBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherTextBytes, 0, combined, iv.size, cipherTextBytes.size)

            return Base64.getEncoder().encodeToString(combined)
        }

        /**
         * Decrypts AES-256-GCM base64 encoded payload
         */
        fun decrypt(
            encryptedBase64: String,
            secretKey: SecretKey,
        ): String {
            val combined = Base64.getDecoder().decode(encryptedBase64)
            if (combined.size < IV_LENGTH) {
                throw IllegalArgumentException("Invalid encrypted payload size")
            }

            val iv = ByteArray(IV_LENGTH)
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH)

            val cipherTextSize = combined.size - IV_LENGTH
            val cipherTextBytes = ByteArray(cipherTextSize)
            System.arraycopy(combined, IV_LENGTH, cipherTextBytes, 0, cipherTextSize)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val parameterSpec = GCMParameterSpec(AES_GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)

            val decryptedBytes = cipher.doFinal(cipherTextBytes)
            return String(decryptedBytes, StandardCharsets.UTF_8)
        }
    }
}
