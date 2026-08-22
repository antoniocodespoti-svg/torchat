# Specifica Protocollo TorChat E2EE (v2.8)

Questo documento descrive il protocollo di crittografia end-to-end utilizzato da TorChat (v2.8+) per la comunicazione sicura.

## 1. Handshake e Pairing Atomico

TorChat v2.8 implementa un handshake a 3 vie con **consumo dello stato verificato ed atomico**.

### Proprietà di Sicurezza:
*   **Verify-and-Consume Atomico**: Lo stato dell'handshake pendente viene rimosso dal gestore tramite un'operazione atomica (`verifyAndConsume`) solo **dopo** che la firma del peer è stata validata. Questo elimina race condition e previene attacchi DoS.
*   **One-Time Tokens**: I token di pairing (QR) usano nonce a 128-bit a uso singolo, con consumo atomico post-verifica della firma.

## 2. Double Ratchet Transazionale

L'algoritmo Double Ratchet garantisce *Forward Secrecy* e *Post-Compromise Security*.

### 2.1 Gestione dello Stato e Rollback
La ricezione di ogni messaggio segue il pattern **Snapshot -> Try -> Commit**:
1.  Viene creato uno snapshot completo dello stato della sessione.
2.  Si calcolano le chiavi derivate e si tenta la decifratura AES-256-GCM.
3.  **Commit**: Solo se l'autenticazione ha successo, lo stato viene aggiornato permanentemente.
4.  **Rollback**: Se la decifratura fallisce, lo stato viene ripristinato dallo snapshot, mantenendo la sincronizzazione.

### 2.2 Secure Memory Wiping
Le chiavi dei messaggi e le chiavi saltate vengono azzerate (`fill(0)`) immediatamente dopo l'uso o la rimozione, minimizzando la persistenza di dati sensibili in RAM.

## 3. Hardening di Rete e DoS

*   **Framing Flessibile**: I pacchetti di controllo possono avere una chiave ratchet di lunghezza zero.
*   **Validazione Rigorosa Header**: `PN` e `N` devono essere compresi tra `0` e `10000`.
*   **Limiti Skipped Keys**: Massimo 1000 chiavi saltate globalmente per sessione, con un gap massimo di 100 messaggi. Qualsiasi violazione blocca l'elaborazione del pacchetto.

## 4. Identità e Storage

*   **Identità Ed25519**: Derivazione standard-compliant dal seme mnemonico tramite PKCS#8.
*   **Fail-Closed Policy**: Blocco totale in caso di errori critici del Keystore Hardware o del database peer.
*   **Sequenza di Rete**: Uso di `AtomicInteger` per prevenire collisioni nei numeri di sequenza dei pacchetti.
