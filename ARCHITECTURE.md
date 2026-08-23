# TorP2PChat: Security & Privacy Architecture

This document provides a technical overview of the TorP2PChat security model for auditors and developers.

## 1. Network Layer (Tor P2P)
TorP2PChat operates as a pure Peer-to-Peer (P2P) network using **Tor Hidden Services (Onion v3)**. There are no central messaging servers.

- **Topology**: Alice (Client) → Local SOCKS5 Proxy (Tor) → Tor Circuit → Hidden Service Bob (Client).
- **Metadata Protection**:
    - **IP Anonymity**: Real IP addresses are never exposed to peers or the network.
    - **DNS Leak Prevention**: Uses `InetSocketAddress.createUnresolved` to force all name resolutions through the Tor proxy.
    - **Binary Framing (NET-001)**: All packets follow a strict binary format to minimize metadata leakage and prevent protocol identification.
- **Traffic Analysis Resistance**:
    - **Padding**: Packets are padded to standard bucket sizes (up to 5MB) to obscure payload length.
    - **Timing Jitter**: Random delays (100ms-1s) are introduced to thwart temporal correlation attacks.
    - **Noise**: Periodic encrypted "Dummy" packets are sent to maintain constant traffic patterns.

## 2. Cryptography Layer (E2E V2)
End-to-End Encryption (E2EE) is applied at the application level before data enters the Tor tunnel.

- **Identity**: Ed25519 key pairs derived deterministically from a 32-byte seed (RFC 8032).
- **Forward Secrecy (PFS)**: Achieved via **Double Ratchet** algorithm (Signal Protocol derivative).
    - **Initial Handshake**: 3-Way Authenticated Exchange using X25519 ephemerals.
    - **DH Ratchet**: X25519 rotation for each response.
    - **Symmetric Ratchet**: HKDF-SHA256 based chain key advancement for every message.
- **Encryption**: **AES-256-GCM** provides both confidentiality and authenticity.
    - **AAD (Additional Authenticated Data)**: Includes Version, Type, Seq, SessionID, and Ratchet Counters to prevent replay and reflection attacks.
- **Key Derivation**: **HKDF-SHA256** is used for all internal key derivations.

## 3. Local Security & Data Management
- **Hardware-Backed Protection**: The Master Key is stored in the **Android Keystore**, utilizing hardware-backed security (TEE/SE) whenever available.
- **Password Hashing**: **Argon2id** (64MB, 2 iterations) protects the local application password.
- **Non-Persistence Policy**:
    - Messages are stored only in volatile memory and are destroyed when the app process terminates.
    - **Attachments**: Photos and files are only saved to persistent storage upon explicit user action.
- **Self-Destruct (Wipe)**: Local sensitive data (SharedPreferences, Master Key, Tor keys) is wiped after 3 failed authentication attempts.
- **UI Protection**: `FLAG_SECURE` is active on all sensitive screens to prevent unauthorized screenshots or screen recording.

## 4. Backup Model
- **Format**: JSON backup encrypted with AES-256-GCM.
- **Key Source**: Key derived from a 12-word Mnemonic Seed (BIP-39) + Salt.
- **Portability**: Backups can be restored on new devices using the Mnemonic Seed, as the cryptographic salt is embedded in the backup file.
