# Feature: Traffico cifrato per la web app locale — Requisiti (Fase 1)

**Slug:** `https-web-app`
**Data:** 2026-08-21
**Stato:** Bozza in attesa di approvazione
**Progetto:** Promemoria (`it.agoldoni.reminder`) — Compose Multiplatform, Android + Desktop JVM, Kotlin 2.3.21 / Room KMP / MVVM
**Team:** 1 sviluppatore (Alberto Goldoni)
**Piattaforma interessata:** solo Android, come la 002 (il codice resta in `jvmSharedMain` e provabile su desktop)
**Estende:** [002-web-app-android](../002-web-app-android/status.md), che ha consegnato la porta HTTP

**Decisioni già prese dall'utente (input di questa fase):**

| Decisione | Scelta | Conseguenza principale |
|---|---|---|
| Obiettivo | **Solo confidenzialità del traffico** | Niente altro: né lucchetto pulito, né *secure context*, né PWA |
| Certificato | **Autofirmato, generato una volta e mai riemesso** | Il browser avvisa una volta per client; l'indirizzo che cambia smette di contare |
| CA locale da installare sul client | **Scartata** | Vedi §1: sproporzionata rispetto al bene da proteggere |

> **Nota di percorso.** Una prima stesura di questa fase, datata 21/08/2026, era costruita su una
> **CA locale** generata dall'app e installata sull'archivio di fiducia del client. È stata
> sostituita da questo documento dopo che l'obiettivo è stato ristretto alla sola cifratura. La
> ragione è in §1 e vale la pena tenerla scritta: se un giorno si vorrà il *secure context*, quella
> è la strada, e questo documento dice perché oggi non la si prende.

---

## 1. Obiettivo e motivazione

La feature 002 ha consegnato una porta HTTP sul telefono che serve la lista dei promemoria a un
browser sulla rete locale. Funziona, è in uso, ed è **in chiaro**. Chi è sullo stesso segmento e sa
ascoltare vede passare due cose che non dovrebbe vedere:

1. **Il token**, che viaggia nella query string di ogni richiesta. Chi lo legge una volta ha accesso
   completo alla lista finché l'interruttore resta acceso. È il rischio R2 della 002, che lì era
   stato mitigato (`Referrer-Policy: no-referrer`, token rigenerato a ogni accensione) ma non
   risolto: nessun *policy header* protegge da chi ascolta il filo.
2. **I promemoria stessi** — titoli, date, descrizioni. Sono dati personali, ed è la ragione per cui
   l'interruttore è spento di default.

C'è una contraddizione interna in questo: il modello di sicurezza della 002 poggia su un segreto
condiviso che quel modello fa viaggiare in chiaro. Questa feature la chiude, e non fa nient'altro.

**Chi è l'attaccante realistico.** Uno solo: il **Wi-Fi con password condivisa** — casa con ospiti,
piccolo ufficio, albergo, bed & breakfast. Chi conosce la PSK può decifrare il traffico degli altri
client, ed è un fatto noto di WPA2-Personal, non un attacco esotico. Su WPA3, su una rete aziendale
con credenziali personali, o a casa da soli, non c'è nessuno da cui difendersi. È una minaccia
stretta ma vera, e la sua misura è ciò che determina quanto è giusto pagare per chiuderla.

**Perché non una CA locale.** Sarebbe la strada per avere anche il lucchetto pulito e il *secure
context*, ma chiede di installare sul computer un'ancora di fiducia capace, in mano a chi ne
possiede la chiave privata, di impersonare qualunque sito. Per proteggere una lista di promemoria
in sola lettura è sproporzionato: si accetterebbe un rischio sistemico per chiuderne uno locale. In
più costerebbe il triplo del lavoro, quasi tutto in ciclo di vita — custodia della chiave,
riemissione, rigenerazione, una revoca che non esiste — e lascerebbe sul computer dell'utente
un'autorità fidata che il giorno in cui il telefono viene venduto nessuno si ricorda di rimuovere.

