# L'indirizzo diventa fisso — Implementation Plan

**Stato:** Bozza — in attesa di approvazione
**Autore:** Alberto Goldoni
**Data:** 2026-08-23
**Versione:** 1.2 — *v1.0 → v1.1: D-06 confermata, D-10 rivista (chiave corrotta: si cancella e la
ricrea il riavvio). v1.1 → v1.2 il 2026-08-23, a implementazione finita: D-16 aggiunta, emersa
dalla prova nel browser (T-17) e non dal ragionamento.*
**Feature:** `006-indirizzo-fisso-web-app`
**Fonti:** [phase-1-requirements.md](phase-1-requirements.md) · [phase-2-analysis.md](phase-2-analysis.md)

---

## 1. Executive Summary

La pagina web dei promemoria, quella che si apre dal browser sulla rete di casa, oggi si raggiunge a
un indirizzo che **contiene la chiave**: otto caratteri in coda all'URL. Funziona, ma quella chiave
muore ogni volta che l'app si riavvia — e allora il segnalibro sul tablet in cucina si rompe da
solo, senza che nessuno abbia fatto niente.

Questa feature separa **dove si va** da **che cosa si è autorizzati a fare**. L'indirizzo diventa
`https://IP:9888/` e basta; il permesso vive in un token firmato che il browser riceve una volta
sola, conserva e presenta a ogni richiesta. Da lì discendono tre cose che l'utente vede: il
segnalibro non si rompe più, il codice sparisce dalla barra dell'indirizzo, e compare finalmente un
comando che **toglie** un accesso già dato senza spegnere la porta a tutti.

**Stima: 6,45 giorni/uomo.** Nessuna dipendenza nuova, nessuna migrazione del database, nessun
cambio del formato dei dati. Una rottura dichiarata — gli indirizzi `?t=` smettono di funzionare —
che non ha nessun client vivo da rompere.

---

## 2. Obiettivo e motivazione

### Problema che risolve

Tre difetti che hanno la stessa radice: **l'indirizzo è la credenziale**.

1. **Il segnalibro si rompe.** `WebService.apri()` conia una coppia di token nuova quando non ne
   trova una in memoria, e la memoria non sopravvive al processo. Basta che Android uccida l'app —
   cosa che fa — perché ogni browser a cui l'indirizzo era stato dato smetta di funzionare. Il testo
   della schermata oggi lo ammette: «Il codice cambia ogni volta che riaccendi».
2. **Il codice è in bella vista.** Sta nella barra dell'indirizzo, nella cronologia, e sarebbe
   finito nell'header `Referer` se la 002 non avesse speso una difesa apposta.
3. **Non si può togliere un accesso.** L'unico gesto disponibile è spegnere l'interruttore, che
   chiude la porta a tutti, sé stessi compresi.

Finché la credenziale sta nell'URL, o l'URL è stabile e la credenziale non scade mai, o la
credenziale ruota e l'URL si rompe. Non se ne esce restandoci dentro.

### Metriche di successo

- [ ] **M1** — Un segnalibro sopravvive a `adb shell am force-stop` più riapertura dell'app: oggi
      **no**, dopo **sì**. È la metrica che riassume la feature.
- [ ] **M2** — Un segnalibro sopravvive a un ciclo spegni/riaccendi dell'interruttore.
- [ ] **M3** — Il token non compare in **nessuna** riga di richiesta HTTP: zero occorrenze in
      `path` e `query` su tutte le richieste di una sessione completa.
- [ ] **M4** — Dalla revoca al primo `401` passa meno di una richiesta: il token successivo alla
      rotazione della chiave è già rifiutato.
- [ ] **M5** — Zero casi, nella batteria avversariale di `JwtTest`, in cui il verificatore lancia
      un'eccezione invece di rispondere «non valido».

### Legame con gli obiettivi del prodotto

La 005 ha appena reso la pagina qualcosa che **si lascia aperta**: non interroga più a intervalli,
aspetta, e una modifica compare in un decimo di secondo. Una pagina fatta per restare aperta e un
indirizzo che si rompe a ogni riavvio del processo sono due cose che si contraddicono. Questa
feature toglie la contraddizione.

---

## 3. Scope

### Incluso

**Telefono**

1. Emissione di JWT HS256 con `p` (permessi), `iat`, `exp` a 30 giorni e `v` (versione di formato).
2. Chiave di firma da 32 byte persistita in `filesDir/web-tls/firma.key`, che nasce una volta sola.
3. Verifica da `Authorization: Bearer`, con la regola *firma prima, payload poi, header mai*.
4. `401` per «non autenticato», `403` per «autenticato ma di sola lettura».
5. `/` servita senza credenziali; `/api/eventi` protetta come e più di prima.
6. Comando **«Revoca gli accessi»**, con dialogo di conferma, che ruota la chiave di firma.
7. `disable()` non invalida più niente.
8. Gli indirizzi mostrati diventano `https://host:porta/#access=<jwt>`, coniati freschi a ogni
   apertura della porta, con la data di scadenza accanto.

**Browser**

9. Raccolta del token dal frammento, conservazione in `localStorage`, pulizia della barra
   dell'indirizzo.
10. Token come header su ogni richiesta, letture e scritture, attesa lunga compresa.
11. Stato «nessun token»: messaggio, **e nessun giro di rete**.
12. Stato «token non più valido»: token cancellato, messaggio, **ciclo fermato**.

