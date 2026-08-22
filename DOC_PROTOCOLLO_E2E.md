# Specifica Protocollo TorChat E2EE (v2.6)

Questo documento descrive il protocollo di crittografia end-to-end utilizzato da TorChat per la comunicazione sicura tra peer su rete Tor.

## 1. Handshake e Scambio Chiavi (3-Way Authenticated Exchange)

TorChat v2.6 utilizza un **Handshake a 3 vie** completamente autenticato e resistente al replay.

### Fasi dell'Handshake:
1.  **PFS_INIT (Alice)**: Alice genera `eA` (X25519) e `nA` (nonce 16 byte). Invia `eA_pub`, `ikA_pub`, `nA` e la firma di Alice sul transcript iniziale (`v2/init`).
2.  **PFS_ACCEPT (Bob)**: Bob verifica la firma, genera `eB` (X25519) e `nB` (nonce 16 byte). Invia `eB_pub`, `ikB_pub`, `nA`, `nB` e la firma di Bob sul transcript completo (`v2/hand`).
3.  **PFS_FINAL (Alice)**: Alice verifica la firma di Bob e invia la propria firma finale sul transcript completo.

**Session ID**: `SHA256(Handshake Transcript completo)`.

## 2. Algoritmo Double Ratchet

TorChat implementa l'algoritmo **Double Ratchet** standard per garantire Forward Secrecy e Post-Compromise Security.

### 2.1 State Management Transazionale
Lo stato della sessione (Root Key, Chain Keys, N, PN) viene aggiornato **solo dopo** che il messaggio ricevuto è stato autenticato con successo tramite il tag AES-GCM. Qualsiasi errore di decifratura comporta il rollback istantaneo allo stato precedente, prevenendo la corruzione della sessione.

### 2.2 Header del Protocollo
Ogni messaggio include un header contenente:
*   `ratchet_public_key`: Chiave pubblica DH corrente del mittente.
*   `pn`: Lunghezza della catena di invio precedente (Previous Counter).
*   `n`: Numero del messaggio nella catena corrente (Message Counter).

### 2.3 Derivazione delle Chiavi
*   `KDF_Root(RK, DH_out) -> (Next_RK, CK)`
*   `KDF_Chain(CK) -> (Next_CK, MessageKey)`
*   Utilizza HKDF-SHA256 con separazione di dominio (`TorChat/v2/dr/...`).

## 3. Cifratura e AAD

*   **Algoritmo**: AES-256-GCM.
*   **AAD (Additional Authenticated Data)**: Include versione, tipo, Session ID, sender Onion, ratchet key, PN e N. Questo garantisce che un messaggio sia legato indissolubilmente alla sessione e alla sua posizione nel ratchet.

## 4. Identità Deterministica

L'identità Ed25519 e l'indirizzo .onion sono derivati deterministicamente dal seme della mnemonica a 12 parole tramite HKDF-SHA256. Questo garantisce che il possesso della mnemonica sia sufficiente e necessario per recuperare l'intera identità crittografica.

## 5. Protezioni Anti-DoS e Network

*   **Handshake Limits**: Massimo 3 handshake pendenti per peer e 50 globali, con enforcement atomico.
*   **Framing Validation**: Validazione rigorosa delle lunghezze dei campi prima di ogni allocazione di memoria.
*   **Skipped Keys Limit**: Massimo 1000 chiavi saltate memorizzate per sessione, con un gap massimo di 100 messaggi.
