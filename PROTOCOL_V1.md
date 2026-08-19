# TorP2PChat Protocol Specification (V1)

## 1. Identity & Addressing
- **Root Entropy**: 128-bit random entropy generated via `SecureRandom`.
- **Mnemonic**: BIP-39 standard (12 words).
- **Identity Key**: Ed25519 KeyPair derived from the root entropy using HKDF-SHA256 (`info: "TorChat/identity/ed25519/v1"`).
- **Addressing**: The Ed25519 Public Key fingerprint is used to request a Tor Hidden Service v3 (.onion address).

## 2. Session Handshake (PFS)
The protocol uses an X25519-based handshake to establish Perfect Forward Secrecy (PFS).

1.  **Handshake Packet**:
    - `Ephemeral_Public_Key` (X25519)
    - `Signature` (Ed25519, signing the ephemeral key)
    - `Identity_Public_Key` (Ed25519)
2.  **Shared Secret**: Derived via XDH (Diffie-Hellman over Curve25519).
3.  **Root Key**: Derived from the Shared Secret using HKDF-SHA256.

## 3. Symmetric Ratchet
Every message uses a unique key derived from a KDF chain.
- **Chain Key**: Updated using HKDF after every message.
- **Message Key**: 32-bit AES key derived from the current Chain Key.
- **Sequence Number**: Incremented for every message to prevent Replay Attacks.

## 4. Message Envelope (Binary Framing)
To prevent DoS and malformed input attacks, every TCP transmission follows this format:
- `MAGIC_BYTE` (1 byte): `0x54` ('T')
- `VERSION` (1 byte): `0x01`
- `PAYLOAD_TYPE` (1 byte)
- `SEQUENCE_NUMBER` (4 bytes, Big Endian)
- `PAYLOAD_LENGTH` (4 bytes, Big Endian)
- `PAYLOAD_DATA` (Encrypted JSON)

## 5. Encryption (AES-256-GCM)
- **Algorithm**: AES-256 in GCM mode.
- **Nonce**: 12-bit random (unique per message).
- **AAD (Additional Authenticated Data)**: `version | type | sequence_number | sender_onion`.

## 6. Traffic Obfuscation
- **Padding**: Payloads are padded to fixed "buckets" (4KB, 64KB, 256KB, 1MB, 5MB).
- **Jitter**: Random delay (50ms - 500ms) before sending.
- **Noise**: Scheduled dummy messages sent when the user is active.
