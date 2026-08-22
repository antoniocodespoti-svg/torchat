# Specifica Protocollo TorChat E2EE (v2.7)

Questo documento descrive il protocollo di crittografia end-to-end utilizzato da TorChat (v2.7+) per la comunicazione sicura.

## 1. Handshake e Pairing Atomico

TorChat v2.7 implementa un handshake a 3 vie con **consumo dello stato verificato**.

### Proprietà di Sicurezza:
*   **Verify-before-Consume**: Lo stato dell'handshake pendente viene rimosso dal gestore solo **dopo** che la firma del peer è stata validata. Questo previene attacchi DoS che mirano a resettare i pairing legittimi tramite pacchetti contraffatti.
*   **One-Time Tokens**: I token di pairing (QR) usano nonce a 128-bit a uso singolo, validati solo dopo la verifica della firma del token stesso.

## 2. Double Ratchet Transazionale

L'algoritmo Double Ratchet garantisce *Forward Secrecy* e *Post-Compromise Security*.

### 2.1 Gestione dello Stato (Rollback)
La ricezione di ogni messaggio segue il pattern **Snapshot -> Try -> Commit**:
1.  Viene creato uno snapshot dello stato corrente (Root Key, Chain Keys, contatori).
2.  Si calcolano le chiavi derivate e si tenta la decifratura AES-256-GCM.
3.  **Commit**: Se (e solo se) il tag di autenticazione GCM è valido, lo stato snapshot viene applicato come stato definitivo.
4.  **Rollback**: In caso di errore, lo snapshot viene scartato e la sessione rimane sincronizzata all'ultimo stato valido.

### 2.2 Header e AAD
Ogni pacchetto include un header con `Ratchet_PK`, `PN` e `N`.
I dati autenticati (AAD) includono: `versione | tipo | sequenza_rete | sessionID | onion_mittente | ratchet_pk | PN | N`.

## 3. Hardening di Rete e DoS

*   **Framing Flessibile**: I pacchetti di controllo (Handshake, Pong) possono avere una chiave ratchet di lunghezza zero. I pacchetti dati (Message, File) richiedono obbligatoriamente una chiave valida.
*   **Validazione Contatori**: `PN` e `N` devono essere compresi tra `0` e `10000`.
*   **Limiti Skipped Keys**: Massimo 1000 chiavi saltate memorizzate globalmente per sessione, con un gap massimo di 100 messaggi tra pacchetti consecutivi.

## 4. Identità e Storage

*   **Fail-Closed Policy**: Qualsiasi errore nel caricamento del seme mnemonico o nella decifratura del database peer (Hardware Keystore) blocca l'operazione invece di ricorrere a fallback insicuri (plaintext o semi casuali).
*   **Limiti Allegati**: Gli allegati sono limitati a 1MB e la dimensione viene verificata **prima** del caricamento in memoria RAM.