### Escluso (out of scope)

| Fuori | Perché |
|---|---|
| Rinnovo silenzioso a scorrimento | Trasformerebbe «30 giorni» in «30 giorni di inattività». Decisione esplicita: la scadenza è secca (D-02) |
| Un token per dispositivo, con etichetta e revoca singola | Vuole una lista di token emessi e una schermata che li elenchi. `v` nel payload le lascia la porta aperta |
| Chiavi asimmetriche | Emittente e verificatore sono lo stesso processo sullo stesso telefono |
| Una libreria JWT | Le primitive ci sono già in `sync/Crypto.kt`; una dipendenza sull'APK per centocinquanta righe non si giustifica |
| Compatibilità su `?t=` | Nessun client vivo da non rompere (§5.8) |
| Cookie invece di `localStorage` | Un cookie viaggia da solo e riapre il CSRF che l'header chiude gratis |
| `START_STICKY` per `WebServerService` | Diventa **possibile** grazie a questa feature (R-12) ma vuole che sia il servizio ad aprire la porta. Seguito naturale, non questa feature |
| Nome mDNS al posto dell'IP | «Fisso» resta vero solo finché l'IP è fisso (R-06). Altra feature |
| Installazione come PWA | La blocca il certificato non fidato, che questa feature non tocca |
| Cancellazione dal browser · Desktop | Esclusi come nelle 004 e 005 |

### Decisioni

| # | Decisione | Scelta | Da | Perché |
|---|---|---|---|---|
| **D-01** | Formato del segreto | **JWT HS256** | Utente | Scadenza dentro al token; spazio per claim futuri. Costo dichiarato: un verificatore scritto a mano (R-01) |
| **D-02** | Durata | **30 giorni secchi** | Utente | Una credenziale con una fine. Il rinnovo mensile è il prezzo accettato |
| **D-03** | Revoca | **Globale** | Utente | Rotazione della chiave. Niente liste di `jti` negati, niente stato per token |
| **D-04** | Dove sta la chiave | **`filesDir/web-tls/firma.key`** | Utente | È già la cartella dei segreti del server web. Un secondo posto sarebbe un posto in più da ricordarsi di cancellare |
| **D-05** | Payload | **`v`, `p`, `iat`, `exp`. Niente `jti`** | Utente | La revoca è globale: un identificatore che nessuno legge sarebbe peso morto in ogni indirizzo consegnato |
| **D-06** | Nome del parametro | **`#access=`** | Utente | Confermato il 2026-08-23. L'indirizzo consegnato è `https://IP:9888/#access=<jwt>` |
| **D-07** | Conferma della revoca | **Dialogo** | Utente | È il primo `AlertDialog` della schermata, e va bene così: è l'unica azione irreversibile che la sezione contenga |
| **D-08** | Revoca a interruttore spento | **Disponibile e mostrata** | Utente | Chi spegne credendo di revocare deve trovare lì il gesto vero. Ruotare la chiave a porta chiusa toglie l'accesso per quando si riaprirà |
| **D-09** | Chiave non scrivibile su disco | **Non si apre la porta** | Utente | Una chiave solo in memoria sarebbe il difetto di oggi travestito da funzionamento: l'utente crederebbe di avere un indirizzo stabile e non l'avrebbe |
| **D-10** | Chiave presente ma corrotta | **Si cancella, la porta non si apre, si chiede di riavviare l'app — che la ricrea** | Utente | Ricrearla è una **revoca di massa**, e una revoca di massa non deve avvenire in silenzio: per il certificato rigenerare costa un avviso del browser, qui costa la cacciata di tutti. Il riavvio è il gesto minimo che la rende visibile. Cancellando il file il caso non può ripetersi: al riavvio non c'è più niente di corrotto da leggere |
| **D-11** | `tokenRichiesto` in `StaticAssets` | **Resta** | Analisi | Non è il controllo: è ciò che obbliga chi aggiunge una risorsa a decidere chi può leggerla |
| **D-12** | Nomi dei campi in `WebStatus` | **Restano `tokenLettura`/`tokenScrittura`** | Analisi | Contengono ancora un token. Rinominarli muoverebbe dieci righe di test senza cambiare niente |
| **D-13** | La bandiera del ciclo fermo in `app.js` | **Nuova, non `fermato`** | Analisi | `fermato` è di `visibilitychange`: riusarla farebbe ripartire il ciclo a ogni riscoperta della scheda (R-08) |
| **D-14** | `START_NOT_STICKY` | **Resta, cambia la motivazione** | Analisi | Il commento attuale dice il falso dopo questa feature; la scelta resta giusta per un'altra ragione (R-12) |
| **D-16** | Un permesso che arriva a pagina già aperta | **Si raccoglie con `hashchange`** | Verifica | **Trovata dalla prova manuale (T-17), non dal ragionamento.** Incollare `…/#access=…` in una scheda già su `…/` è una navigazione *nello stesso documento*: il browser non ricarica e lo script non riparte. Senza, la pagina resta con il permesso di prima mentre la barra mostra quello nuovo — e il difetto non si vede. Adottando il token si azzera l'`ETag` e si **interrompe** la richiesta in corso, o un `401` in volo cancellerebbe il permesso appena arrivato |
| **D-15** | Se `localStorage` non è disponibile | **Il frammento si lascia dov'è** | Piano | Ripulire l'URL su un browser che non sa conservare niente ucciderebbe la pagina al primo ricaricamento. Lasciandolo, quel browser degrada esattamente al comportamento di oggi — con in più il fatto che il token non passa comunque dal filo |