**L'osservazione su cui poggia tutta la feature.** Se l'obiettivo è solo che nessuno legga il filo,
**il certificato è una formalità**: TLS pretende che il server ne presenti uno, ma nessuno lo
validerà — l'utente scavalca l'avviso una volta e si va avanti. Da qui discendono tre conseguenze
che cambiano il lavoro, e sono il motivo per cui questa feature costa tre giorni e non dieci:

- **L'indirizzo che cambia smette di essere un problema.** Non si valida nulla, quindi il
  certificato non deve corrispondere all'indirizzo. Si genera una volta e vale per sempre, su
  qualunque rete. Sparisce l'intera ragione per cui serviva una CA.
- **Il codificatore DER scritto a mano smette di essere codice di sicurezza.** Un difetto lì produce
  «il browser si lamenta in modo diverso», non un buco. Il modo peggiore in cui può andare storto è
  che la JVM si rifiuti di caricare il certificato e il server non parta — un guasto rumoroso, che
  i test vedono.
- **Non c'è nessuna chiave da custodire come un gioiello.** La chiave privata sul telefono serve a
  fingersi quel server, che è esattamente ciò che quel server è.

**Metriche di successo (verifica manuale, nessuna analytics):**

- [ ] Catturando il traffico fra computer e telefono (`tcpdump`/Wireshark) durante una sessione
      completa, il token **non** compare in chiaro in nessun pacchetto, e nemmeno il titolo di un
      promemoria.
- [ ] Il browser mostra l'avviso di certificato **una volta**; proseguendo, la pagina funziona come
      prima in tutto.
- [ ] Riavviando app e telefono, e riaccendendo l'interruttore, l'avviso **non** ritorna: il
      certificato è lo stesso.
- [ ] Cambiando rete al telefono, l'indirizzo nuovo funziona subito senza toccare nulla e senza un
      avviso diverso da quello già accettato.
- [ ] L'impronta SHA-256 mostrata dall'app coincide con quella che il browser mostra nei dettagli
      del certificato.
- [ ] Nessuna regressione: tutti i criteri di accettazione della 002 restano soddisfatti sopra TLS,
      e la dimensione dell'APK non cresce in modo apprezzabile (nessuna libreria nuova).

---

## 2. Scope

### Incluso

**A. Un certificato autofirmato, scritto a mano**
- Codificatore ASN.1/DER minimale e costruzione del `TBSCertificate`: SEQUENCE, INTEGER, BIT STRING,
  OCTET STRING, OID, `UTCTime`, tag di contesto. Nient'altro.
- **Nessuna libreria nuova.** Su Android non c'è `keytool`, non c'è `sun.security.x509`, e
  BouncyCastle costerebbe megabyte nell'APK — lo stesso ragionamento che ha tenuto fuori
  `material-icons-extended` (37 MB per due icone) e le librerie ODS. Il progetto ha già scritto a
  mano HKDF contro i vettori della RFC 5869 e un foglio ODF: questa è la terza volta.
- Ciò che si può non scrivere non si scrive: la `SubjectPublicKeyInfo` arriva già codificata da
  `PublicKey.getEncoded()`, e la firma ECDSA arriva già in DER da `Signature`.
- Chiave **P-256** via JCA, la stessa curva già scelta in [Crypto.kt](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/sync/Crypto.kt)
  e per la stessa ragione: X25519 su Android arriva con l'API 31, il minimo qui è 26.
- Estensioni: `subjectAltName` con gli indirizzi IPv4 locali al momento della generazione più
  `127.0.0.1`, `basicConstraints: CA:FALSE`, `keyUsage`, `extKeyUsage: serverAuth`. **Nessuno le
  verificherà**, ma costano venti righe e fanno un certificato onesto invece di un segnaposto — e
  lasciano aperta la porta a chi un giorno volesse installarlo davvero.
- Validità lunga (ordine dei 10 anni). I tetti dei browser sui certificati brevi valgono per le
  catene pubbliche, non qui. `notBefore` retrodatato di un giorno, contro il disallineamento di
  orologio fra i due dispositivi.

**B. Generato una volta sola — la regola che conta più di tutte**
- Chiave e certificato nascono alla prima accensione di HTTPS e sono **persistiti**, accanto al
  database, protetti dai permessi del file: la stessa scelta già presa e documentata per il
  `sharedSecret` della sincronizzazione.
