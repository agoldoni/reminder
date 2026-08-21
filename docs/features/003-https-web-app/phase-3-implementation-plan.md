# Traffico cifrato per la web app locale — Implementation Plan

**Stato:** Bozza — in attesa di approvazione
**Autore:** Alberto Goldoni
**Data:** 2026-08-21
**Versione:** 1.0

---

## 1. Executive Summary

La web app locale introdotta con la feature 002 serve i promemoria a un browser sulla rete locale
**in chiaro**: chi condivide il Wi-Fi e sa ascoltare legge il codice d'accesso e la lista. Questa
feature mette TLS sotto quella porta, usando un certificato **autofirmato generato dall'app una
volta sola**. Il browser mostrerà un avviso di certificato la prima volta e lo si scavalca; in
cambio, da quel momento nessuno sul filo vede più nulla. Non si installa niente sul computer e non
cambia nient'altro: stessa porta, stesso codice d'accesso, stessa pagina. Stima: **5,25
giorni/uomo**, intervallo realistico 4–7.

---

## 2. Obiettivo e motivazione

- **Problema che risolve:** il modello di sicurezza della 002 poggia su un token che quello stesso
  modello fa viaggiare in chiaro nella query string di ogni richiesta. Chi lo intercetta una volta
  ha accesso completo alla lista finché l'interruttore resta acceso; e intanto legge anche i
  promemoria stessi. È una contraddizione interna, accettata allora perché l'unica alternativa
  disponibile — un autofirmato — costava un avviso del browser senza portare in cambio nient'altro
  che la cifratura. Ora la cifratura *è* l'obiettivo, e il baratto cambia di segno.

- **Chi è l'attaccante realistico:** uno solo, il **Wi-Fi con password condivisa** — casa con
  ospiti, piccolo ufficio, albergo. Chi conosce la PSK può decifrare il traffico degli altri
  client: è un fatto noto di WPA2-Personal, non un attacco esotico. Su WPA3 o su una rete con
  credenziali personali non c'è nessuno da cui difendersi. Minaccia stretta ma vera, ed è la sua
  misura a stabilire quanto sia giusto pagarla.

- **Metriche di successo** (verifica manuale, nessuna analytics):
  - [ ] Con una cattura del traffico durante una sessione completa, il token non compare in chiaro
        in nessun pacchetto, e nemmeno il titolo di un promemoria.
  - [ ] Il browser mostra l'avviso **una volta**; proseguendo, la pagina funziona come prima.
  - [ ] Riavviando app e telefono, l'avviso **non** ritorna: il certificato è lo stesso.
  - [ ] Cambiando rete, l'indirizzo nuovo funziona subito senza toccare nulla.
  - [ ] L'impronta SHA-256 mostrata dall'app coincide con quella dei dettagli del certificato.
  - [ ] Nessuna regressione sui criteri della 002; APK non apprezzabilmente più grande, nessuna
        dipendenza nuova.

- **Legame con gli obiettivi di prodotto:** chiude R2 della 002 («il token nell'URL lascia
  tracce»), che lì era mitigato ma non risolto. Non apre nulla di nuovo all'utente: è manutenzione
  di sicurezza su una feature già consegnata.

---

## 3. Scope

### Incluso

- **Certificato autofirmato scritto a mano.** Codificatore ASN.1/DER minimale, `TBSCertificate`,
  estensioni (`subjectAltName` con indirizzi IP, `basicConstraints: CA:FALSE`, `keyUsage`,
  `extKeyUsage: serverAuth`), chiave **P-256** e firma `SHA256withECDSA`. Nessuna libreria nuova:
  su Android non c'è `keytool` né `sun.security.x509`, e BouncyCastle costerebbe megabyte — lo
  stesso ragionamento che ha tenuto fuori `material-icons-extended` e le librerie ODS.
- **Generazione una volta sola**, persistita accanto ai dati dell'app. È la regola che conta più di
  tutte: le eccezioni del browser sono per-certificato, e rigenerare significa rimettere l'utente
  davanti all'avviso.
- **TLS in ascolto:** `SSLServerSocket` in `HttpServer`, protocolli TLS 1.2/1.3, handshake fallito
  che chiude solo la propria connessione. **HTTP si spegne, non si affianca.**
- **Impronta SHA-256** mostrata dall'app, per chi vuole verificare invece di scavalcare alla cieca.
- **Interfaccia:** indirizzo con schema `https://`, e una riga che **anticipa** l'avviso del
  browser dicendo che è atteso.
