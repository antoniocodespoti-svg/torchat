# Specifica Protocollo TorChat E2EE (v2.2)

Questo documento descrive il protocollo di crittografia end-to-end utilizzato da TorChat per la comunicazione sicura tra peer su rete Tor.

## 1. Handshake e Scambio Chiavi

TorChat utilizza un meccanismo di handshake basato su chiavi effimere X25519 e firme di identità Ed25519 per garantire l'autenticità e la segretezza dei messaggi futuri (forward secrecy per le sessioni).

### Fasi dell'Handshake:
1.  **Iniziatore (Alice)**:
    *   Genera una coppia di chiavi effimere X25519 (`eA_pub`, `eA_priv`).
    *   Crea un **Handshake Transcript** binario canonico (length-prefixed) contenente le identità e le chiavi effimere note.
    *   Firma il transcript con la propria chiave di identità Ed25519.
    *   Invia (`eA_pub`, firma, `identity_key_A`) a Bob.

2.  **Risponditore (Bob)**:
    *   Riceve il pacchetto di Alice.
    *   Ricostruisce e verifica la firma Ed25519 sul transcript.
    *   Verifica l'identità di Alice (TOFU).
    *   Genera la propria coppia effimera X25519 (`eB_pub`, `eB_priv`).
    *   Crea e firma il proprio transcript completo.
    *   Calcola il **Shared Secret** tramite X25519 Diffie-Hellman: `ECDH(eB_priv, eA_pub)`.
    *   Invia (`eB_pub`, firma, `identity_key_B`) ad Alice.

3.  **Finalizzazione (Alice)**:
    *   Verifica la firma di Bob sul transcript completo.
    *   Calcola lo stesso **Shared Secret**: `ECDH(eA_priv, eB_pub)`.

## 2. Derivazione delle Chain Key (Split Ratchet)

Per evitare problemi di simmetria e collisioni di sequenza, Alice e Bob derivano due catene di chiavi simmetriche distinte utilizzando HKDF-SHA256 con etichette direzionali basate sugli indirizzi Onion.

*   `Chain_A_to_B = HKDF(SharedSecret, salt=null, info="TorChat/v2/chain/OnionA->OnionB", len=32)`
*   `Chain_B_to_A = HKDF(SharedSecret, salt=null, info="TorChat/v2/chain/OnionB->OnionA", len=32)`

Alice imposta: `sendChain = Chain_A_to_B`, `receiveChain = Chain_B_to_A`.
Bob imposta: `sendChain = Chain_B_to_A`, `receiveChain = Chain_A_to_B`.

## 3. Symmetric Split Ratchet

Ogni messaggio avanza la catena simmetrica. Il ratchet garantisce che la compromissione di una chiave di messaggio non comprometta i messaggi passati (Forward Secrecy all'interno della sessione).

*   `KDF_Step(ChainKey, label) -> (NextChainKey, MessageKey)`
*   Utilizza HKDF-SHA256 con info: `"TorChat/v2/ratchet/" + label`.

### Gestione Concorrenza e Atomicità (v2.2):
*   **Atomicità Key/Sequence**: L'invio di un messaggio recupera la chiave e il numero di sequenza in un'unica operazione atomica (sotto Mutex) per evitare collisioni di sequenza in caso di invii simultanei.
*   **Avanzamento Atomico**: Lo stato del ratchet di ricezione viene aggiornato **solo dopo** che la decifratura del messaggio ha avuto successo (validazione del tag GCM). Questo previene attacchi di desincronizzazione tramite pacchetti malevoli.

## 4. Cifratura dei Messaggi (AES-256-GCM)

*   **Algoritmo**: AES/GCM/NoPadding.
*   **Chiave**: `MessageKey` (256 bit).
*   **IV**: 12 byte (generati casualmente per ogni messaggio).
*   **Tag di Autenticazione**: 128 bit.
*   **AAD (Additional Authenticated Data)**:
    *   `version` (1 byte)
    *   `payload_type` (1 byte)
    *   `sequence_number` (4 byte)
    *   `sender_onion` (stringa UTF-8)

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
*   Massimo 5 connessioni simultanee.