- **La generazione è idempotente.** Le eccezioni del browser sono per-certificato: rigenerare
  significa rimettere l'utente davanti all'avviso. Questo è il difetto più facile da introdurre e
  più difficile da notare in prova, perché in prova si accetta l'avviso senza pensarci.
- Non si riemette al cambio di indirizzo — non serve, nessuno valida. Non si rigenera allo
  spegnimento dell'interruttore: spegnere chiude la porta e invalida il **token**, non il materiale
  crittografico.

**C. TLS in ascolto**
- `HttpServer` apre un `SSLServerSocket` invece di un `ServerSocket`: archivio chiavi in memoria →
  `KeyManagerFactory` → `SSLContext`. Il socket da aprire diventa un parametro iniettabile, così i
  test possono ancora usare la porta effimera e, dove serve, il testo in chiaro.
- Protocolli: quel che la piattaforma offre fra TLS 1.2 e 1.3, invece di un elenco fisso che su API
  26 farebbe fallire l'apertura. Niente suite deboli.
- Un handshake fallito — cioè quel che succede quando qualcuno parla HTTP a una porta TLS, o quando
  un client rifiuta il certificato — deve chiudere **solo quella connessione**. È la disciplina già
  scritta nel ciclo di `accept()`, ma qui va verificata e non data per scontata.
- **Porta invariata: 9888.** Non cambia nulla del vincolo già documentato rispetto a `SYNC_PORT`
  (47700) e `SingleInstance.DEFAULT_PORT` (47653).
- **HTTP si spegne, non si affianca.** Tenere aperta la porta in chiaro vorrebbe dire che chi
  ascolta aspetta la prima richiesta che passa di lì.
- **Il token resta.** TLS dà confidenzialità, non controllo dell'accesso: senza token chiunque sulla
  rete leggerebbe i promemoria. Quel che cambia è che ora il token non è più intercettabile.

**D. Impronta confrontabile**
- L'app mostra l'impronta **SHA-256** del certificato nel formato in cui la mostrano i browser
  (coppie esadecimali separate da due punti).
- Serve a chiudere l'unico buco che resta (§5 R2): scavalcando l'avviso si ottiene cifratura ma
  nessuna autenticazione del server, e un attaccante **attivo** potrebbe mettersi in mezzo.
  Confrontare l'impronta una volta porta da «cifrato» a «cifrato e autenticato». È lo stesso
  argomento del **confronto a vista** già adottato da `Pairing`: un canale che non si può
  autenticare si mette in sicurezza con un confronto breve fatto dall'essere umano.

**E. Interfaccia**
- La sezione «Web app» della schermata Sincronizzazione mostra l'indirizzo con schema `https://`.
- Una riga che **anticipa l'avviso** del browser dicendo che è atteso, e come si scavalca. Senza,
  l'utente pensa che qualcosa si sia rotto.

**F. Test e documentazione**
- Test in `jvmSharedTest`, che girano su desktop: la prova centrale è un **handshake vero** (§5 R4).
- `CLAUDE.md`: la nuova area, e la correzione dei punti della 002 che questa feature supera.

### Escluso (out of scope)

| Fuori scope | Motivo |
|---|---|
| **CA locale, installazione sul client, assenza di avvisi** | L'obiettivo è la sola cifratura. Vedi §1: sproporzionata, e triplicherebbe il lavoro |
| **Secure context, service worker, cache offline, PWA installabile, `navigator.clipboard`** | Restano dietro a un certificato *fidato*, che qui non c'è. Il paragrafo «Escluso» della 002 su questi punti resta valido tale e quale |
| **Riemissione del certificato al cambio di indirizzo** | Inutile per costruzione: nessuno valida il SAN. È il vantaggio principale di questo scope, non una rinuncia |
| **Revoca (CRL, OCSP)** | Non ha senso senza validazione, e nessun client la interrogherebbe |
| **Nome stabile via mDNS (`.local`)** | Serviva a far sopravvivere un certificato *validato* al DHCP. Senza validazione non serve |
| **Doppio ascolto HTTP + HTTPS** | Conserverebbe esattamente la debolezza che questa feature esiste per chiudere |
| **HSTS** | Non si applica agli indirizzi IP: sarebbe un header che nessun browser onora |
| **mTLS (certificato lato client)** | Sostituirebbe il token con qualcosa di più forte al prezzo di installare un certificato personale su ogni browser. Sproporzionato; annotato in §8 |
| **Scrittura dal browser** | Restava rimandata dalla 002 e resta rimandata: TLS non cambia l'argomento |
| **Attivazione su desktop** | Come nella 002: il codice resta riusabile, ma nessun interruttore desktop |
| **Codice QR per l'impronta** | Nessun encoder QR in progetto; già rimandato dalla 002 |