- **Test** in `jvmSharedTest` e verifica sul campo, compresa la riesecuzione dei casi della 002.

### Escluso (out of scope)

- **CA locale, installazione sul client, assenza di avvisi** — sarebbe la strada per avere anche il
  lucchetto pulito, ma chiede di installare sul computer un'ancora di fiducia capace, in mano a chi
  ne possiede la chiave, di impersonare qualunque sito: sproporzionato per una lista in sola
  lettura, e triplicherebbe il lavoro. La prima stesura della Fase 1 la descriveva per intero: se
  l'obiettivo cambierà, si riparte da lì.
- **Secure context, service worker, cache offline, PWA installabile, `navigator.clipboard`** —
  restano dietro a un certificato *fidato*, che qui non c'è.
- **Riemissione del certificato al cambio di indirizzo** — inutile per costruzione, perché nessuno
  valida il SAN. È il vantaggio principale di questo scope, non una rinuncia.
- **Revoca, `.local` via mDNS, doppio ascolto HTTP+HTTPS, HSTS, mTLS** — motivazioni in
  [phase-1-requirements.md §2](phase-1-requirements.md).
- **Scrittura dal browser, attivazione su desktop, codice QR** — già rimandati dalla 002 e non
  toccati da TLS.

### Decisioni chiuse

Le sei decisioni aperte della Fase 2 sono chiuse qui. Restano due punti nelle *Domande residue*.

| # | Decisione | Scelta | Perché |
|---|---|---|---|
| D-01 | Formato di persistenza | **Due file DER**: chiave in `PKCS#8` (`privateKey.encoded`), certificato in DER (`cert.encoded`) | PKCS#12 vuole una password che qui starebbe nel sorgente: scriverla dichiarerebbe una protezione che non c'è. La protezione sono i permessi, come già dichiarato per il `sharedSecret` in `peers` |
| D-02 | `WebServiceTest`: TLS o chiaro | **Chiaro** per i dieci test esistenti, iniettando il fornitore predefinito; TLS in `TlsHandshakeTest` per la via vera | Quei test provano l'interruttore e il ciclo di vita, che sono ortogonali al trasporto. Convertirli costerebbe di più e non coprirebbe niente in più |
| D-03 | `enable()` su `Dispatchers.IO` | **Si misura prima** (T-13), si complica dopo | Sono millisecondi e il precedente esiste già (`ServerSocket()` è I/O e sta lì da sempre). Se la prima accensione costa meno di ~50 ms, la complicazione non si ripaga |
| D-04 | `subjectAltName` | Indirizzi IPv4 locali alla generazione **più `127.0.0.1`** | Nessuno li verificherà, ma costano venti righe, fanno un certificato onesto e tengono aperta la porta a un'installazione futura. `127.0.0.1` serve alla prova via `adb forward`: un certificato che vale in prova ma non in campo non prova nulla |
| D-05 | Redirect da HTTP a HTTPS riconoscendo il primo byte | **No** in questa iterazione | È parsing in più su una porta esposta per un beneficio di sola cortesia. Annotato per il seguito |
| D-06 | Dove sta la cartella su Android | **`filesDir`**, già privata all'app | Non `getDatabasePath()`, che è la cartella dei database e non deve ospitare altro |

---

## 4. User Stories e criteri di accettazione

### US-001 · Non farsi leggere da chi ascolta la rete
**Priorità:** Must Have

Come utente che consulta i promemoria dal PC su un Wi-Fi con la password condivisa, voglio che il
traffico sia cifrato, per non regalare il token — e la lista — a chi conosce quella password.

**Criteri di accettazione:**
- [ ] Il server accetta **solo** connessioni TLS: un `GET` in chiaro sulla porta 9888 non
      restituisce contenuto.
- [ ] Con una cattura del traffico durante una sessione completa (apertura, aggiornamenti, asset),
      il token non compare in chiaro in nessun pacchetto, e nemmeno il titolo di un promemoria.
- [ ] I protocolli negoziati sono TLS 1.2 o 1.3; una richiesta che offre solo protocolli più vecchi
      viene rifiutata.
- [ ] Un handshake fallito non abbatte il ciclo di ascolto: le connessioni successive funzionano.
- [ ] Il token continua a essere richiesto e verificato come prima: TLS non lo sostituisce.

