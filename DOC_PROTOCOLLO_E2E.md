# Specifica Protocollo TorChat E2EE (v2.4.2)

Questo documento descrive il protocollo di crittografia end-to-end utilizzato da TorChat per la comunicazione sicura tra peer su rete Tor.

## 1. Handshake e Scambio Chiavi (3-Way Authenticated Exchange)

La versione 2.4.2 consolida l'**Handshake a 3 vie** con protezioni atomiche anti-DoS.

### Fasi dell'Handshake:
1.  **Iniziatore (Alice) - PFS_INIT**:
    *   Genera una coppia di chiavi effimere X25519 (`eA_pub`, `eA_priv`) e un **nonce casuale** (`nA`) di 16 byte.
    *   Firma il **Transcript di Inizio** (`v2/init | onions | ikA | eA | nA`) con la propria chiave di identità Ed25519.
    *   Invia (`eA_pub`, `identity_key_A`, `nA`, firma_Alice_Init) a Bob.

2.  **Risponditore (Bob) - PFS_ACCEPT**:
    *   Riceve il pacchetto di Alice.
    *   **Verifica immediatamente la firma di Alice** prima di procedere.
    *   Genera la propria coppia effimera X25519 (`eB_pub`, `eB_priv`) e un proprio **nonce challenge** (`nB`).
    *   Crea un **Handshake Transcript** binario canonico contenente le identità, le chiavi effimere e i nonce di entrambi.
    *   Firma il transcript con la propria chiave di identità Ed25519.
    *   Invia (`eB_pub`, firma_Bob, `identity_key_B`, `nA_echo`, `nB`) ad Alice.
    *   Bob memorizza lo stato in `HandshakeManager` (DoS protected).

3.  **Iniziatore (Alice) - PFS_FINAL**:
    *   Riceve `PFS_ACCEPT`.
    *   Verifica la firma di Bob sul transcript ricostruito.
    *   Firma lo stesso transcript completo con la propria chiave di identità Ed25519.
    *   Invia (firma_Alice_Final, `nA_echo`, `nB_echo`) a Bob.
    *   Alice attiva la sessione.

4.  **Finalizzazione (Bob)**:
    *   Riceve `PFS_FINAL`.
    *   Verifica la firma finale di Alice sul transcript memorizzato.
    *   Bob attiva la sessione.

### Protezioni DoS (v2.4.2):
*   **Limiti Handshake Atomici**: Massimo 3 handshake pendenti per peer e 50 totali a livello globale. L'enforcement dei limiti è atomico (thread-safe) per prevenire bypass tramite richieste concorrenti.
*   **Anti-Overwrite**: Non è possibile sovrascrivere un handshake pendente con lo stesso nonce, prevenendo attacchi di reset della sessione durante l'handshake.
*   **Clock Monotonico**: Cleanup degli handshake scaduti (TTL 60s) basato su clock di sistema non alterabile dall'utente.
*   **Autenticazione Precoce**: Il risponditore scarta richieste non firmate o non valide al primo step senza allocare risorse pesanti.

## 2. Derivazione delle Chain Key (Split Ratchet)

Alice e Bob derivano due catene di chiavi simmetriche distinte utilizzando HKDF-SHA256 con etichette direzionali.

*   `Chain_A_to_B = HKDF(SharedSecret, salt=null, info="TorChat/v2/chain/OnionA->OnionB", len=32)`
*   `Chain_B_to_A = HKDF(SharedSecret, salt=null, info="TorChat/v2/chain/OnionB->OnionA", len=32)`

## 3. Symmetric Split Ratchet

Ogni messaggio avanza la catena simmetrica. Il ratchet garantisce la **Forward Secrecy** all'interno della sessione.

> [!NOTE]
> Il protocollo non fornisce attualmente *Post-Compromise Security* in quanto non implementa un DH ratchet periodico.

### Session Binding e AAD:
Ogni pacchetto è legato crittograficamente alla sessione tramite il **Session ID** (SHA256 del Transcript completo) incluso nei dati autenticati addizionali (AAD).

## 4. Protezioni di Sicurezza

*   **Handshake Replay Protection**: Il risponditore (Bob) verifica la liveness dell'iniziatore tramite il 3rd step.
*   **Atomic State Updates**: Il ratchet avanza solo dopo una decifratura GCM avvenuta con successo.
*   **Onion Validation**: Tutti gli indirizzi .onion sono validati tramite Regex prima di ogni operazione.