---

## 3. User Stories

**US-1 — Non farsi leggere da chi ascolta la rete**
> Come utente che consulta i promemoria dal PC su un Wi-Fi con la password condivisa, voglio che il
> traffico sia cifrato, per non regalare il token — e la lista — a chi conosce quella password.

**US-2 — L'avviso una volta, non a ogni avvio**
> Come utente voglio che l'avviso del browser, una volta accettato, non ritorni, per non
> ricominciare da capo ogni volta che riaccendo il telefono.

**US-3 — Cambiare rete senza pensarci**
> Come utente che passa da casa all'ufficio all'hotspot, voglio che continui a funzionare senza
> rifare niente, perché l'indirizzo del telefono cambia e non è colpa mia.

**US-4 — Sapere che l'avviso è atteso**
> Come utente voglio che sia l'app a dirmi che il browser protesterà e perché, per non pensare che
> si sia rotto qualcosa — e voglio un'impronta da confrontare, se ci tengo a verificare davvero.

**US-5 — Non perdere quello che già funzionava**
> Come utente voglio che tutto ciò che la web app già faceva continui a farlo, per non pagare la
> cifratura con una regressione.

---

## 4. Criteri di accettazione

### US-1 — Non farsi leggere da chi ascolta la rete
- [ ] Il server accetta **solo** connessioni TLS: un `GET` in chiaro sulla porta 9888 non
      restituisce contenuto.
- [ ] Con una cattura del traffico durante una sessione completa (apertura, aggiornamenti, asset),
      il token non compare in chiaro in nessun pacchetto, e nemmeno il titolo di un promemoria.
- [ ] I protocolli negoziati sono TLS 1.2 o 1.3; una richiesta che offre solo protocolli più vecchi
      viene rifiutata.
- [ ] Un handshake fallito non abbatte il ciclo di ascolto: le connessioni successive funzionano.
- [ ] Il token continua a essere richiesto e verificato come prima: TLS non lo sostituisce.
- [ ] La porta HTTP in chiaro non esiste più: non c'è nessun percorso che serva contenuto senza TLS.

### US-2 — L'avviso una volta, non a ogni avvio
- [ ] Chiave e certificato sopravvivono alla chiusura dell'app e al riavvio del telefono.
- [ ] Due accensioni consecutive dell'interruttore presentano **lo stesso** certificato, byte per
      byte.
- [ ] Spegnere e riaccendere l'interruttore invalida il token ma **non** tocca il certificato.
- [ ] Aggiornare l'app senza disinstallarla non rigenera il certificato.
- [ ] In Firefox, accettata l'eccezione una volta, non viene più richiesta.

### US-3 — Cambiare rete senza pensarci
- [ ] Cambiando rete al telefono, l'app mostra un indirizzo nuovo che funziona subito.
- [ ] Il certificato **non** viene riemesso per il cambio di indirizzo.
- [ ] Il browser, che aveva già accettato quel certificato, non ripropone l'avviso sul nuovo
      indirizzo — o, se lo ripropone perché l'eccezione è per-origine, lo fa una volta sola per
      indirizzo. *(Da verificare sul campo: è il comportamento del browser, non nostro.)*
- [ ] L'accesso via `adb forward` su `127.0.0.1` funziona con lo stesso certificato, così la prova e
      l'uso reale non divergono.

### US-4 — Sapere che l'avviso è atteso
- [ ] L'app dice, prima che l'utente apra il browser, che comparirà un avviso di certificato e che è
      atteso.
