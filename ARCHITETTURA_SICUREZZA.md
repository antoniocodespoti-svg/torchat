# TorP2PChat: Architettura di Sicurezza e Anonimato

Questo documento fornisce una panoramica tecnica per i revisori della sicurezza.

## 1. Comunicazione di Rete (Network Layer)
- **Topologia**: 100% Peer-to-Peer (P2P) puro. Nessun server centrale di messaggistica.
- **Protocollo**: Tor Hidden Services v3 (Onion).
- **Flusso**: Alice (Client) → Proxy SOCKS5 Locale (Tor) → Circuito Tor Mondiale → Hidden Service Bob (Client).
- **Protezioni**:
    - `InetSocketAddress.createUnresolved`: Forza la risoluzione DNS solo tramite Tor, prevenendo leak dell'indirizzo IP reale.
    - **Padding Avanzato**: I pacchetti vengono "gonfiati" a bucket standard (4KB, 128KB, 512KB, 1MB, 2MB, 5MB) per contrastare l'analisi del traffico basata sulla dimensione.
    - **Timing Jitter**: Ritardo casuale (100ms-1s) prima dell'invio per contrastare l'analisi temporale.
    - **Noise Generator**: Invio periodico di pacchetti "Dummy" cifrati.

## 2. Crittografia (Encryption Layer)
- **Cifratura End-to-End (E2EE)**: Implementata a livello applicativo prima dell'invio nel tunnel Tor.
- **Scambio Chiavi**: ECDH (Elliptic Curve Diffie-Hellman) utilizzando la curva NIST P-256 (secp256r1).
- **Cifratura Simmetrica**: AES-256-GCM (Galois/Counter Mode) per garantire riservatezza e integrità.
- **Forward Secrecy**: Rotazione automatica della chiave di sessione AES ogni **5 messaggi**.
- **Hashing Locale**: Argon2id v1.6.0 (64MB RAM, 2 iterazioni) per la protezione della password dell'app.

## 3. Gestione Identità e Chiavi
- **Identità**: Basata sulla chiave pubblica dell'Hidden Service di Tor (indirizzo .onion) + chiave di identità crittografica ECDH.
- **Verifica**: Supporto per la verifica manuale dell'identità (confronto fingerprint o QR Code) per prevenire attacchi Man-In-The-Middle (MITM).
- **Conservazione**: Chiavi private salvate esclusivamente nel sistema protetto `EncryptedSharedPreferences` (Keystore hardware se disponibile).

## 4. Privacy e Gestione Dati Locali
- **Politica di Non-Persistenza Messaggi**: Nessun database di messaggi è presente sul disco. I messaggi decifrati vivono esclusivamente nella memoria volatile (Snapshots di stato) e vengono distrutti alla chiusura del processo dell'app.
- **Eccezione Allegati**: Gli allegati (foto/file) possono essere salvati permanentemente nel dispositivo esclusivamente su azione esplicita dell'utente tramite il pulsante "Salva". In quel caso, i file vengono gestiti dal MediaStore di sistema e non sono più sotto il controllo di sicurezza dell'app.
- **Cancellazione Dati (Wipe)**: Dopo 3 tentativi di password errati, l'app esegue la cancellazione dei propri dati sensibili (SharedPreferences, Master Key hardware, chiavi Tor). **Nota**: I file precedentemente esportati dall'utente (come i backup JSON o gli allegati salvati) non vengono rimossi da questa procedura.
- **Screenshot Protection**: `FLAG_SECURE` attivo su tutte le schermate sensibili.
- **Metadata Stripping**: Rimozione automatica dei metadati EXIF dalle immagini prima dell'invio.

## 5. Backup
- **Formato**: JSON cifrato con AES-256 (chiave derivata da Mnemonic Seed 12 parole + Salt).
- **Recuperabilità**: Il Salt crittografico è incluso nel pacchetto di backup per permettere il ripristino dopo un Wipe o su un nuovo dispositivo senza dipendere dal Keystore originale.