### US-002 · L'avviso una volta, non a ogni avvio
**Priorità:** Must Have

Come utente voglio che l'avviso del browser, una volta accettato, non ritorni, per non ricominciare
da capo ogni volta che riaccendo il telefono.

**Criteri di accettazione:**
- [ ] Chiave e certificato sopravvivono alla chiusura dell'app e al riavvio del telefono.
- [ ] Due accensioni consecutive dell'interruttore presentano lo **stesso** certificato, byte per
      byte.
- [ ] Spegnere e riaccendere l'interruttore invalida il token ma **non** tocca il certificato.
- [ ] Aggiornare l'app senza disinstallarla non rigenera il certificato.
- [ ] In Firefox, accettata l'eccezione una volta, non viene più richiesta.

### US-003 · Cambiare rete senza pensarci
**Priorità:** Must Have

Come utente che passa da casa all'ufficio all'hotspot, voglio che continui a funzionare senza rifare
niente, perché l'indirizzo del telefono cambia e non è colpa mia.

**Criteri di accettazione:**
- [ ] Cambiando rete al telefono, l'app mostra un indirizzo nuovo che funziona subito.
- [ ] Il certificato **non** viene riemesso per il cambio di indirizzo.
- [ ] L'accesso via `adb forward` su `127.0.0.1` funziona con lo stesso certificato.
- [ ] Se il browser ripropone l'avviso sul nuovo indirizzo — perché l'eccezione è per-origine — lo
      fa una volta sola per indirizzo. *(Comportamento del browser, non nostro: si osserva, non si
      garantisce.)*

### US-004 · Sapere che l'avviso è atteso
**Priorità:** Should Have

Come utente voglio che sia l'app a dirmi che il browser protesterà e perché, per non pensare che si
sia rotto qualcosa — e voglio un'impronta da confrontare, se ci tengo a verificare davvero.

**Criteri di accettazione:**
- [ ] L'app dice, prima che l'utente apra il browser, che comparirà un avviso e che è atteso.
- [ ] L'app mostra l'impronta SHA-256 in formato leggibile e confrontabile (coppie esadecimali
      separate da due punti).
- [ ] L'impronta mostrata coincide con quella che il browser mostra nei dettagli del certificato.
- [ ] Il testo non minimizza: dice che scavalcando l'avviso si ottiene cifratura ma non
      autenticazione, e che confrontare l'impronta serve a quello.

### US-005 · Non perdere quello che già funzionava
**Priorità:** Must Have

Come utente voglio che tutto ciò che la web app già faceva continui a farlo, per non pagare la
cifratura con una regressione.

**Criteri di accettazione:**
- [ ] Tutti i criteri di §4 della 002 restano soddisfatti, sopra TLS.
- [ ] L'aggiornamento condizionale (`ETag` / `304`) funziona come prima.
- [ ] La porta resta aperta ad app chiusa, con il servizio in primo piano, come prima.
- [ ] Il tempo di apertura della pagina resta sotto i 2 secondi nonostante l'handshake per
      connessione.
- [ ] Nessuna dipendenza nuova, nessun permesso Android nuovo, nessuna crescita apprezzabile
      dell'APK.
- [ ] La suite completa resta verde; sincronizzazione, allarmi ed export non sono toccati.

---

## 5. Architettura tecnica

### Componenti coinvolti

```
  Browser sulla rete locale
        │  handshake TLS (certificato autofirmato → avviso da scavalcare)
        │  GET /?t=<token>            ── cifrato ──►
        ▼
  ┌──────────────────────────────────────────────────────────┐
  │  jvmSharedMain — package web/                            │
  │                                                          │
  │   ┌── NUOVO ──────────────────────────────────────────┐  │
  │   │  Der ──► SelfSignedCertificate ──► TlsIdentity    │  │
  │   │                    ▲                    │         │  │
  │   │              CertificateStore ──────────┘         │  │
  │   │              (due file DER, caricaOCrea)          │  │
  │   └───────────────────────┬───────────────────────────┘  │
  │                           │ SSLContext                   │
  │                           ▼                              │
  │   HttpServer  ──► HttpMessages (parsing, limiti)         │
  │       │  apriSocket: (Int) -> ServerSocket               │
  │       ▼                                                  │
  │   Router ──┬──► AccessToken   (403 / soglia)   invariati │
  │            ├──► StaticAssets  (elenco chiuso)  invariati │
  │            └──► WebPayload ──► EventDao        invariati │
  │                                                          │
  │   WebService  (implementa WebServerController)           │
  └──────────────────────────────────────────────────────────┘
        ▲                                    ▲
        │ costruito da ReminderApp           │ WebStatus (url https + impronta)
        │ con filesDir                       │
   androidApp                          SezioneWebApp (Compose)
```