- [ ] L'app mostra l'impronta SHA-256 in formato leggibile e confrontabile.
- [ ] L'impronta mostrata coincide con quella che il browser mostra nei dettagli del certificato.
- [ ] Il testo non minimizza: dice che scavalcando l'avviso si ottiene cifratura ma non
      autenticazione, e che confrontare l'impronta serve a quello.

### US-5 — Non perdere quello che già funzionava
- [ ] Tutti i criteri di §4 della 002 restano soddisfatti, sopra TLS.
- [ ] L'aggiornamento condizionale (`ETag` / `304`) funziona come prima.
- [ ] La porta resta aperta ad app chiusa, con il servizio in primo piano, come prima.
- [ ] Il tempo di apertura della pagina resta sotto i 2 secondi nonostante l'handshake per
      connessione (§5 R5).
- [ ] Nessuna dipendenza nuova, nessun permesso Android nuovo, nessuna crescita apprezzabile
      dell'APK.
- [ ] La suite completa resta verde e sincronizzazione, allarmi ed export non sono toccati.

---

## 5. Rischi e dipendenze

### Rischi tecnici

**R1 — Il certificato viene rigenerato per sbaglio** · *Medio — è il difetto più probabile*
Le eccezioni del browser sono legate al certificato: basta un percorso di codice che rigeneri
all'accensione, all'aggiornamento dell'app o al cambio di rete, e l'utente si ritrova davanti
all'avviso senza capire perché. È insidioso perché **in prova non si nota**: chi verifica accetta
l'avviso per riflesso. *Mitigazione:* la generazione è idempotente per costruzione (si genera solo
se non esiste già), e c'è un test dedicato che confronta i byte del certificato attraverso due cicli
di avvio; sul campo si verifica riavviando il telefono, non solo l'app.

**R2 — Nessuna difesa dall'attaccante attivo** · *Medio, accettato consapevolmente*
Scavalcando l'avviso si accetta *qualunque* certificato: chi si mette in mezzo con ARP spoofing può
presentarne uno suo e leggere tutto. È il limite strutturale di questo scope, non un difetto.
*Mitigazione:* l'impronta SHA-256 mostrata dall'app e confrontata una volta chiude il buco per chi
vuole chiuderlo. Il documento e il testo nell'app lo dicono invece di lasciar credere che il
lucchetto barrato equivalga al lucchetto.

**R3 — L'avviso insegna a scavalcare gli avvisi** · *Medio, non tecnico*
Era l'argomento con cui la 002 aveva escluso HTTPS, ed era buono finché in cambio non si otteneva
nulla. Ora in cambio si ottiene la confidenzialità, e il baratto cambia di segno — ma il costo
resta: si abitua l'utente a un gesto che altrove è pericoloso. *Mitigazione:* l'app dice prima che
l'avviso è atteso e su quale indirizzo; l'impronta dà un modo di *verificare* invece di scavalcare
alla cieca.

**R4 — DER scritto a mano** · *Basso — declassato dallo scope*
DER non perdona, ma qui nessuno valida: un difetto produce un avviso di forma diversa, non un buco.
Il vero modo di sbagliare è produrre qualcosa che la JVM **rifiuta di caricare** nell'archivio
chiavi, e allora il server non parte. *Mitigazione:* la prova non è mai un confronto con byte attesi
— si finirebbe per congelare i propri errori — ma un **handshake vero** in `jvmSharedTest`: server
TLS su porta effimera, client `SSLSocket`, richiesta HTTP completa, risposta attesa. Accanto,
rilettura con `CertificateFactory` e controllo delle estensioni.

**R5 — Un handshake per ogni connessione** · *Medio*
La 002 ha scelto **nessun keep-alive**: una richiesta per connessione. Sopra TLS quella scelta si
paga con un handshake per ogni asset e per ogni giro di aggiornamento. *Mitigazione:* la ripresa di
sessione TLS è attiva di default in JSSE e riduce gli handshake successivi; se non bastasse, la
risposta giusta è introdurre il keep-alive, non abbassare la sicurezza. Da **misurare**, non da
presumere: il criterio è il tempo di apertura sotto i 2 secondi già fissato dalla 002.

