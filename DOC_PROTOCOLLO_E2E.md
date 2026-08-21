# Specifica Protocollo TorChat E2EE (v2.3)

Questo documento descrive il protocollo di crittografia end-to-end utilizzato da TorChat per la comunicazione sicura tra peer su rete Tor.

## 1. Handshake e Scambio Chiavi

TorChat utilizza un meccanismo di handshake basato su chiavi effimere X25519 e firme di identità Ed25519. La versione 2.3 introduce **Handshake Nonces** per garantire la freshness e prevenire attacchi di replay dell'intero handshake.

### Fasi dell'Handshake:
1.  **Iniziatore (Alice)**:
    *   Genera una coppia di chiavi effimere X25519 (`eA_pub`, `eA_priv`) e un **nonce casuale** (`nA`) di 16 byte.
    *   Crea un **Handshake Transcript** binario canonico (length-prefixed) contenente:
        *   `initiator_onion`, `responder_onion`
        *   `initiator_identity_key`, `initiator_ephemeral_key`
        *   `responder_identity_key`, `responder_ephemeral_key` (vuote)
        *   `initiator_nonce` (`nA`), `responder_nonce` (vuoto)
    *   Firma il transcript con la propria chiave di identità Ed25519.
    *   Invia (`eA_pub`, firma, `identity_key_A`, `nA`) a Bob.

2.  **Risponditore (Bob)**:
    *   Riceve il pacchetto di Alice.
    *   Ricostruisce il transcript (usando `nA`) e verifica la firma Ed25519.
    *   Verifica l'identità di Alice (TOFU).
    *   Genera la propria coppia effimera X25519 (`eB_pub`, `eB_priv`) e un proprio **nonce** (`nB`).
    *   Crea e firma il **Transcript Completo** (includendo entrambi i nonce e le chiavi).
    *   Calcola il **Shared Secret** tramite X25519 ECDH: `ECDH(eB_priv, eA_pub)`.
    *   Deriva il **Session ID**: `SHA256(Transcript Completo)`.
    *   Invia (`eB_pub`, firma, `identity_key_B`, `nA`, `nB`) ad Alice.

3.  **Finalizzazione (Alice)**:
    *   Verifica che `nA` restituito sia corretto.
    *   Verifica la firma di Bob sul Transcript Completo.
    *   Calcola lo stesso **Shared Secret** e lo stesso **Session ID**.

## 2. Derivazione delle Chain Key (Split Ratchet)

Alice e Bob derivano due catene di chiavi simmetriche distinte utilizzando HKDF-SHA256 con etichette direzionali.

*   `Chain_A_to_B = HKDF(SharedSecret, salt=null, info="TorChat/v2/chain/OnionA->OnionB", len=32)`
*   `Chain_B_to_A = HKDF(SharedSecret, salt=null, info="TorChat/v2/chain/OnionB->OnionA", len=32)`

Alice imposta: `sendChain = Chain_A_to_B`, `receiveChain = Chain_B_to_A`.
Bob imposta: `sendChain = Chain_B_to_A`, `receiveChain = Chain_A_to_B`.

## 3. Symmetric Split Ratchet

Ogni messaggio avanza la catena simmetrica. Il ratchet garantisce che la compromissione di una chiave di messaggio non comprometta i messaggi passati (**Forward Secrecy** all'interno della sessione).

> [!NOTE]
> Il protocollo non fornisce attualmente *Post-Compromise Security* in quanto non implementa un DH ratchet periodico. Se la Chain Key viene compromessa, i messaggi futuri della stessa sessione sono vulnerabili finché non viene eseguito un nuovo handshake.

### Session Binding e AAD (v2.3):
Ogni pacchetto è legato crittograficamente alla sessione tramite il **Session ID** incluso nei dati autenticati addizionali (AAD).

### Gestione Concorrenza e Atomicità:
*   **Atomicità Key/Sequence**: L'invio di un messaggio recupera la chiave e il numero di sequenza in un'unica operazione atomica (sotto Mutex).
*   **Avanzamento Atomico**: Lo stato del ratchet di ricezione viene aggiornato **solo dopo** che la decifratura del messaggio ha avuto successo (validazione del tag GCM).

## 4. Cifratura dei Messaggi (AES-256-GCM)

*   **Algoritmo**: AES/GCM/NoPadding.
*   **AAD (Additional Authenticated Data)**:
    *   `version` (1 byte)
    *   `payload_type` (1 byte)
    *   `sequence_number` (4 byte)
    *   `sender_onion` (stringa UTF-8)
    *   `session_id` (stringa Base64)

## 5. Framing Binario e Protezione Network

I pacchetti sono inviati in formato binario:
1.  `MAGIC_BYTE` (1 byte, 0x54)
2.  `VERSION` (1 byte, 0x01)
3.  `TYPE` (1 byte)
4.  `SEQUENCE` (4 byte)
5.  `SENDER_ONION_LENGTH` (4 byte)
6.  `SENDER_ONION` (variabile)
7.  `PAYLOAD_LENGTH` (4 byte)
8.  `PAYLOAD` (Base64 dell'IV + Ciphertext + Tag)

### Misure Anti-DoS:
*   Timeout di 5 secondi per gli header binari.
*   Dimensione massima del payload: 1 MB.
*   Validazione rigorosa dell'indirizzo `.onion` del mittente tramite Regex.