**Il confine di piattaforma non si sposta.** `javax.net.ssl`, `java.security` e `java.io.File` sono
su entrambi i target JVM: nessun `expect`/`actual` nuovo, nessuna interfaccia in `commonMain`. È la
stessa constatazione che ha permesso di tenere in `jvmSharedMain` la crittografia della
sincronizzazione.

### Modifiche al data model

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| `events`, `peers` | **Nessuna** | Lo schema resta alla v5. Nessuna migrazione da scrivere, e quindi nessuna da annullare in caso di rollback |
| `WebStatus` (`commonMain`) | Modifica | `url` passa a `https://`; nuovo campo `impronta: String?` |
| File su disco | Nuovo | `filesDir/web-tls/chiave.der` (PKCS#8) e `filesDir/web-tls/certificato.der`. Fuori dal database, protetti dai permessi della cartella privata dell'app |

### Nuove API o endpoint

| Metodo | Path | Descrizione | Auth richiesta |
|---|---|---|---|
| — | — | **Nessun endpoint nuovo.** Cambia il trasporto, non i percorsi: `/`, `/api/eventi` e gli asset restano identici, con lo stesso token e lo stesso `ETag` | — |

### Breaking changes

| Componente | Tipo di breaking change | Piano di migrazione |
|---|---|---|
| Schema dell'indirizzo (`http://` → `https://`) | Un segnalibro salvato con la 002 smette di funzionare | L'app mostra sempre l'indirizzo corrente, ed è già il posto da cui si copia. Nessun percorso automatico: D-05 rimanda il redirect |
| La porta 9888 non parla più in chiaro | Un client HTTP non riceve un errore leggibile ma spazzatura | Documentato in `CLAUDE.md` e nel messaggio dell'app; è il prezzo dichiarato di non affiancare le due porte |
| `HttpServer` — costruttore | `apriSocket` si inserisce **fra** `scope` e `gestisci`, perché `gestisci` deve restare lambda finale | Due chiamanti, entrambi interni. `HttpServerTest` lo passa **per posizione** e va convertito al passaggio per nome, altrimenti compila e smette di provare quel che dice |
| `WebService` — costruttore | Parametro obbligatorio per la cartella dell'identità TLS | Due chiamanti. Nessun default: un percorso predefinito sbagliato rigenererebbe il certificato a ogni avvio |

---

## 6. Piano di implementazione

Responsabile unico: Alberto Goldoni. Aree: **Core** (logica in `:shared`), **Infra** (verifiche di
piattaforma), **UI** (Compose), **Test**, **Doc**. Ogni task di Core include i propri test unitari.

| ID | Task | Area | Stima (gg) | Dipende da |
|---|---|---|---|---|
| T-01 | **Verifica bloccante V-01:** archivio chiavi in memoria (`KeyStore.getInstance("PKCS12")`, `setKeyEntry` con chiave EC), `KeyManagerFactory`, `SSLContext`, `SSLServerSocket` legato — su emulatore **API 26** e API 34. Se fallisce si adotta il ripiego `X509ExtendedKeyManager` scritto a mano, che salta del tutto l'archivio | Infra | 0,25 | — |
| T-02 | **V-02:** leggere `supportedProtocols` su API 26 e 34; abilitare l'**intersezione** con {TLS 1.2, TLS 1.3} invece di un elenco fisso, che su API 26 farebbe fallire l'apertura | Infra | 0,10 | T-01 |
| T-03 | `Der.kt`: `sequence`, `integer`, `bitString`, `octetString`, `oid`, `utcTime`, `boolean`, tag di contesto, `raw`. `DerTest` sui confini di lunghezza e sui casi con segno | Core | 0,75 | — |
| T-04 | `SelfSignedCertificate.kt`: `TBSCertificate` v3, seriale casuale positivo, `Name` con CN, validità con `notBefore` retrodatato di un giorno, le quattro estensioni, firma `SHA256withECDSA` con **parametri assenti** (non `NULL`). `SelfSignedCertificateTest` | Core | 0,75 | T-03 |
| T-05 | `TlsIdentity.kt`: archivio chiavi in memoria, `KeyManagerFactory`, `SSLContext`, impronta SHA-256 già formattata | Core | 0,25 | T-04, T-01 |
| T-06 | `CertificateStore.kt`: due file DER (D-01), permessi, `caricaOCrea()` **idempotente**. `CertificateStoreTest` — è il presidio di R1 | Core | 0,50 | T-04 |
| T-07 | `HttpServer`: `apriSocket` iniettabile, protocolli impostati sul socket d'ascolto perché gli accettati li ereditino, tenuta del ciclo agli handshake falliti. **Conversione della chiamata posizionale in `HttpServerTest`** | Core | 0,25 | T-05 |
| T-08 | `TlsHandshakeTest`: la prova centrale — client che si fida solo di quel certificato, client che non si fida, client in chiaro contro porta TLS | Test | 0,50 | T-07 |
| T-09 | `WebService`: parametro dell'archivio, identità caricata **dentro** il `runCatching` di `apri()`, impronta nello stato, guasti in italiano in `lastMessage`. `WebServiceTest` aggiornato (D-02) | Core | 0,25 | T-06, T-07 |
| T-10 | `WebStatus`: schema `https` in `url`, campo `impronta`. **Commit isolato, nessuna logica** | Core | 0,10 | — |
| T-11 | `ReminderApp`: cartella `filesDir/web-tls` passata a `WebService` (D-06) | Core | 0,10 | T-09 |
| T-12 | `SezioneWebApp`: riga che anticipa l'avviso, impronta mostrata e copiabile | UI | 0,25 | T-10 |
| T-13 | **Misura di R12:** costo della prima accensione sul thread principale. Chiude D-03 | Test | 0,15 | T-11 |
| T-14 | **Misura di R5:** tempo di apertura della pagina con un handshake per connessione, contro il criterio dei 2 secondi | Test | 0,15 | T-11 |
| T-15 | **V-03:** che cosa fa il browser con un autofirmato scavalcato — `isSecureContext`, `navigator.clipboard`, registrazione di un service worker di prova, offerta di installazione. Decide che cosa scrivere in `CLAUDE.md`, non lo scope | Test | 0,15 | T-11 |
| T-16 | Verifica sul campo: cattura del traffico, avviso una volta sola attraverso un riavvio del telefono, cambio di rete, impronta confrontata, **riesecuzione di TC-16/17/19/20/21 della 002 sopra TLS** | Test | 0,50 | T-12 |
| T-17 | `CLAUDE.md` (area `web/`, la regola del certificato generato una volta, revisione del paragrafo sul secure context), `status.md` della 003, riga di rimando nella 002 | Doc | 0,25 | T-15, T-16 |

**Stima totale:** **5,25 giorni/uomo** — intervallo realistico **4–7**.
**Breakdown:** Core 2,95 · Infra 0,35 · UI 0,25 · Test 1,45 · Doc 0,25

> **Sulla differenza con le fasi precedenti.** La Fase 1 stimava 3,00 e la Fase 2 diceva che
> l'analisi non spostava la stima. Enumerando i task si vede che la sposta, di **+2,25**, e vale la
> pena dire dove: le tre verifiche di piattaforma (T-01, T-02, T-15) e le due misure (T-13, T-14)
> non erano voci della tabella di Fase 1, e la riesecuzione dei casi della 002 sul dispositivo
> (T-16) era sottopesata. Il lavoro di scrittura vero e proprio è rimasto quello previsto. È lo
> stesso scarto che la 002 ha registrato fra Fase 1 e Fase 3 (8,00 → 11,00), e per lo stesso
> motivo: le stime a grana grossa dimenticano la verifica.

**Ordine consigliato.** T-01 apre tutto ed è bloccante, ma T-03 e T-04 non dipendono da lui: se
l'emulatore fa storie, si scrive il codificatore mentre si indaga. T-10 è isolabile e si può
committare per primo.

---

## 7. Piano di test

**Strategia generale.** La logica applicativa non cambia: cambia il livello sotto. Da qui due
scelte. La prima: la prova centrale è **di integrazione**, un handshake TLS vero contro un client
JSSE, e non un confronto fra i byte prodotti e byte attesi — confrontare l'output del proprio
codificatore con byte che ha prodotto lui congela i propri errori invece di trovarli. La seconda: i
test della 002 restano **in chiaro**, iniettando il fornitore di socket predefinito, perché provano
l'interruttore e il ciclo di vita, che sono ortogonali al trasporto (D-02).

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Unit | `Der`: lunghezze ai confini — 127, 128, 255, 256, 65535. Forma breve e forma lunga | Alta |
| TC-02 | Unit | `Der`: `INTEGER` con bit alto acceso prende il byte di segno; OID noti (`1.2.840.10045.4.3.2`, `2.5.29.17`) confrontati con i byte della specifica; `UTCTime` in UTC e non nel fuso locale | Alta |
| TC-03 | Unit | Il certificato si rilegge con `CertificateFactory`; `verify(publicKey)` passa; `checkValidity()` passa | Alta |
| TC-04 | Unit | `getSubjectAlternativeNames()` contiene gli indirizzi dati e `127.0.0.1`; `getBasicConstraints() == -1`; l'uso esteso contiene `serverAuth` | Alta |
| TC-05 | Unit | L'impronta coincide con `MessageDigest("SHA-256")` su `cert.encoded`, nel formato a coppie esadecimali | Media |
| TC-06 | Unit | `caricaOCrea()` su cartella vuota genera chiave e certificato e li scrive | Alta |
| TC-07 | Unit | **Presidio di R1:** una seconda chiamata, e un'istanza nuova sulla stessa cartella, restituiscono lo **stesso** certificato byte per byte | Alta |
| TC-08 | Unit | Cartella non scrivibile → messaggio riportato, non eccezione che sfugge | Media |
| TC-09 | Integration | Giro completo su porta effimera: client `SSLSocket` che si fida **solo** di quel certificato, `GET`, `200` con il corpo atteso | Alta |
| TC-10 | Integration | Un client che non si fida riceve `SSLHandshakeException` **e il server serve ancora** il client successivo | Alta |
| TC-11 | Integration | Un client in chiaro contro la porta TLS non ottiene contenuto e non abbatte il ciclo | Alta |
| TC-12 | Integration | Cento handshake falliti di fila non impediscono il centounesimo riuscito | Media |
| TC-13 | Regressione | I nove test di `HttpServerTest` restano verdi in chiaro col fornitore predefinito | Alta |
| TC-14 | Regressione | I dieci test di `WebServiceTest` (interruttore, custode, sorte del token) restano verdi | Alta |
| TC-15 | Regressione | Suite intera verde: da **188** a ~205 test | Alta |
| TC-16 | Campo | Cattura del traffico durante una sessione completa: né token né titoli in chiaro | Alta |
| TC-17 | Campo | L'avviso compare una volta; dopo un **riavvio del telefono** non ritorna | Alta |
| TC-18 | Campo | Cambio di rete: indirizzo nuovo funzionante, certificato invariato | Alta |
| TC-19 | Campo | L'impronta dell'app coincide con quella dei dettagli del certificato nel browser | Media |
| TC-20 | Campo | Riesecuzione sopra TLS di TC-16/17/19/20/21 della 002 (lista, `304`, rotazione, background, irraggiungibilità) | Alta |
| TC-21 | Campo | Misure: apertura della pagina (R5) e prima accensione (R12) | Media |
| TC-22 | Campo | V-03 su Chromium e Firefox: `isSecureContext`, `navigator.clipboard`, service worker di prova, offerta di installazione | Bassa |
| TC-23 | Dispositivo | V-01 su emulatore API 26 e API 34 — **bloccante, precede tutto il resto** | Alta |

### Definition of Done

- [ ] `./gradlew :shared:desktopTest :desktopApp:test` verde.
- [ ] TC-01…TC-15 automatizzati e passanti.
- [ ] TC-16…TC-23 spuntati a mano, con esito annotato in `status.md`.
- [ ] Nessun ANR e nessun crash nei log durante la sessione di verifica.
- [ ] Tutti i criteri di accettazione di §4 verificati.
- [ ] Nessuna dipendenza nuova nel catalogo delle versioni; APK non apprezzabilmente più grande.
- [ ] `CLAUDE.md` aggiornato, **compresa la revisione del paragrafo sul secure context** sulla base
      di ciò che T-15 ha osservato e non di ciò che si suppone.
- [ ] Rilettura del codice del certificato con occhio all'interoperabilità: parametri assenti per
      ECDSA, `UTCTime` in UTC, lunghezze DER. È il posto dove un errore è silenzioso.

---

## 8. Rischi e mitigazioni

| Rischio | Prob. | Impatto | Mitigazione |
|---|---|---|---|
| **R1 — Certificato rigenerato per sbaglio.** `apri()` è chiamato da `enable()` **e** da `resume()`, e `resume()` arriva da `MainActivity.onStart()`, cioè a ogni rotazione dello schermo. Senza guardia, l'avviso tornerebbe a ogni rotazione. Insidioso perché in prova non si nota: chi verifica accetta l'avviso per riflesso | Media | Alto | Guardia in un punto solo (`CertificateStore.caricaOCrea()`), TC-07 addosso, e sul campo si verifica riavviando il **telefono**, non solo l'app |
| **R13 — L'archivio chiavi in memoria su Android.** Il tipo predefinito di Android è storicamente diverso da quello del JDK, e i test girano solo sul desktop | Media | Alto | T-01 è **bloccante** e precede tutto. Ripiego già individuato: `X509ExtendedKeyManager` scritto a mano, una decina di righe, che salta del tutto l'archivio |
| **R2 — Nessuna difesa dall'attaccante attivo.** Scavalcando l'avviso si accetta *qualunque* certificato: chi si mette in mezzo può presentarne uno suo | Media | Medio | Limite strutturale dello scope, non un difetto: **accettato consapevolmente**. L'impronta mostrata dall'app lo chiude per chi vuole chiuderlo, e il testo dell'app non lascia credere che il lucchetto barrato equivalga al lucchetto |
| **R3 — L'avviso insegna a scavalcare gli avvisi.** Era l'argomento con cui la 002 aveva escluso HTTPS | Alta | Basso | L'app anticipa l'avviso dicendo su quale indirizzo comparirà; l'impronta dà un modo di *verificare* invece di scavalcare alla cieca. Il baratto cambia di segno perché ora in cambio si ottiene la confidenzialità |
| **R5 — Un handshake per ogni connessione.** La 002 ha scelto nessun keep-alive: sei asset più `/api/eventi`, e polling ogni 30 s | Media | Medio | Ripresa di sessione JSSE, attiva di default. Da **misurare** (T-14) contro il criterio dei 2 secondi. Se non bastasse, la risposta è il keep-alive — lavoro noto e circoscritto — non abbassare la sicurezza |
| **R12 — Disco e generazione di chiavi sul thread principale.** `SezioneWebApp` chiama `enable()` dal thread principale; oggi lì dentro c'è già `ServerSocket()`, che è I/O | Media | Basso | Misura prima (T-13), complicazione dopo (D-03). Il precedente della sincronizzazione — `syncNow` e `pair` su `withContext(Dispatchers.IO)` — è lì se serve |
| **R4 — DER scritto a mano.** Due insidie specifiche: parametri **assenti** e non `NULL` per ECDSA; `UTCTime` in UTC | Media | Basso | Declassato dallo scope: nessuno valida, quindi un difetto produce un avviso di forma diversa, non un buco. Il modo vero di sbagliare è produrre qualcosa che la JVM rifiuta di caricare — guasto rumoroso, che TC-09 vede |
| **R9 — Regressione silenziosa della 002.** Nessuna logica cambia, ma il livello sotto sì | Media | Alto | I casi della 002 vanno **rieseguiti**, non dedotti (TC-20), in particolare quelli sul ciclo di vita e sul `304` |
| **R7 — Il segnalibro `http://` vecchio.** Non riceve un errore leggibile ma spazzatura | Alta | Basso | Accettato (D-05). L'indirizzo giusto è sempre sotto gli occhi nell'app, ed è già la riga da cui si copia |
| **R14 — Il paragrafo di `CLAUDE.md` sul secure context diventa ambiguo** | Alta | Basso | T-15 lo risolve guardando invece di supporre, e T-17 riscrive quel che si è visto |
| **R11 — Tentazione di una libreria.** Rischio solo se in corsa si cede e si aggiunge BouncyCastle | Bassa | Medio | Sarebbe un cambio di decisione da discutere, non un dettaglio implementativo: è dichiarato in tre documenti |

---

## 9. Rollout e feature flag

**Strategia di rilascio:**
- [x] **Graduale con feature flag** — il flag esiste già ed è quello della 002.
- [ ] Deploy diretto
- [ ] Canary release

Non c'è un canale di distribuzione da orchestrare: l'APK si installa a mano sui dispositivi
dell'utente. La gradualità sta nel fatto che, dopo l'aggiornamento, **l'app si comporta esattamente
come prima** finché l'interruttore resta spento — e quando lo si accende, l'unica differenza
visibile è l'avviso del browser la prima volta.

**Feature flag:** `webEnabled` in `AppSettings`, **spento di default**, persistito nelle
`SharedPreferences` `"impostazioni"`. Questa feature non ne introduce uno nuovo: TLS non è
un'opzione da poter spegnere, perché poterlo spegnere significherebbe poter tornare al chiaro, che è
proprio ciò che si sta chiudendo.

**Piano di rollback:**
1. **Spegnere l'interruttore** dall'app. La porta si chiude, il token si invalida. Immediato e
   sufficiente nella quasi totalità dei casi.
2. Se il difetto fosse nel percorso TLS stesso: **reinstallare l'APK precedente**. Torna a parlare
   in chiaro e il segnalibro `http://` ricomincia a funzionare. **Non serve toccare il database:**
   lo schema resta alla v5 e non c'è nessuna migrazione da annullare.
3. I due file DER restano sul disco e vengono ignorati dalla versione precedente. Conseguenza
   utile: se in seguito si riaggiorna, il certificato è **lo stesso** e l'eccezione già accettata
   dal browser vale ancora.
4. Se il difetto fosse in T-10 (schema dell'URL) o T-03 (codificatore): sono commit isolati e si
   revocano da soli.

**Ciò che il rollback non può annullare:** un'eccezione di certificato già accettata nel browser di
un altro dispositivo. Resta legata a quel certificato e a quell'origine, e si toglie dalle
impostazioni del browser — non è un residuo pericoloso, ma va saputo.

---

## 10. Checklist di approvazione

Progetto personale a sviluppatore unico: i ruoli coincidono nella stessa persona. La tabella resta
perché i cappelli vanno indossati comunque, e separarli aiuta a non saltarne uno.

| Revisione | Responsabile | Stato | Data |
|---|---|---|---|
| Decisioni D-01…D-06 chiuse | Alberto Goldoni | ✅ Fatto | 2026-08-21 |
| Revisione tecnica (architettura, breaking changes) | Alberto Goldoni | ⏳ In attesa | — |
| Revisione prodotto (l'avviso del browser è accettabile come prezzo) | Alberto Goldoni | ⏳ In attesa | — |
| Stima approvata (5,25 gg, +2,25 sulla Fase 1) | Alberto Goldoni | ⏳ In attesa | — |
| Rischi accettati (in particolare R2, R13 e R3) | Alberto Goldoni | ⏳ In attesa | — |
| Data di inizio confermata | Alberto Goldoni | ⏳ In attesa | — |

---

## Domande residue

Le sei decisioni aperte della Fase 2 sono chiuse (§3). Restano due punti che **non bloccano l'inizio
dei lavori** ma vanno decisi al momento giusto.

1. **Il testo che l'utente legge prima di aprire il browser** (T-12) è l'unica parte non tecnica di
   questa feature, ed è quella che decide se R2 e R3 sono davvero mitigati o solo dichiarati. Troppo
   tecnico spaventa e fa spegnere l'interruttore; troppo poco mente per omissione. Va scritto
   sapendo che dovrà dire tre cose in tre righe: *l'avviso comparirà, è atteso, e se vuoi verificare
   davvero confronta questa impronta*. Meglio deciderlo guardando la schermata, non qui.
2. **Se T-01 fallisse su API 26**, il ripiego (`X509ExtendedKeyManager` a mano) è individuato e
   costa poco, ma cambia T-05 e va accettato prima di iniziare — non scoperto a metà. T-03 e T-04
   non dipendono da lui e si possono scrivere nel frattempo.

---

## Riferimenti

- [phase-1-requirements.md](phase-1-requirements.md) — obiettivo, scope, user stories, stima iniziale
- [phase-2-analysis.md](phase-2-analysis.md) — analisi su codice reale, file coinvolti, pattern, verifiche bloccanti
- [docs/features/002-web-app-android/](../002-web-app-android/) — la feature che questa mette in sicurezza
- [docs/features/001-desktop-linux/](../001-desktop-linux/) — da cui provengono i pattern crittografici (P-256, `jvmSharedMain`)

---

*Documento generato con la skill `claude-code-feature`.*