---

## 4. User Stories e criteri di accettazione

### US-001 · L'indirizzo si mette fra i segnalibri
**Priorità:** Must Have

Come utente con un tablet fisso in cucina, voglio salvare l'indirizzo una volta sola e ritrovarlo
funzionante domani, per non dover riprendere il telefono ogni volta che l'app si è riavviata.

- [ ] Aperto l'indirizzo con il frammento, la barra mostra `https://IP:9888/` — senza `#`, senza `?`.
- [ ] Ricaricando `https://IP:9888/` a mano la lista compare.
- [ ] Chiuso il browser e riaperto il segnalibro, la lista compare.
- [ ] Dopo `adb shell am force-stop` e riapertura dell'app, il segnalibro funziona ancora **(M1)**.

### US-002 · Spegnere e riaccendere non rompe niente
**Priorità:** Must Have

Come utente che spegne la porta la notte e la riaccende la mattina, voglio che i browser a cui ho già
dato l'indirizzo continuino a funzionare, perché l'interruttore chiude la porta, non cambia le
serrature.

- [ ] `disable()` seguito da `enable()`: il token consegnato prima continua a valere **(M2)**.
- [ ] Un test lo dichiara a parole, non solo di fatto.

### US-003 · Posso togliere un accesso che ho dato
**Priorità:** Must Have

Come utente che ha passato l'indirizzo di sola lettura a qualcuno e se n'è pentito, voglio un comando
che lo invalidi, per non dover scegliere fra tenerlo e spegnere la web app anche a me.

- [ ] Il comando esiste nella schermata e chiede conferma con un dialogo (D-07).
- [ ] È raggiungibile anche a interruttore spento (D-08).
- [ ] Dopo la revoca ogni token consegnato prima riceve `401` **(M4)**.
- [ ] Dopo la revoca la schermata mostra **subito** gli indirizzi nuovi.
- [ ] Il testo della schermata dice che spegnere **non** revoca.

### US-004 · Il codice non è più in bella vista
**Priorità:** Must Have

Come utente che apre la pagina davanti ad altre persone, voglio che nella barra dell'indirizzo non ci
sia niente da rubare.

- [ ] Il token non compare in nessuna riga di richiesta HTTP **(M3)**.
- [ ] `?t=<qualunque cosa>` non autentica più: `401`.

### US-005 · Quando l'accesso non vale più, lo capisco
**Priorità:** Must Have

Come utente che apre un segnalibro vecchio di due mesi, voglio leggere che l'indirizzo è scaduto e va
riaperto dal telefono, invece di vedere una pagina vuota.

- [ ] Con un token scaduto o revocato compare un messaggio in italiano che rimanda al telefono.
- [ ] La pagina **smette di chiedere**: nessuna richiesta dopo il `401` (D-13).
- [ ] Il token conservato viene cancellato: un ricaricamento non ripete lo stesso errore.
- [ ] La schermata dell'app dichiara fino a quando vale l'indirizzo che sta mostrando.

### US-006 · Chi guarda e chi comanda restano due
**Priorità:** Must Have

Come utente che presta la pagina a qualcun altro, voglio continuare ad avere due indirizzi distinti.

- [ ] L'indirizzo di sola lettura non mostra i comandi e riceve `403` su una scrittura.
- [ ] `403` e `401` restano due risposte diverse, e la pagina dice due cose diverse.
- [ ] L'indirizzo che comanda resta dietro un tocco in più, come oggi.

### US-007 · Un token contraffatto non entra
**Priorità:** Must Have

Come sviluppatore, voglio che il verificatore controlli la firma **prima** di guardare qualunque cosa
ci sia scritta nel token.

- [ ] La firma si ricalcola sui byte grezzi dei primi due segmenti e si confronta a tempo costante.
- [ ] L'header del token non viene **mai** letto.
- [ ] `{"alg":"none"}` con firma vuota è rifiutato.
- [ ] I tredici casi avversariali di TC-02 danno tutti «non valido» e **nessuno lancia (M5)**.
- [ ] La soglia dei dieci tentativi al minuto per indirizzo IP resta in piedi.
- [ ] Un token valido ma insufficiente non consuma tentativi.

---

## 5. Architettura tecnica

### 5.1 — Il flusso, prima e dopo

```
PRIMA — la credenziale è l'indirizzo

  app          mostra  https://IP:9888/?t=k7mq3wzp
  browser      GET /?t=k7mq3wzp                    ← il segreto sul filo, in ogni riga
               GET /api/eventi?t=k7mq3wzp
  riavvio      token nuovo → ogni indirizzo consegnato è morto

DOPO — la credenziale è un token, l'indirizzo è un indirizzo

  app          mostra  https://IP:9888/#access=eyJ…      ← consegna, una volta sola
                                        └────────┘
                                        mai spedito al server
  browser      GET /                                     ← guscio, nessuna credenziale
               (JS legge il frammento, lo salva, ripulisce la barra)
               GET /api/eventi          Authorization: Bearer eyJ…
               PUT /api/eventi/12       Authorization: Bearer eyJ…
  riavvio      la chiave di firma è su disco → il token consegnato vale ancora
  revoca       chiave nuova → tutti i token consegnati muoiono insieme
```

