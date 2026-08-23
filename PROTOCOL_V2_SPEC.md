# TorChat E2EE Protocol Specification (V2)

This document defines the normative standard for the End-to-End Encryption (E2EE) and Binary Framing used in TorP2PChat.

## 1. Binary Framing (NET-001)

All network communication follows a strict binary format to ensure efficiency and protocol obfuscation.

| Offset | Field | Type | Description |
| :--- | :--- | :--- | :--- |
| 0 | Magic Byte | Byte | Always `0x54` ('T') |
| 1 | Version | Byte | `0x01` for V2 Protocol |
| 2 | Type | Byte | Payload Type (Handshake, Message, Ping, etc.) |
| 3 | Sequence | Int | Packet sequence number |
| 7 | Onion Len | Int | Length of sender's .onion address |
| 11 | Sender Onion | String | Sender's Onion v3 address |
| - | RPK Len | Int | Length of Ratchet Public Key |
| - | Ratchet PK | String | Current X25519 Ratchet Public Key (if applicable) |
| - | PN | Int | Previous Counter (Double Ratchet) |
| - | N | Int | Message Counter (Double Ratchet) |
| - | Data Len | Int | Length of encrypted payload |
| - | Payload | Bytes | AES-256-GCM encrypted data |

## 2. Identity and Key Derivation

- **Identity Key (IK)**: Ed25519 (RFC 8032). Used for long-term authentication and signing.
- **Identity Derivation**: Derived deterministically from a 32-byte seed.
- **KDF**: HKDF-SHA256 (RFC 5869) used for all key derivation steps.

## 3. Handshake (3-Way Authenticated Exchange)

The handshake establishes the initial `SharedSecret` and `SessionID`.

1. **PFS_INIT**:
   - Alice sends: `eA_pub` (X25519), `ikA_pub`, `nA` (nonce).
   - Signature: `Ed25519_Sign(ikA_priv, Transcript_Init)`.
2. **PFS_ACCEPT**:
   - Bob sends: `eB_pub` (X25519), `ikB_pub`, `nB` (nonce).
   - Shared Secret: `ECDH(eB_priv, eA_pub)`.
   - Signature: `Ed25519_Sign(ikB_priv, Transcript_Full)`.
3. **PFS_FINAL**:
   - Alice verifies Bob's signature and sends her own signature on the full transcript.

**Session ID**: `SHA256(Handshake Transcript Full)`.

## 4. Double Ratchet Algorithm

Guarantees Perfect Forward Secrecy (PFS) and Post-Compromise Security.

### 4.1 Root Chain
Advanced by Diffie-Hellman steps.
- `KDF_Root(RK, DH_out) -> (Next_RK, CK)`
- Salt: Previous Root Key. IKM: DH output. Label: `TorChat/v2/dr/root`.

### 4.2 Symmetric Ratchet
Advances for every message sent or received.
- `KDF_Chain(CK) -> (Next_CK, MessageKey)`
- Label: `TorChat/v2/dr/chain/sending` or `receiving`.

### 4.3 DH Ratchet Step
Triggered by receiving a new ephemeral key from the peer.
1. Complete receiving chain.
2. `DH_out = ECDH(my_priv, peer_pub)`.
3. Update Root Chain → new `CK_recv`.
4. Generate new ephemeral key pair.
5. `DH_out = ECDH(my_new_priv, peer_pub)`.
6. Update Root Chain → new `CK_send`.

## 5. Message Encryption (AES-256-GCM)

- **Algorithm**: AES-256-GCM (12-byte IV, 16-byte Tag).
- **AAD (Additional Authenticated Data)**:
  - Binary concatenation of: `version`, `type`, `seq`, `sender_onion`, `session_id`, `ratchet_public_key`, `pn`, `n`.
  - Ensures that metadata cannot be tampered with or re-used across sessions.

## 6. Session Lifecycle (SessionManager)

1. **Discovery**: Onion address exchange (manual or pairing).
2. **Handshake**: Execution of the 3-Way Exchange.
3. **Active**: Double Ratchet messaging.
4. **Re-keying**: Automatic transition to new ephemeral keys.
5. **Termination**: Keys wiped from memory on session close.
