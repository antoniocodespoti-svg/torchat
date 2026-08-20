package com.p2p.tormaster.util

object Constants {
    const val AES_KEY_SIZE = 256
    const val AES_KEY_BYTES = 32
    const val GCM_IV_LENGTH = 12
    const val GCM_TAG_LENGTH = 128
    const val ARGON2_ITERATIONS = 2
    const val ARGON2_MEMORY = 65536
    const val ARGON2_PARALLELISM = 1
    const val ARGON2_HASH_LENGTH = 32
    const val BIP39_ENTROPY_BITS = 128
    const val BIP39_CHECKSUM_BITS = 4
    const val BIP39_WORD_COUNT = 12
    const val SHA256_ALGO = "SHA-256"
    const val ED25519_ALGO = "Ed25519"
}