### 5.2 — Componenti coinvolti

```
  ChiaveFirma  ──32 byte──►  Jwt.firma / Jwt.verifica
   (filesDir/                      ▲            ▲
    web-tls/)                      │            │
                            AccessToken.conia   AccessToken.verifica
                                   │                    │
                             WebService ──────────►  Router  ◄── Authorization: Bearer
                                   │                    │
                              WebStatus            401 / 403 / 200
                              (#access=…)
```

### 5.3 — Il token

```
header   {"alg":"HS256","typ":"JWT"}
payload  {"v":1,"p":"scrittura","iat":1755900000,"exp":1758492000}
firma    HMAC-SHA256(chiave, ascii(header64) + "." + ascii(payload64))
```

- `p` usa **gli stessi due letterali** di `PermessiWeb` (`"lettura"` / `"scrittura"`, dai
  `@SerialName` in `WebPayload.kt`): il valore che entra dal token e quello che esce nel payload non
  possono divergere.
- `iat` ed `exp` in **secondi**, come vuole la RFC 7519. Tutto il resto dell'app lavora in
  millisecondi: **la conversione avviene dentro `Jwt` e in nessun altro posto**, e nessun valore in
  secondi esce da quel file. È il genere di unità mista che produce un token che scade fra
  cinquant'anni o fra mezz'ora, e non si nota finché non è tardi.
- `Json { ignoreUnknownKeys = true }`: un campo sconosciuto dentro un payload **firmato** non è un
  attacco, e un decoder severo renderebbe impossibile aggiungere un claim senza rompere i token in
  circolazione.
- Lunghezza ~180 caratteri contro gli 8 di oggi. `MAX_LINEA` è 8 KiB e `MAX_HEADER` è 50: due ordini
  di grandezza di margine, nessuna modifica a `HttpMessages.kt`.

### 5.4 — La regola che rende sicuro un verificatore scritto a mano

**Firma prima, payload poi, header mai.** Va scritta nel sorgente come invariante, non come commento
di cortesia:

```
1. si spezza in tre segmenti; diverso da tre → null
2. si ricalcola HMAC-SHA256 sui BYTE GREZZI di "<seg0>.<seg1>"
3. constantTimeEquals con il terzo segmento decodificato → se diverso, null
4. SOLO ORA si decodifica seg1 e lo si legge: v, exp, p
5. seg0 non viene decodificato MAI
```

`alg: none` e la confusione fra algoritmi non vengono «respinte»: **non hanno un posto dove
entrare**, perché nessun ramo del codice dipende dal contenuto dell'header. È la stessa forma di
difesa di `StaticAssets`, dove la risalita di percorso non è filtrata ma resa impossibile
dall'elenco chiuso.

`java.util.Base64.getUrlDecoder().decode()` lancia `IllegalArgumentException` su input non valido:
va catturata dentro `Jwt`, o il difetto diventa un `500` invece di un `401`.

### 5.5 — La chiave di firma e la revoca

`ChiaveFirma` è modellata su `CertificateStore`, **avvertenza compresa**: `caricaOCrea()` con la
guardia in un punto solo e cache in memoria, perché `apri()` è chiamata anche da `resume()`, cioè da
`MainActivity.onStart()`, cioè a ogni rotazione dello schermo. Una chiave rigenerata lì butterebbe
fuori ogni browser senza che nessuno abbia toccato niente (R-09).

Due differenze rispetto al certificato, ed entrambe contano:

1. **C'è una `revoca()`**, ed è l'**unica** altra porta da cui una chiave nuova può entrare. Va
   scritto lì che è l'unica.
2. **Un file corrotto non si rigenera sul posto** (D-10). Per il certificato rigenerare costa un
   avviso del browser; qui costa la cacciata di tutti i browser insieme — cioè una revoca di massa,
   che non deve poter avvenire in silenzio nel mezzo di una sessione. La regola è in tre mosse:

   ```
   lunghezza ≠ 32 byte  →  si cancella il file
                        →  la porta NON si apre, con il messaggio qui sotto
                        →  al riavvio dell'app non c'è più niente da leggere: percorso normale
                           di creazione, chiave nuova
   ```

   *«La chiave d'accesso era illeggibile ed è stata rimossa. Riavvia l'app per ricrearla: gli
   indirizzi consegnati finora andranno riconsegnati.»*

   **Cancellare è ciò che impedisce il ciclo:** senza, ogni `resume()` — cioè ogni ritorno in primo
   piano — ritroverebbe lo stesso file corrotto e ripeterebbe lo stesso rifiuto per sempre.

Un file valido è **esattamente 32 byte**. Qualunque altra lunghezza è corruzione.

I permessi si stringono con `soloPerNoi`, come per la chiave TLS: su Android `filesDir` è già
privata, ma il codice gira anche sul target desktop dove decide la `umask`.

### 5.6 — Il contratto HTTP

