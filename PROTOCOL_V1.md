# Specifica Protocollo TorChat E2EE (V1)

Questo documento definisce lo standard normativo per la crittografia end-to-end (E2EE) in TorChat.

## 1. Identità e Chiavi Long-Term

Ogni utente è identificato da:
*   **Onion Address**: Indirizzo v3 della rete Tor.
*   **Identity Key (IK)**: Coppia di chiavi Ed25519 utilizzata per la firma e l'autenticazione dell'identità.
*   **Long-Term Public Key**: Scambiata durante il pairing iniziale (Trust On First Use - TOFU).

## 2. Handshake Iniziale (3-Way Authenticated Exchange)

L'handshake stabilisce il `SharedSecret` iniziale e il `SessionID`.

1.  **PFS_INIT (Alice)**: Alice genera `eA` (X25519) e `nA` (nonce 16 byte). Invia `eA_pub`, `ikA_pub`, `nA` e la firma di Alice sul transcript iniziale.
2.  **PFS_ACCEPT (Bob)**: Bob verifica la firma, genera `eB` (X25519) e `nB` (nonce 16 byte). Calcola `SharedSecret = ECDH(eB, eA)`. Invia `eB_pub`, `ikB_pub`, `nA`, `nB` e la firma di Bob sul transcript completo.
3.  **PFS_FINAL (Alice)**: Alice verifica la firma di Bob, calcola lo stesso `SharedSecret`. Invia la propria firma sul transcript completo.

**Session ID**: `SHA256(Handshake Transcript completo)`.

## 3. Algoritmo Double Ratchet

TorChat implementa l'algoritmo **Double Ratchet** per garantire Forward Secrecy e Post-Compromise Security.

### 3.1 Root Chain
Derivata dal `SharedSecret` iniziale. Ogni passo DH (Diffie-Hellman Ratchet) avanza la Root Chain.
*   `KDF_Root(RK, DH_out) -> (Next_RK, CK)`

### 3.2 Chain Key (Symmetric Ratchet)
Esistono due catene simmetriche per sessione: **Sending Chain** e **Receiving Chain**.
*   `KDF_Chain(CK) -> (Next_CK, MessageKey)`
*   Usa HKDF-SHA256 con etichette direzionali.

### 3.3 DH Ratchet
Ogni volta che viene ricevuto un messaggio con una nuova chiave pubblica `e_peer`, il destinatario:
1.  Termina la catena di ricezione corrente.
2.  Esegue un passo DH: `DH_out = ECDH(my_e_priv, e_peer_pub)`.
3.  Avanza la Root Chain per ottenere una nuova `CK_recv`.
4.  Genera una propria nuova coppia effimera `my_e_new`.
5.  Esegue un altro passo DH: `DH_out = ECDH(my_e_new_priv, e_peer_pub)`.
6.  Avanza la Root Chain per ottenere una nuova `CK_send`.

## 4. Cifratura dei Messaggi (AES-256-GCM)

*   **AES-256-GCM** con IV da 12 byte e Tag da 128 bit.
*   **AAD (Additional Authenticated Data)**:
    *   `version` (1 byte, 0x01)
    *   `payload_type` (1 byte)
    *   `sequence_number` (4 byte)
    *   `session_id` (stringa)
    *   `sender_onion` (stringa)
    *   `ratchet_public_key` (chiave pubblica DH corrente dell'invitante)

## 5. Gestione Messaggi Saltati (Out-of-Order)

*   Le chiavi dei messaggi saltati vengono memorizzate in una map temporanea.
*   **Limite**: Massimo 1000 chiavi saltate per sessione per prevenire attacchi DoS di memoria.

## 6. Pairing e Sicurezza dell'Identità

*   **TOFU**: La prima chiave di identità ricevuta per un indirizzo Onion viene "pinnata".
*   **Alert**: Se la chiave di identità di un peer cambia, TorChat blocca la sessione e avvisa l'utente (possibile MITM).
*   **Verifica Manuale**: L'utente deve confrontare il Fingerprint Ed25519 (SHA256 della chiave pubblica) o scansionare il QR Code per confermare l'identità del peer.