**R6 — Custodia della chiave privata** · *Basso*
Compromette la capacità di fingersi questo server sulla rete locale, e nient'altro: non è il caso
della CA, dove sarebbe stato un problema di ordine diverso. *Mitigazione:* file accanto al database
protetto dai permessi, come già deciso per il `sharedSecret`. L'`AndroidKeyStore` resta un'opzione
da pesare in Fase 2, sapendo che comporta un'astrazione di piattaforma perché `jvmSharedMain` non
lo vede.

**R7 — Il segnalibro vecchio smette di funzionare** · *Basso*
Chi ha salvato l'URL `http://…` della 002 non riceve una pagina d'errore comprensibile: un client in
chiaro contro una porta TLS riceve spazzatura. *Mitigazione:* l'indirizzo giusto è sempre sotto gli
occhi nell'app; da valutare in Fase 2 se riconoscere il primo byte della connessione (`0x16` =
handshake TLS, una lettera ASCII = HTTP in chiaro) e rispondere con un `301` verso `https://` — poco
codice, ma pur sempre parsing in più su una porta esposta.

**R8 — TLS 1.3 non c'è su tutte le versioni supportate** · *Basso*
Il minimo è l'API 26; TLS 1.3 arriva con la 29. *Mitigazione:* si abilita l'intersezione fra quel che
la piattaforma offre e {1.2, 1.3}, invece di pretendere un elenco fisso.

**R9 — Regressione silenziosa della 002** · *Medio*
Tutta la verifica sul campo della 002 è stata fatta in chiaro, e TLS tocca il livello sotto ogni cosa
già verificata. *Mitigazione:* i casi di prova della 002 vanno **rieseguiti**, non dedotti — in
particolare quelli sul ciclo di vita (background, Doze, servizio in primo piano) e sul `304`.

**R10 — Orologio disallineato** · *Molto basso*
Un certificato ha una finestra di validità, ma nessuno la controlla: al più cambia il testo
dell'avviso. *Mitigazione:* `notBefore` retrodatato di un giorno e validità lunga; non serve altro.

**R11 — Crescita dell'APK e tentazione di una libreria** · *Nullo per costruzione*
Come nella 002: è un rischio solo se in corsa si cede e si aggiunge BouncyCastle. Sarebbe un cambio
di decisione da discutere, non un dettaglio implementativo.

### Dipendenze

- **Nessuna dipendenza esterna nuova.** `javax.net.ssl`, `java.security` e `javax.crypto` sono su
  entrambi i target — la stessa constatazione che ha permesso di tenere in `jvmSharedMain` la
  crittografia della sincronizzazione.
- **Nessun permesso Android nuovo:** `INTERNET` è già dichiarato.
- **Dipendenza da codice esistente:** `HttpServer`, `WebService`, `AccessToken`, `siteAddress()`,
  `AppSettings`, e i pattern di `Crypto.kt`.
- **Dipendenza da 002:** questa feature non ha senso da sola.

---

## 6. Stima effort

Unità: giorni/uomo per uno sviluppatore che conosce la codebase.

| Area | Attività | Stima |
|---|---|---|
| **BE — certificato** | Codificatore ASN.1/DER minimale, `TBSCertificate`, estensioni (`subjectAltName` con IP, `basicConstraints`, `keyUsage`, `extKeyUsage`), firma, serializzazione, impronta SHA-256 | 1,00 |
| **BE — persistenza** | Generazione idempotente, scrittura e rilettura di chiave e certificato accanto al database, permessi | 0,50 |
| **BE — TLS** | Archivio chiavi in memoria, `SSLContext`, socket iniettabile in `HttpServer`, protocolli, tenuta del ciclo agli handshake falliti, spegnimento di HTTP | 0,50 |
| **FE / UI** | Schema `https` nell'indirizzo, riga che anticipa l'avviso, impronta mostrata e copiabile | 0,25 |
| **Test** | Handshake reale su porta effimera, rilettura con `CertificateFactory`, idempotenza attraverso due cicli di avvio, handshake fallito che non abbatte il ciclo | 0,50 |
| **Documentazione** | `CLAUDE.md`, documenti di feature, correzione dei punti della 002 superati, `status.md` | 0,25 |
| | **Totale** | **3,00** |