| Metodo | Percorso | Credenziale | Prima | Dopo |
|---|---|---|---|---|
| GET | `/` | nessuna | `403` | **`200`** |
| GET | `/app.css`, `/app.js`, `/manifest.json`, icone | nessuna | `200` | `200` |
| GET | `/api/eventi` | assente / malformata / firma sbagliata / scaduta / bloccata | `403` | **`401`** |
| GET | `/api/eventi` | Bearer lettura o scrittura | `200`/`304` | `200`/`304` |
| POST | `/api/eventi` | Bearer lettura | `403` | `403` |
| PUT | `/api/eventi/{id}` | Bearer scrittura | `200` | `200` |
| — | qualunque | `?t=<qualunque cosa>` | autentica | **ignorato → `401`** |

`WWW-Authenticate` **non** si manda: non serve a un client nostro e allarga la superficie.

Lo schema è **case-insensitive** per RFC 7235: `bearer xyz` deve funzionare come `Bearer xyz`.

Le quattro condizioni «non autenticato» restano **indistinguibili fra loro sul filo**, per la regola
della 002 (`NEGATO` e `BLOCCATO` danno la stessa risposta). Il `403` compare **solo** a chi ha già un
token valido, e non gli dice niente che non sappia già.

`WEB_PAYLOAD_VERSION` **non cambia**: il corpo di `/api/eventi` resta identico campo per campo.
Cambia chi ha il diritto di riceverlo.

### 5.7 — Perché `/` diventa pubblica, e perché non è una rinuncia

Il browser che apre `https://IP:9888/` **non può mandare un header prima di aver caricato il
JavaScript**. È il vincolo da cui discende tutta la forma della feature: `/` deve essere servibile
senza credenziali, perché è il guscio che poi si autentica.

`index.html` non contiene nessun promemoria: è markup vuoto. Il prezzo — una richiesta senza
credenziali rivela che il servizio è acceso — è **già stato accettato e dichiarato dalla 002** per
`/app.css`, e qui si estende a un file in più della stessa natura. Il controllo d'accesso resta
intero dove stanno i dati.

`tokenRichiesto` resta nel modello (D-11) anche se tutti e sei gli asset valgono `false`: non è il
controllo, è ciò che obbliga la prossima risorsa a nascere con una decisione presa.

### 5.8 — La macchina a stati del client

```
   avvio
     │
     ├─ frammento presente ─► salva in localStorage ─► ripulisci la barra ──┐
     │                        (fallisce? tieni in RAM e LASCIA il frammento)│  D-15
     ├─ altrimenti ─► leggi da localStorage ──────────────────────────────► │
     │                                                                      ▼
     ├─ nessun token ─► «apri l'indirizzo dal telefono», NESSUN GIRO DI RETE
     │
     └─ token ─► ciclo della 005 (attesa lunga)
                   │
                   ├─ 200/304 ─► normale
                   ├─ 403 su scrittura ─► «questo indirizzo permette solo di guardare»; il ciclo continua
                   └─ 401 ─► cancella il token, messaggio, senzaAccesso = true, CICLO FERMO
```

**Tre punti che sembrano dettagli e non lo sono.**

1. **La bandiera del ciclo fermo è nuova, non è `fermato`** (D-13). `fermato` appartiene a
   `visibilitychange`, e il ramo che rimette in moto il ciclo fa `if (!inCorso) giro()`: riusarla
   vorrebbe dire che nascondere e riscoprire la scheda fa ripartire un ciclo che sta ricevendo `401`
   — dieci richieste al minuto contro una soglia di dieci al minuto.
2. **Il `403` non cancella niente.** Un token di sola lettura è un token buono: cancellarlo perché
   ha ricevuto un rifiuto su una scrittura butterebbe via l'accesso di chi ha semplicemente toccato
   un pulsante che non doveva esserci. Il `403` e il `401` restano due strade separate anche qui.
3. **Se `localStorage` non è disponibile, il frammento si lascia dov'è** (D-15). Ripulire l'URL su
   un browser che non sa conservare niente ucciderebbe la pagina al primo ricaricamento. Lasciandolo,
   quel browser degrada esattamente al comportamento di oggi — e in più il token non passa comunque
   dal filo.

Ogni accesso a `localStorage` va in `try/catch`: in modalità privata o con i dati del sito bloccati
può lanciare, e la pagina deve funzionare almeno per la sessione corrente.

### 5.9 — Modifiche al data model

**Nessuna.** Nessuna tabella, nessuna migrazione, nessun campo. `AppDatabase` resta alla versione 5.
L'unico stato nuovo è un file da 32 byte accanto al certificato.

### 5.10 — Breaking changes

| Componente | Rottura | Piano di migrazione |
|---|---|---|
| Indirizzi `?t=…` della 002/004 | Smettono di autenticare: `401` | **Nessuno, e non serve.** `WebService.apri()` alla riga 152 conia una coppia nuova quando non ne trova in memoria, e `corrente` in `AccessToken` è `@Volatile`: ogni riavvio del processo **già oggi** invalida tutto. Non esiste un indirizzo consegnato abbastanza longevo da poter essere rotto |
| `GET /` senza credenziali | Da `403` a `200` | Voluto: §5.7 |
| `WebServerController` | Nuovo metodo `revoke()` | Interfaccia interna. `WebServerNonDisponibile` lo implementa come no-op, come già fa per gli altri tre |

---

## 6. Piano di implementazione

