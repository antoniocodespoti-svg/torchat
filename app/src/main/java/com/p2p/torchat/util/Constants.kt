package com.p2p.torchat.util

object Constants {
    const val MAX_AUTH_ATTEMPTS = 3
    const val TOR_SOCKS_PORT = 9050
    const val LOCAL_SERVER_PORT = 8080
    const val TAG = "TorChat"
    const val PREFS_NAME = "tor_chat_prefs"

    const val KEY_ONION = "saved_onion_address"
    const val KEY_PASS_HASH = "app_password_hash"
    const val KEY_FAILED_ATTEMPTS = "failed_attempts"
    const val KEY_EXPIRY = "account_expiry_date"
    const val KEY_TERMS_ACCEPTED = "is_terms_accepted"
    const val KEY_MY_ALIAS = "my_alias"
    const val KEY_DARK_THEME = "is_dark_theme"
    const val KEY_AUTO_BACKUP = "is_auto_backup_enabled"
    const val KEY_PUBLIC_KEY = "my_public_key"
    const val KEY_IDENTITY_SEED_ENC = "my_identity_seed_enc"
    const val KEY_SAVED_PEERS = "saved_peers"
    const val KEY_SAVED_SEED = "saved_seed" // Plaintext - TO BE REMOVED
    const val KEY_SAVED_SEED_ENC = "saved_seed_enc" // Encrypted with password
    const val KEY_AVAILABILITY = "is_available"
    const val KEY_MASTER_KEY_ALIAS = "torchat_master_key"

    const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
    const val GCM_IV_LENGTH = 12
    const val GCM_TAG_LENGTH = 128
    const val AES_KEY_SIZE = 256
    const val AES_KEY_BYTES = 32

    const val ARGON2_ITERATIONS = 2
    const val ARGON2_MEMORY = 65536
    const val ARGON2_PARALLELISM = 1
    const val ARGON2_HASH_LENGTH = 32

    const val ED25519_ALGO = "Ed25519"
    const val X25519_ALGO = "X25519"
    const val XDH_ALGO = "XDH"
    const val SHA256_ALGO = "SHA-256"

    const val BIP39_WORD_COUNT = 12
    const val BIP39_ENTROPY_BITS = 128
    const val BIP39_CHECKSUM_BITS = 4

    const val ONION_V3_REGEX = "^[a-z2-7]{56}\\.onion$"
}