**Intervallo realistico: 2–4 giorni/uomo.** La voce più esposta a slittamento è il codificatore DER,
ma solo in avvio: qui non deve essere accettato da *ogni* verificatore, deve solo essere caricabile
e presentabile, e il test di handshake dà un segnale netto in pochi minuti.

Riferimento di taratura: la 002 è stata stimata 8,0 ed è stata rispettata. Questa è un'aggiunta a
quella, concentrata in tre file.

---

## 7. Milestones

**M1 — Un certificato che la JVM accetta**
Codificatore DER, `TBSCertificate`, autofirma, estensioni, archivio chiavi in memoria.
*Fatto quando:* `CertificateFactory` lo rilegge, `verify()` con la propria pubblica passa,
`KeyManagerFactory` lo carica senza lamentarsi, e `openssl x509 -text` lo stampa per intero.

**M2 — TLS in ascolto**
`SSLServerSocket` in `HttpServer`, protocolli, tenuta del ciclo agli handshake falliti, HTTP spento.
*Fatto quando:* la suite della 002 gira intera sopra TLS e resta verde, e cento handshake falliti di
fila non impediscono il centounesimo riuscito.

**M3 — Generato una volta sola**
Persistenza di chiave e certificato, generazione idempotente, indipendenza da interruttore e rete.
*Fatto quando:* due cicli di avvio dell'app presentano lo stesso certificato byte per byte, e
spegnere e riaccendere l'interruttore invalida il token senza toccare il resto.

**M4 — Interfaccia**
Indirizzo `https://`, avviso anticipato, impronta.
*Fatto quando:* un utente che non ha scritto questo codice apre la pagina senza pensare che si sia
rotto qualcosa, e sa dove guardare per confrontare l'impronta.

**M5 — Verifica sul campo e chiusura**
Prova reale telefono ↔ computer sulla stessa rete: cattura del traffico pulita, avviso una volta
sola, cambio di rete, casi della 002 riverificati.
*Fatto quando:* tutti i criteri di §4 sono spuntati su dispositivo reale, `CLAUDE.md` è aggiornato,
e i punti della 002 che questa feature supera non contraddicono più la realtà.

---

## 8. Note per le fasi successive

Decisioni da chiudere in Fase 2:

- **Dove stanno chiave e certificato** — file accanto al database (come il `sharedSecret`) oppure
  `AndroidKeyStore`; nel secondo caso, dove passa il confine di piattaforma.
- **Formato di persistenza** — PKCS#12 in un file solo, oppure chiave e certificato separati. Pesa
  la semplicità contro il fatto che PKCS#12 vuole una password che qui non protegge nulla.
- **`subjectAltName`: gli indirizzi correnti alla generazione, oppure nessuno.** Nessuno lo
  verificherà; includerli costa poco e lascia la porta aperta a un'installazione futura.
- **Un'azione «Rigenera»** — utile se si sospetta che la chiave sia uscita, ma rimette l'utente
  davanti all'avviso su ogni client. Se entra, deve dirlo prima.
- **Redirect da HTTP a HTTPS riconoscendo il primo byte** — sì o no, sapendo che è parsing in più su
  una porta esposta (R7).
- **Dove va l'impronta nell'interfaccia** — sempre visibile o dietro un dettaglio da aprire.

Idee esplicitamente rimandate a feature future:

- **CA locale** e con essa *secure context*, lucchetto pulito, service worker, cache offline e PWA
  installabile. La prima stesura di questa fase la descriveva per intero: se l'obiettivo cambierà,
  si riparte da lì e non da zero.
- **mTLS**: certificato personale sul browser al posto del token. Più forte e molto più scomodo.
- **Keep-alive** nel server HTTP, se la misura di R5 dicesse che serve.
- **Togliere il token dalla query string** al primo accesso riuscito (cookie e `history.replaceState`):
  sotto TLS non è più intercettabile, ma continua a finire nella cronologia del browser.
- **HTTPS anche sul desktop**, se e quando la web app verrà cablata lì.