| ID | Task | Area | Stima (gg) | Dipende da |
|---|---|---|---|---|
| **T-01** | `Jwt.kt` — firma, verifica, base64url, conversione secondi/millisecondi | BE | 0,50 | — |
| **T-02** | `JwtTest` — la batteria avversariale (TC-02) | Test | 0,50 | T-01 |
| **T-03** | `ChiaveFirma.kt` — `caricaOCrea` con guardia unica, `revoca`, `soloPerNoi`, 32 byte esatti | BE | 0,30 | T-01 |
| **T-04** | `ChiaveFirmaTest` | Test | 0,25 | T-03 |
| **T-05** | `AccessToken` riscritto — `conia`, `verifica` da Bearer; via `rigenera`/`invalida`/`CoppiaToken` | BE | 0,30 | T-01, T-03 |
| **T-06** | `AccessTokenTest` riscritto — 18 `rigenera()` e 1 `invalida()` da rivedere; una asserzione cambia di segno | Test | 0,35 | T-05 |
| **T-07** | `Router` — credenziale dall'header, `401`/`403`, `/` pubblica in `StaticAssets` | BE | 0,30 | T-05 |
| **T-08** | `RouterTest` esteso + `AttesaLungaTest` adattato | Test | 0,30 | T-07 |
| **T-09** | `WebService` — `ChiaveFirma`, coniatura, `revoke()`, i due guasti di D-09 e D-10 | BE | 0,40 | T-03, T-05 |
| **T-10** | `WebServerController` — `revoke()`, frammento in `indirizzo()`, `validoFinoA` | BE | 0,20 | T-09 |
| **T-11** | `WebServiceTest` — le due asserzioni rovesciate, dichiarate una per una | Test | 0,30 | T-09, T-10 |
| **T-12** | `app.js` — raccolta dal frammento, `localStorage` difeso, header su ogni `fetch` | FE | 0,40 | T-07 |
| **T-13** | `app.js` — i due stati nuovi e la bandiera del ciclo fermo | FE | 0,35 | T-12 |
| **T-14** | `SezioneWebApp` + `WebViewModel` — revoca con dialogo, `validoFinoA`, tre testi da riscrivere | UI | 0,50 | T-10 |
| **T-15** | `JwtPiattaformaTest` — presidio strumentale su dispositivo (R-07) | Test | 0,25 | T-01 |
| **T-16** | I commenti che dichiarano il comportamento vecchio: `ReminderApp` righe 37-40, `WebServerService` (R-12) | Doc | 0,15 | T-09 |
| **T-17** | Checklist manuale nel browser (TC-15…TC-20) | Test | 0,30 | T-13, T-14 |
| **T-18** | Verifica sul dispositivo: `force-stop`, revoca, R-07 | Test | 0,35 | T-15, T-17 |
| **T-19** | `CLAUDE.md` (sezione `web/`, correzione in profondità) + `status.md` | Doc | 0,45 | tutti |

**Stima totale: 6,45 giorni/uomo**
**Breakdown:** BE 2,00 · FE 0,75 · UI 0,50 · Test 2,60 · Doc 0,60

### Scostamento dalla Fase 1

La Fase 1 stimava ≈5,5 gg più 0,5 di margine su R-01. **+0,95 rispetto a quel totale**, e le tre
ragioni sono note:

- **+0,25** il dialogo di conferma (D-07): è il primo della schermata, quindi non c'è un pattern da
  copiare;
- **+0,30** la gestione dei due guasti della chiave (D-09, D-10), che in Fase 1 non erano decisi;
- **+0,40** il lavoro di test, che qui è enumerato riga per riga invece che stimato in blocco.

Il margine di 0,5 su R-01 resta **in piedi e non è incluso** nei 6,45: se la batteria avversariale
di T-02 scopre un buco in T-01, si riscrive.

### Da dove si comincia davvero

**T-01 e T-02, e non è ordine di comodo.** Quattro file finiranno per dipendere dal fatto che un
token contraffatto non entra. Scoprire che entra dopo averceli appoggiati sopra costa la riscrittura
di tutti e quattro — e il buco non si sarebbe visto in nessuna prova manuale, perché una prova
manuale usa token buoni.

### Ordine di consegna, se il tempo stringe

Non si consegna a metà. Il telefono e il browser vanno insieme: un server che accetta solo l'header
con una pagina che manda solo la query è una web app che non si apre. L'unico taglio possibile è
**T-14 ridotto** (la revoca senza dialogo, o senza `validoFinoA`), che toglie rifinitura e non
funzione — ma non US-003, che è una delle tre ragioni per cui la feature esiste.

---

## 7. Piano di test

**Strategia.** Il pezzo nuovo e delicato è uno solo — il verificatore — e va provato **per primo e
in modo avversariale**: non «funziona con un token buono», ma «non si fa fregare da tredici token
cattivi». Il resto è adattamento di test che esistono. Il JavaScript resta non provato
automaticamente, come dalla 005, e la sua prova è la checklist manuale: va eseguita, non evocata.

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| **TC-01** | Unit | Giro completo: si conia un token di lettura e uno di scrittura, si verificano, `p` torna quello giusto | Alta |
| **TC-02** | Unit | **La batteria avversariale**, tredici casi: firma manomessa · payload manomesso · due segmenti · quattro segmenti · segmento non base64url · `{"alg":"none"}` con terzo segmento vuoto · firma di un'altra chiave · `v` sconosciuta · `exp` scaduto di un secondo · `exp` assente · token vuoto · token di soli punti · firma di lunghezza sbagliata. Tutti `null`, **nessuno lancia (M5)** | Alta |
| **TC-03** | Unit | `exp` è a 30 giorni esatti dall'istante di coniatura, con `now` finto; e i secondi non escono da `Jwt` | Alta |
| **TC-04** | Unit | `ChiaveFirma`: nasce una volta sola; una seconda istanza sulla stessa cartella legge la stessa chiave | Alta |
| **TC-05** | Unit | `ChiaveFirma.revoca()`: la chiave cambia e i token coniati prima non verificano più | Alta |
| **TC-06** | Unit | File di 31 byte → il file viene **cancellato**, la porta non si apre, il messaggio dice di riavviare; una seconda istanza sulla stessa cartella crea una chiave nuova senza lamentarsi (D-10) | Alta |
| **TC-07** | Unit | Permessi: `p` decide `puoLeggere`/`puoScrivere`; il token di scrittura legge anche | Alta |
| **TC-08** | Unit | La soglia resta **per indirizzo** e non globale; la finestra dimentica; un accesso riuscito azzera | Alta |
| **TC-09** | Unit | Un token valido ma insufficiente **non consuma tentativi** (regola della 004) | Alta |
| **TC-10** | Unit | **Spegnere non invalida niente**; revocare sì. È l'asserzione che cambia di segno rispetto a `WebServiceTest` | Alta |
| **TC-11** | Integration | `GET /` senza credenziali → `200`; `GET /api/eventi` senza header → `401` | Alta |
| **TC-12** | Integration | `Bearer` di lettura → `200`; `PUT` con quello di lettura → `403` e **nessun tentativo consumato** | Alta |
| **TC-13** | Integration | `bearer` minuscolo funziona; `Bearer` da solo → `401`; `?t=<jwt valido>` → `401` | Alta |
| **TC-14** | Integration | `attendi=1` autenticato si sospende e si sveglia come nella 005 | Media |
| **TC-15** | Manuale | Il frammento sparisce dalla barra; ricaricando l'URL nudo la lista compare | Alta |
| **TC-16** | Manuale | Spegni/riaccendi l'interruttore → il segnalibro funziona ancora **(M2)** | Alta |
| **TC-17** | Manuale | `am force-stop` + riapertura → il segnalibro funziona ancora **(M1)** | Alta |
| **TC-18** | Manuale | Revoca → `401`, messaggio, **e nessuna richiesta successiva** in DevTools **(M4)** | Alta |
| **TC-19** | Manuale | Indirizzo di sola lettura: nessun comando; `PUT` a mano → `403` con il messaggio giusto | Media |
| **TC-20** | Manuale | Nessun token in `path` o `query` in tutta la sessione di DevTools **(M3)** | Alta |
| **TC-21** | Strumentale | `Jwt` conia e verifica sul dispositivo (`java.util.Base64` al `minSdk` 26) | Alta |

### Definition of Done

- [ ] `:shared:desktopTest`, `:desktopApp:test` e `:androidApp:assembleDebug` verdi.
- [ ] TC-21 verde su emulatore o dispositivo.
- [ ] TC-15…TC-20 eseguiti a mano e riportati in `status.md`, con l'esito reale — anche se negativo.
- [ ] Nessun test preesistente modificato **senza che la modifica sia dichiarata**: `AccessTokenTest`
      e `WebServiceTest` cambiano di proposito (§D.2 della Fase 2), e ogni asserzione rovesciata va
      nominata. Un test che cambia in silenzio è indistinguibile da un test aggiustato per passare.
- [ ] Nessun testo dell'app o commento nel sorgente dichiara più il comportamento vecchio.
- [ ] `CLAUDE.md` corretto, non solo esteso.

---

## 8. Rischi e mitigazioni

| # | Rischio | Prob. | Impatto | Mitigazione |
|---|---|---|---|---|
| **R-01** | Il verificatore JWT è codice di sicurezza scritto a mano: `alg: none`, confusione fra algoritmi, base64url malformato | Media | **Alto** | La regola strutturale di §5.4 scritta come invariante; TC-02 come batteria avversariale; T-01/T-02 **per primi**. Il codice nuovo è la codifica, non la crittografia: HMAC e confronto vengono da `sync/Crypto.kt`, già provati da `SecureChannel` |
| **R-02** | `/` diventa pubblica | Alta | Basso | È il vincolo di §5.7, non aggirabile. `index.html` non contiene dati; prezzo già dichiarato dalla 002 per `/app.css`. Da scrivere in `CLAUDE.md` |
| **R-03** | Il token vive 30 giorni invece di una sessione: un dispositivo prestato resta autorizzato | Media | Medio | È il compromesso richiesto (D-02), non un difetto. Contropartita: US-003. Il testo della schermata deve dirlo, o l'utente continuerà a credere che spegnere basti |
| **R-03b** | `localStorage` è per **origine**: aprire l'indirizzo di sola lettura su un browser che aveva quello completo lo **declassa** (e viceversa) | Media | Basso | Prevedibile e reversibile. Va scritto, perché è l'unico modo in cui l'utente può togliersi i comandi senza accorgersene |
| **R-04** | `localStorage` assente o che lancia | Media | Medio | `try/catch` su ogni accesso, ripiego in RAM, **e il frammento si lascia dov'è** (D-15) |
| **R-05** | Rottura dichiarata: `?t=` smette di funzionare | Alta | Basso | Nessun client vivo (§5.10). Scritto nel documento come rottura voluta |
| **R-06** | «Fisso» è vero solo finché l'IP è fisso | Media | Medio | Fuori scope risolverlo; dentro scope **non peggiorarlo**: il token non è legato all'indirizzo, quindi una riconsegna sulla rete nuova basta. Da dichiarare all'utente |
| **R-07** | `java.util.Base64` è esattamente al `minSdk` 26 | Bassa | Medio | TC-21, file nuovo accanto a `TlsPiattaformaTest` — che è il precedente giusto, non `CorpoPiattaformaTest`. È il confine su cui il progetto si è già bruciato due volte |
| **R-08** | Il `401` fa girare a vuoto il ciclo della 005 | Alta | Medio | Bandiera nuova (D-13), controllata in `giro()` e in `programma()`, mai rimessa a `false` da `visibilitychange` |
| **R-09** | La chiave di firma rigenerata per sbaglio caccia fuori tutti | Media | **Alto** | Guardia in un punto solo, copiata da `CertificateStore.caricaOCrea` avvertenza compresa; `revoca()` come unica altra porta; e D-10, che toglie l'ultimo caso in cui una chiave nuova poteva nascere senza che nessuno se ne accorgesse |
| **R-10** | `401`/`403` raccontano qualcosa a chi sonda | Bassa | Basso | Le quattro condizioni «non autenticato» restano indistinguibili fra loro. Il `403` lo vede solo chi ha già un token valido |
| **R-11** | Gli indirizzi diventano lunghi (~180 caratteri) | Alta | Basso | Si copiano, non si leggono. In compenso ora differiscono per **tutta** la coda invece che per otto caratteri: lo scambio che la 004 mitigava con il tocco in più diventa meno probabile |
| **R-12** | La motivazione di `START_NOT_STICKY` smette di essere vera | Alta | Basso | La scelta resta giusta per un'altra ragione — a servizio riavviato dal sistema nessuno chiamerebbe `resume()`, e la notifica dichiarerebbe aperta una porta chiusa. Si riscrive il commento (T-16); `START_STICKY` è un seguito, non questa feature |
| **R-13** | Un token può scadere mentre una richiesta è appesa (fino a 25 s) | Alta | Basso | Accettato e dichiarato: l'autorizzazione vale per la richiesta, non per l'istante della risposta. Ricontrollarla al risveglio non aggiungerebbe sicurezza e aggiungerebbe un ramo |

---

## 9. Rollout

**Strategia: deploy diretto.** Nessun feature flag nuovo — **ce n'è già uno ed è quello giusto**:
`AppSettings.webEnabled`, spento di default, che l'utente controlla dalla schermata. Una feature che
riguarda solo chi ha acceso quell'interruttore non ha bisogno di un secondo interruttore.

**Nessuna migrazione.** Nessuna tabella, nessun campo, `AppDatabase` resta alla versione 5.

**Piano di rollback**

1. `git revert` del commit della feature.
2. Il file `firma.key` resta su disco, inerte: nessuna pulizia necessaria, e se la feature tornasse
   sarebbe ancora buona.
3. Gli indirizzi `#access=` consegnati smettono di funzionare, e tornano quelli `?t=`. Non serve
   avvisare nessuno: dopo un rollback l'app si riavvia comunque, e con la versione precedente
   riavviarsi **significa già** cambiare tutti i token.

Il rollback è quindi **pulito in un senso preciso**: non lascia dati inconsistenti e non richiede
nessuna azione dell'utente oltre a riprendere l'indirizzo dalla schermata — che è esattamente ciò
che la versione precedente gli chiedeva di fare a ogni riavvio.

---

## 10. Checklist di approvazione

| Revisione | Responsabile | Stato | Data |
|---|---|---|---|
| Revisione tecnica | Alberto Goldoni | ⏳ In attesa | — |
| Decisioni D-01…D-15 confermate | Alberto Goldoni | ✅ Confermate | 2026-08-23 |
| Stima 6,45 gg accettata | Alberto Goldoni | ⏳ In attesa | — |
| Rischi accettati (R-01 e R-09 sono i due alti) | Alberto Goldoni | ⏳ In attesa | — |
| Data di inizio confermata | Alberto Goldoni | ⏳ In attesa | — |

---

## Domande aperte

1. **Il testo esatto dei quattro messaggi**  — scadenza, revoca, «spegnere non revoca» nella schermata (T-14),
   e la chiave illeggibile in §5.5 — si scrive in T-14 e T-09. Vale la pena rileggerli insieme prima
   di considerarli finiti: in questa schermata il testo *è* la funzione, come lo era l'avviso sul
   certificato della 003.

### Chiuse il 2026-08-23

- **D-06** confermata: `#access=`.
- **D-10** decisa dall'utente, e diversa sia dalla Fase 2 sia dalla prima stesura di questo
  documento: non «si rigenera in silenzio» e non «serve la revoca», ma **si cancella, non si apre, e
  la ricrea il riavvio dell'app**.

---

*Documento generato con la skill `claude-code-feature`.*
