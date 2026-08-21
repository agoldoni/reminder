# Web app locale su Android — Implementation Plan

**Stato:** Bozza — decisioni chiuse, in attesa di approvazione
**Autore:** Alberto Goldoni
**Data:** 2026-08-21
**Versione:** 1.1 *(1.0 → 1.1: chiuse le sette decisioni aperte; vedi §3)*

---

## 1. Executive Summary

L'app Android apre, su richiesta dell'utente, una porta HTTP dalla quale un qualsiasi browser
sulla stessa rete locale può leggere la lista dei promemoria aperti. Serve a consultarli da un
computer o da un tablet su cui l'app non è installata, senza prendere in mano il telefono e senza
associare nulla. La porta è **chiusa di default**, si apre con un atto esplicito dalla schermata
Sincronizzazione, si chiude quando l'app esce dal primo piano, ed è protetta da un token che
compare nell'indirizzo. La vista è di **sola lettura**, ed è dichiaratamente il **primo passo**:
router e modello di scambio vengono progettati per accogliere la scrittura in una iterazione
successiva. Stima: **11,00 giorni/uomo**, intervallo realistico 10–13.

---

## 2. Obiettivo e motivazione

- **Problema che risolve:** oggi i promemoria si leggono solo dall'app Android o dall'app desktop
  Linux su una macchina installata e associata. Chi è davanti a uno schermo qualsiasi — il PC di
  lavoro dove non può installare un AppImage, un portatile con un altro sistema operativo, un
  tablet — non ha modo di dare un'occhiata alla lista. La sincronizzazione punto-punto non copre
  questo caso: richiede l'app su entrambi i lati e un'associazione permanente, sproporzionata per
  una consultazione occasionale. L'export ODS è a senso unico e fotografa un istante.

- **Metriche di successo** (verifica manuale, nessuna analytics):
  - [ ] Da un browser sulla stessa rete, digitando l'URL mostrato dall'app, la lista compare in
        meno di 2 secondi.
  - [ ] La lista nel browser coincide riga per riga, e nell'ordine, con quella dell'app.
  - [ ] A interruttore spento la connessione è **rifiutata**: da fuori il servizio non esiste.
  - [ ] Richiesta senza token o con token errato → `403`, nessun dato.
  - [ ] L'app in background chiude la porta; tornata in primo piano torna raggiungibile allo
        stesso indirizzo, con lo stesso token.
  - [ ] Nessuna regressione su allarmi, sincronizzazione ed export; APK non apprezzabilmente più
        grande (nessuna libreria nuova).

- **Legame con gli obiettivi del progetto:** *Promemoria* è un'app personale a dipendenze minime,
  che privilegia il codice scritto in casa quando la libreria costerebbe più di quanto risolve
  (l'esportatore ODS, HKDF, il protocollo di sincronizzazione). Questa feature estende la
  raggiungibilità dei dati **senza** introdurre un server centrale, un account o una dipendenza:
  la stessa linea della feature `001-desktop-linux`.

---

## 3. Scope

### Incluso

- **Server HTTP minimale** scritto a mano sopra `ServerSocket`, in `jvmSharedMain`: solo `GET`,
  `Connection: close`, nessun keep-alive, nessun upload. Porta **9888**.
- **Controllo dell'accesso a token**: generato a ogni accensione, invalidato allo spegnimento,
  confrontato a tempo costante, con limitazione dei tentativi per indirizzo.
- **Vista principale in sola lettura**: promemoria aperti ordinati per data, con titolo, data e
  ora, orario di notifica e descrizione; stessa codifica cromatica dell'app (scaduto / oggi /
  domani / più avanti); stato vuoto esplicito.
- **Confezione web**: pagina unica responsive, tema chiaro/scuro via `prefers-color-scheme`,
  `manifest.json` e icone, asset statici imbarcati nell'APK.
- **Aggiornamento solo quando qualcosa è cambiato**: richiesta condizionale con impronta del
  contenuto; se nulla è cambiato il server risponde `304` e la pagina non si ridisegna.
- **Integrazione Android**: interruttore persistente spento di default, **sezione dentro la
  schermata Sincronizzazione** da cui accendere e leggere l'indirizzo completo, avvio e arresto
  legati al primo piano.
- **Predisposizione alla scrittura**: identificatore stabile e numero di versione nel modello di
  scambio, smistamento per metodo già strutturato. Nessun endpoint di scrittura in questa
  iterazione.
- **Test** automatici sulla logica e **verifica manuale** sul dispositivo; documentazione.

### Escluso (out of scope)

- **Qualsiasi scrittura dal browser** — richiesta esplicita dell'utente («per ora deve solo
  mostrare la vista principale»). Non è un traguardo ma un primo passo (D-07), e il codice viene
  strutturato di conseguenza.
- **Vista «Fatti», editor, sincronizzazione dal web** — una vista sola, fatta bene.
- **Service worker, offline, installazione PWA piena** — impossibili: dipendono dal *secure
  context*, e `http://192.168.x.y` non lo è. (Verificato in realizzazione: non è il service worker
  mancante a impedire l'installazione — Chrome non lo richiede più — ma il contesto non sicuro.)
- **HTTPS / certificati** — un autofirmato dà comunque avvisi, e un certificato non fidato non
  rende sicuro il contesto.
- **Accesso da fuori la rete locale** — cambierebbe il modello di minaccia.
- **Foreground service / raggiungibilità ad app chiusa** — decisione presa: batteria e notifica
  permanente non giustificate per una consultazione occasionale.
- **Attivazione su desktop** — la richiesta riguarda Android. Il codice resta riusabile.
- **Codice QR** — richiederebbe un encoder che il progetto non ha.
- **Localizzazione** — come il resto dell'app, solo italiano.
- **Push/WebSocket** — la richiesta condizionale ottiene lo stesso effetto pratico a un decimo del
  costo.

### Decisioni chiuse

Chiuse dall'utente il 2026-08-21. Sono qui con la loro conseguenza, perché una decisione senza la
sua conseguenza si dimentica.

| # | Decisione | Scelta | Conseguenza |
|---|---|---|---|
| D-01 | Numero di porta | **9888** | Distinta da 47700 (`SYNC_PORT`) e 47653 (`SingleInstance.DEFAULT_PORT`); i due test di distinzione restano obbligatori (T-02) |
| D-02 | Punto d'ingresso | **Sezione dentro la schermata Sincronizzazione** | Niente schermata nuova, niente rotta nuova, niente quinta icona. `EventListScreen` e `NavGraph` **non si toccano**; si modifica `SyncScreen`. Risolve R16 e riduce T-12 di 0,25 gg |
| D-03 | Caricamento asset | **Classloader** da `androidMain/resources` | Nessun `Context` richiesto, codice riusabile su desktop. Resta subordinata all'esito di T-03, che ha già il ripiego individuato |
| D-04 | Formattazione delle date | **Lato browser** | Il JSON porta solo millisecondi; il client formatta con `Intl.DateTimeFormat('it-IT')`. Vedi sotto: ha un costo e un beneficio, entrambi reali |
| D-05 | Aggiornamento | **Solo se qualcosa è cambiato** | Richiesta condizionale con `ETag`/`If-None-Match` e `304`. Vedi §5 per il dettaglio che sembra una contraddizione e non lo è |
| D-06 | Token e soglia | **8 caratteri, soglia 10 tentativi** | Deciso in assenza di indicazione contraria; è il parametro più facile da cambiare di tutto il piano |
| D-07 | Sola lettura: traguardo o primo passo? | **Primo passo** | Il modello di scambio porta un identificatore stabile e un numero di versione fin da ora, e lo smistamento per metodo è già strutturato |

**Sul costo e sul beneficio di D-04.** Il costo: la fascia cromatica e gli orari li calcola il
browser sul **proprio** fuso, quindi un PC in un altro fuso mostrerebbe orari diversi da quelli
dell'app, e il criterio «coincide riga per riga» cadrebbe in modo silenzioso. Su una rete locale i
due dispositivi condividono quasi sempre il fuso, quindi in pratica il caso è raro — ma va saputo,
non scoperto. Il beneficio, che compensa: **il colore resta corretto senza rete**. Un promemoria
che scade alle 18:00 deve passare da «oggi» a «scaduto» alle 18:00 anche se nessun dato è
cambiato; con il calcolo lato client la pagina se ne accorge da sola, mentre con la formattazione
lato server sarebbe servita una richiesta in più solo per far cambiare un colore — proprio ciò che
D-05 vuole evitare. **Le due decisioni si sostengono a vicenda.**

---

## 4. User Stories e criteri di accettazione

### US-001 · Consultare senza installare
**Priorità:** Must Have

Come utente davanti a un computer sul quale non ho l'app, voglio aprire nel browser la lista dei
miei promemoria, per non dover prendere in mano il telefono ogni volta.

**Criteri di accettazione:**
- [ ] Con interruttore acceso e app in primo piano, un browser sulla stessa rete che apre l'URL
      mostrato riceve una pagina HTML con la lista dei promemoria aperti.
- [ ] L'ordine è per data crescente, identico a quello dell'app.
- [ ] Ogni riga mostra titolo, data/ora dell'evento e orario della notifica; la descrizione,
      quando c'è, è visibile.
- [ ] Senza promemoria aperti la pagina mostra «Nessun evento».
- [ ] Completati e tombstone **non** compaiono.
- [ ] La pagina si apre correttamente su Chrome e Firefox, desktop e mobile.

### US-002 · Non lasciare la porta aperta
**Priorità:** Must Have

Come utente che si connette anche a reti non sue, voglio che la porta sia chiusa finché non la
accendo io, per non esporre i miei promemoria a chi condivide il Wi-Fi.

**Criteri di accettazione:**
- [ ] Al primo avvio dopo l'installazione l'interruttore è spento.
- [ ] A interruttore spento la connessione è **rifiutata**, non accettata e poi chiusa.
- [ ] Spegnere l'interruttore chiude il socket **e invalida il token** entro pochi secondi.
- [ ] Lo stato dell'interruttore sopravvive alla chiusura e riapertura dell'app.
- [ ] Accenderlo non richiede permessi Android nuovi.

### US-003 · Sapere che indirizzo digitare
**Priorità:** Must Have

Come utente voglio leggere sull'app l'indirizzo completo da digitare sull'altro dispositivo, per
non cercare l'IP del telefono nelle impostazioni di sistema.

**Criteri di accettazione:**
- [ ] La sezione dentro Sincronizzazione mostra host, porta e token in forma copiabile e
      digitabile senza ambiguità.
- [ ] L'indirizzo è quello della rete locale (non `127.0.0.1`, non `127.0.1.1`).
- [ ] Senza un indirizzo di rete utilizzabile, l'app lo dice invece di mostrarne uno inservibile.
- [ ] L'indirizzo si aggiorna se cambia la rete mentre la schermata è aperta.
- [ ] La porta mostrata è quella su cui il socket si è **effettivamente** legato.
- [ ] Con l'interruttore spento la sezione non mostra un indirizzo: mostra che è spento.

### US-004 · Non farsi leggere da chi passa di lì
**Priorità:** Must Have

Come utente voglio che chi conosce indirizzo e porta ma non il token non veda nulla, per non
dovermi fidare del fatto che nessuno scansioni la rete.

**Criteri di accettazione:**
- [ ] Richiesta senza token a `/` o `/api/eventi` → `403`, corpo vuoto.
- [ ] Token errato → `403` **indistinguibile** dal caso precedente.
- [ ] Il token cambia a ogni accensione: un URL della sessione precedente riceve `403`.
- [ ] Superata la soglia di 10 tentativi falliti dallo stesso indirizzo, le richieste successive
      sono respinte senza confrontare il token.
- [ ] Metodi diversi da `GET` → `405`.
- [ ] Percorsi costruiti per uscire dalla cartella degli asset (`../`, `..%2f`, `%2e%2e/`,
      percorsi assoluti, doppie barre) non restituiscono nulla fuori dall'elenco ammesso.
- [ ] Ogni risposta porta `Referrer-Policy: no-referrer`, `Cache-Control: no-store`,
      `X-Content-Type-Options: nosniff`.

### US-005 · Lasciare la scheda aperta
**Priorità:** Should Have

Come utente che tiene una scheda aperta accanto al lavoro, voglio che la lista si aggiorni da
sola, e **solo quando c'è davvero qualcosa di nuovo**.

**Criteri di accettazione:**
- [ ] Un promemoria creato sull'app compare nella pagina aperta entro l'intervallo di controllo,
      senza ricaricare a mano.
- [ ] Un promemoria completato o eliminato sparisce entro lo stesso intervallo.
- [ ] Se nulla è cambiato, il server risponde `304` e **la pagina non si ridisegna**: nessuno
      sfarfallio, nessuna perdita della posizione di scorrimento.
- [ ] Se il server diventa irraggiungibile, la pagina lo **segnala** invece di mostrare dati vecchi
      come se fossero freschi.
- [ ] Le richieste non si accumulano sovrapposte quando la rete è lenta.
- [ ] A scheda nascosta il controllo è sospeso, e riprende subito quando la scheda torna visibile.
- [ ] La fascia cromatica si aggiorna al passare dell'ora **senza** richieste di rete: un
      promemoria che scade diventa «scaduto» da solo.

### US-006 · Leggere bene su qualunque schermo
**Priorità:** Should Have

Come utente che apre la pagina ora dal telefono e ora da un monitor grande, voglio una vista che si
adatti e usi gli stessi colori dell'app.

**Criteri di accettazione:**
- [ ] Leggibile senza scorrimento orizzontale a 360 px di larghezza.
- [ ] Su schermo largo il contenuto non si stira in modo illeggibile.
- [ ] I quattro stati cromatici sono distinguibili e corrispondono a quelli dell'app.
- [ ] Le date sono nel formato `dd/MM/yyyy HH:mm`, lo stesso di `formatDateTime`.
- [ ] Con `prefers-color-scheme: dark` la pagina usa la variante scura.
- [ ] `manifest.json` è servito col tipo MIME corretto; l'aggiunta alla schermata Home produce
      icona e nome corretti.

### US-007 · Sapere cosa resta acceso
**Priorità:** Must Have

Come utente voglio che chiudendo l'app la porta si chiuda davvero, per non restare col dubbio di
aver lasciato un servizio attivo sul telefono.

**Criteri di accettazione:**
- [ ] Mandando l'app in background la porta smette di accettare connessioni.
- [ ] Riportandola in primo piano, con l'interruttore acceso, torna raggiungibile allo stesso host
      e alla stessa porta.
- [ ] Il token **resta valido** attraverso un ciclo background → primo piano.
- [ ] Il token **resta valido** attraverso una rotazione dello schermo.
- [ ] Nessun ANR e nessun crash nei passaggi ripetuti.
- [ ] Terminando l'app dal gestore attività la porta torna libera.

---

## 5. Architettura tecnica

### Componenti coinvolti

```
  Browser sulla rete locale
        │  GET /?t=<token>
        │  GET /api/eventi?t=<token>   [If-None-Match: "<impronta>"]
        ▼
  ┌─────────────────────────────────────────────────────┐
  │  jvmSharedMain — package web/                       │
  │                                                     │
  │   HttpServer ──► HttpMessages (parsing, limiti)     │
  │       │                                             │
  │       ▼                                             │
  │   Router ──┬──► AccessToken   (403 / soglia)        │
  │            ├──► StaticAssets  (elenco chiuso)       │
  │            └──► WebPayload ──► EventDao.getAllOpen()│
  │                     │                               │
  │                     └──► impronta ──► 200 | 304     │
  │                                                     │
  │   WebService  (implementa WebServerController)      │
  └─────────────────────────────────────────────────────┘
        ▲                                    ▲
        │ status: StateFlow<WebStatus>       │ enable/disable
        │                                    │ onForeground/onBackground
  ┌─────┴───────────────┐            ┌───────┴──────────────┐
  │ commonMain          │            │ androidApp           │
  │  WebServerController│            │  ReminderApp (crea)  │
  │  WebViewModel       │            │  MainActivity        │
  │  SezioneWebApp ─────┼──► dentro  │   onStart / onStop   │
  │  AppContainer       │   SyncScreen└──────────────────────┘
  └─────────────────────┘
```

L'interfaccia sta in `commonMain` e l'implementazione in `jvmSharedMain` per la stessa ragione già
scritta in `SyncController`: `AppContainer` e le schermate vivono in `commonMain`, mentre socket e
I/O stanno dove `java.*` è disponibile. Il gruppo `jvmShared` è dichiarato con
`applyDefaultHierarchyTemplate`; il `dependsOn` manuale non è ammesso.

**`WebService` è costruito in `ReminderApp.onCreate()` e vive quanto il processo**, con uno scope
dedicato, come già fa `SyncService`. La `MainActivity` gli manda solo `onForeground()` e
`onBackground()`: se socket o token fossero legati alla Activity, una rotazione dello schermo li
ricreerebbe e l'URL già digitato sull'altro dispositivo smetterebbe di funzionare.

**Sulla sezione dentro `SyncScreen` (D-02).** La sezione usa un **`WebViewModel` proprio**, preso
dalla stessa `viewModelFactory` di `AppContainer`, non un ampliamento di `SyncViewModel`. Due
ViewModel in una schermata sono leciti in Compose, e così `SyncViewModel` resta il sottile
passa-carte che è oggi. Attenzione a **non** avviare il server da un `DisposableEffect` della
schermata, come fa `SyncScreen` per `beginInteractive`: il server deve restare vivo quando l'utente
torna alla lista, altrimenti la pagina web muore appena si esce dalle impostazioni. Il confine
giusto è l'`Activity`, non la schermata.

### Modifiche al data model

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| `events` | **Nessuna** | Lo schema resta alla **v5**. Nessuna migrazione, nessun file nuovo in `shared/schemas/` |
| `peers` | **Nessuna** | La feature non conosce i peer |
| `EventDao` | **Nessuna** | Usa `getAllOpen()`, che già filtra `deleted = 0 AND completed = 0` e ordina per `dateTimeMillis ASC` |
| `AppSettings` | Modifica | Due membri nuovi: `webEnabled: StateFlow<Boolean>`, `setWebEnabled(Boolean)`. Vedi Breaking changes |
| `WebStatus` | Nuovo | `enabled`, `host`, `port`, `token`, `url`, `lastMessage` — direttamente mostrabile |
| `WebPayload` | Nuovo | Busta con `versione` + elenco. Ogni voce: `id`, `titolo`, `descrizione`, `dateTimeMillis`, `notificationMillis`. **Nessuna stringa formattata** (D-04): il client formatta. **`id` incluso** (D-07): serve un identificatore stabile per la scrittura futura, e aggiungerlo dopo sarebbe una rottura del formato. `uuid` e `origin` restano fuori: non hanno ragione di uscire dal dispositivo |

`notificationMillis` è calcolato dal server (`dateTimeMillis - advanceMinutes * 60000`) e mandato
come numero. Così la **formula** non viene duplicata in JavaScript, mentre la **formattazione**
resta al client come vuole D-04: si duplica il meno possibile e ciascuno fa la sua parte.

La busta porta un `versione` per la stessa ragione per cui `Hello` porta `protocolVersion`: quando
arriverà la scrittura, un client vecchio contro un server nuovo deve poter fallire con un messaggio
esplicito invece di provare a capirsi.

### Nuove API o endpoint

| Metodo | Path | Descrizione | Token |
|---|---|---|---|
| GET | `/` | Pagina unica della vista principale | **Sì** |
| GET | `/api/eventi` | Promemoria aperti in JSON, con `ETag`. Con `If-None-Match` corrispondente → `304` e corpo vuoto | **Sì** |
| GET | `/app.css`, `/app.js` | Asset della pagina | No |
| GET | `/manifest.json`, `/icona-192.png`, `/icona-512.png` | Confezione web | No |
| *(altro)* | — | `404` | — |
| *(≠ GET)* | — | `405` | — |

**Perché il token protegge solo due percorsi.** CSS, JavaScript, manifest e icone non contengono
dati personali, e pretendere il token su di essi complicherebbe la catena di richieste che il
browser fa **fuori** dal contesto della pagina — il manifest e le icone che vi sono elencate. Chi
non ha il token riceve `403` su `/` e su `/api/eventi`, cioè su tutto ciò che è dato. Il prezzo,
dichiarato: una richiesta non autenticata a `/app.css` rivela che il servizio esiste. È accettato.

**La richiesta condizionale e `Cache-Control: no-store` non si contraddicono, anche se sembra.**
`no-store` vieta alla cache HTTP del browser di conservare la risposta, e quindi impedisce la
rivalidazione automatica. Ma l'impronta la conserva **il nostro JavaScript, in una variabile**, e
manda `If-None-Match` esplicitamente a ogni controllo: funziona a prescindere dalla cache del
browser, e i dati personali continuano a non essere scritti su disco. Vale la pena averlo scritto:
è esattamente il genere di cosa che, riletta fra un anno, sembra un errore.

**L'impronta si calcola sul corpo della risposta**, non su `max(updatedAt)` o sul conteggio. Su
quelle due grandezze si può ragionare a lungo e sbagliare comunque — completare un promemoria lo
toglie dall'insieme degli aperti e il massimo può *scendere* — mentre l'impronta del corpo è
corretta per costruzione e costa un passaggio su pochi kilobyte.

### Breaking changes

| Componente | Tipo di breaking change | Piano di migrazione |
|---|---|---|
| `AppSettings` (commonMain) | Due membri astratti nuovi: rompe la compilazione di **5 implementazioni** — `AndroidAppSettings`, `DesktopAppSettings`, `FakeSettings` (jvmSharedTest), `SettingsInMemoria` (desktopTest), più eventuali fake nuovi | T-01: commit isolato, solo refactoring meccanico, nessuna logica nuova. Verifica: compila e i test passano |
| `AppContainer` (commonMain) | Parametro nuovo `web: WebServerController`; 2 chiamanti (`ReminderApp`, `Main.kt` desktop) | Dichiararlo **con default inerte** — un `object` spento i cui metodi non fanno nulla. Il modulo `:desktopApp` non si tocca. È lo stesso espediente di `navigationRequests: Flow<String> = emptyFlow()` |
| `SyncScreen` (commonMain) | Sezione nuova nella `LazyColumn`; nessuna firma cambia | Nessuna migrazione |

`EventListScreen` e `NavGraph` **non cambiano**: è il regalo di D-02. Nessun breaking change verso
l'esterno: nessuna API pubblica, nessun formato di file, nessuno schema di database.

---

## 6. Piano di implementazione

Responsabile unico: Alberto Goldoni. Aree: **Core** (logica in `:shared`), **Infra**
(build/packaging), **FE** (pagina web), **UI** (schermate Compose), **Test**, **Doc**.
Ogni task di Core include i propri test unitari.

| ID | Task | Area | Stima (gg) | Dipende da |
|---|---|---|---|---|
| T-01 | Estendere `AppSettings` con `webEnabled`/`setWebEnabled` e aggiornare le 5 implementazioni. **Commit isolato, nessuna logica nuova** | Core | 0,50 | — |
| T-02 | Costante `WEB_PORT = 9888` accanto a `SYNC_PORT`, con il commento sul perché non può coincidere; `PorteWebTest` (commonTest) e `PorteTest` esteso (`:desktopApp`); correzione della riga sbagliata di `CLAUDE.md` | Core+Doc | 0,25 | — |
| T-03 | **Verifica bloccante:** file di prova in `shared/src/androidMain/resources/web/` letto via `ClassLoader.getResourceAsStream` **dentro l'APK**, su emulatore o dispositivo. Se fallisce si adotta il ripiego `AssetManager` con interfaccia in `commonMain` | Infra | 0,50 | — |
| T-04 | `HttpMessages`: modello richiesta/risposta, parsing della riga di richiesta e degli header (compreso `If-None-Match`), limiti espliciti; `HttpMessagesTest` sugli input malformati | Core | 1,00 | — |
| T-05 | `HttpServer`: ciclo di `accept()` su `Dispatchers.IO`, `start(porta = 0)` per i test, `stop()` che libera davvero la porta, una connessione andata male che non abbatte il ciclo; `HttpServerTest` su socket reali | Core | 1,00 | T-04 |
| T-06 | `AccessToken`: 8 caratteri da `randomBytes`, confronto con `constantTimeEquals`, soglia di 10 tentativi per indirizzo; `AccessTokenTest` | Core | 0,50 | T-04 |
| T-07 | `StaticAssets`: elenco chiuso di percorsi ammessi, tipi MIME, caricamento dal classloader; test sui tentativi di uscita dall'elenco | Core | 0,50 | T-03 |
| T-08 | `WebPayload` (busta con `versione`, voci con `id` e `notificationMillis`) e lettura da `getAllOpen()`; calcolo dell'impronta sul corpo; `WebPayloadTest` | Core | 0,50 | T-04 |
| T-09 | `Router`: smistamento per metodo e percorso (strutturato perché aggiungere `POST` non sia una riscrittura), applicazione del token ai soli percorsi con dati, `304` su `If-None-Match` corrispondente, header di sicurezza, `404`/`405`; `RouterTest` completo | Core | 0,75 | T-06, T-07, T-08 |
| T-10 | `WebServerController` (commonMain) e `WebService` (jvmSharedMain): interruttore, `onForeground`/`onBackground`, stato osservabile, porta effettiva, errori in italiano invece che eccezioni; `WebServiceTest` | Core | 1,00 | T-01, T-05, T-09 |
| T-11 | Frontend: `index.html`, `app.css`, `app.js`, `manifest.json`, icone. Responsive, quattro stati cromatici ricalcolati localmente al passare dell'ora, tema scuro, formattazione con `Intl.DateTimeFormat('it-IT')`, controllo condizionale con `If-None-Match` ogni 30 s sospeso a scheda nascosta, stato di errore, stato vuoto | FE | 1,50 | T-09 |
| T-12 | `AppContainer` con parametro `web` e default inerte; `WebViewModel`; `SezioneWebApp` innestata in `SyncScreen` | UI | 1,00 | T-10 |
| T-13 | Aggancio in `ReminderApp` (costruzione + scope dedicato) e `MainActivity` (`onStart`/`onStop`) | Core | 0,50 | T-10, T-12 |
| T-14 | Verifica sul dispositivo: `adb forward`, secondo dispositivo su rete reale, rotazione, background/primo piano ripetuti, spegnimento con pagina aperta, cambio di rete. Non-regressione su allarmi, sincronizzazione, export | Test | 1,00 | T-13 |
| T-15 | `CLAUDE.md` con l'area `web/` e le sue trappole; chiusura dei documenti di feature e `status.md` | Doc | 0,50 | T-14 |

**Stima totale:** **11,00 giorni/uomo** · intervallo realistico **10–13**
**Breakdown:** Core 6,50 gg · FE 1,50 gg · UI 1,00 gg · Test 1,00 gg · Infra 0,50 gg · Doc 0,50 gg

**Scostamento dalla Fase 1 (8,00 gg): +3,00.** Da dove viene, per intero:
- **+0,75** prerequisiti che la Fase 1 non aveva visto: refactoring di `AppSettings` su 5
  implementazioni (T-01), costante di porta con i due test di distinzione (T-02).
- **+0,50** la verifica bloccante sul packaging degli asset (T-03), emersa dall'esame dei source
  set: `jvmSharedMain/resources` non arriva nell'artefatto Android.
- **+1,00** l'integrazione, cresciuta da 1,50 a 2,50: oltre a `WebService` vanno toccati
  `AppContainer`, `SyncScreen` e `MainActivity`. Sarebbe stata +1,25 con una schermata dedicata:
  **D-02 ha risparmiato 0,25 gg** eliminando rotta e punto d'ingresso.
- **+0,75** il server, cresciuto da 1,50 a 2,75 fra parsing, ciclo di socket e routing, con i
  rispettivi test dentro il task invece che in una voce separata.

Le richieste condizionali di D-05 non aggiungono stima: l'impronta è un passaggio sul corpo già
serializzato, e il `304` è un ramo del router.

L'ordine è pensato perché **T-01, T-02 e T-03 si chiudano per primi**: sono i tre punti in cui una
sorpresa cambia il piano, e scoprirli a metà di T-11 costerebbe molto di più.

---

## 7. Piano di test

**Strategia generale.** La logica sta in `jvmSharedMain`, cioè in codice JVM puro provabile senza
dispositivo: parsing, routing, token, impronta e serializzazione si scrivono con i test accanto. Il
server si prova su socket veri legati a **porta 0**, così i test non dipendono da una porta libera
sulla macchina di build — è la convenzione già in uso in `SyncServiceTest` e `SyncTransportTest`.
Resta manuale solo ciò che è davvero manuale: il ciclo di vita dell'Activity, la resa su browser
reali e la raggiungibilità attraverso una rete vera.

Dove girano: `jvmSharedTest` e `commonTest` con `./gradlew :shared:desktopTest`;
`desktopApp/src/test` con `:desktopApp:test`. `FakeEventDao` (commonTest) è già visibile da
`jvmSharedTest` e implementa `getAllOpen()` con la semantica della query reale.

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Unit | Richiesta troncata, riga oltre il limite, header oltre il limite, header senza `:`, byte non ASCII, richiesta vuota: nessuno lancia fuori dal parser | Alta |
| TC-02 | Unit | Token assente e token errato producono risposte **byte per byte identiche** | Alta |
| TC-03 | Unit | Superata la soglia di 10, i tentativi sono respinti senza confrontare il token | Alta |
| TC-04 | Unit | `../`, `..%2f`, `%2e%2e/`, percorsi assoluti e doppie barre non restituiscono nulla fuori dall'elenco ammesso | Alta |
| TC-05 | Unit | Il JSON contiene i soli promemoria aperti, nell'ordine di `dateTimeMillis`; niente completati, niente tombstone; `uuid` e `origin` assenti; `id` e `versione` **presenti** | Alta |
| TC-06 | Unit | `notificationMillis` corrisponde a `dateTimeMillis - advanceMinutes * 60000`; descrizione nulla serializzata senza rompere il client; nessuna stringa di data nel payload | Media |
| TC-07 | Unit | Ogni risposta porta i tre header di sicurezza; metodo ≠ `GET` → `405`; percorso ignoto → `404`; il token è richiesto su `/` e `/api/eventi` e **non** sugli asset | Alta |
| TC-08 | Unit | A dati invariati l'impronta è **stabile** fra due chiamate; cambia quando un promemoria è creato, modificato, completato o eliminato — in particolare **quando il completamento fa scendere `max(updatedAt)` dell'insieme aperto** | Alta |
| TC-09 | Unit | `If-None-Match` con impronta corrispondente → `304` e corpo vuoto; impronta diversa o assente → `200` col corpo | Alta |
| TC-10 | Integration | Giro completo su socket reale con un client vero: richiesta, risposta, chiusura | Alta |
| TC-11 | Integration | Connessione chiusa a metà: il ciclo di `accept()` sopravvive e serve la richiesta successiva | Alta |
| TC-12 | Integration | Cento cicli avvio/arresto non lasciano socket appesi; `stop()` libera davvero la porta | Alta |
| TC-13 | Integration | A interruttore spento nessun socket è aperto (calco dell'omologo test della sincronizzazione) | Alta |
| TC-14 | Integration | `disable()` chiude **e invalida il token**; `onBackground()` chiude ma **conserva** il token; `onForeground()` riapre e l'URL precedente funziona ancora | Alta |
| TC-15 | Unit | `WEB_PORT` (9888) è distinta sia da `SYNC_PORT` (47700) sia da `SingleInstance.DEFAULT_PORT` (47653) | Alta |
| TC-16 | Manuale | Da un secondo dispositivo sulla rete reale: apertura, contenuto coincidente con l'app, aggiornamento all'arrivo di un promemoria nuovo | Alta |
| TC-17 | Manuale | Con dati fermi la pagina **non si ridisegna**: la posizione di scorrimento resta dov'era anche dopo molti cicli di controllo | Media |
| TC-18 | Manuale | Un promemoria che scade mentre la pagina è aperta cambia fascia cromatica **senza** una richiesta di rete | Media |
| TC-19 | Manuale | Rotazione dello schermo: il token non cambia e l'URL già aperto continua a funzionare | Alta |
| TC-20 | Manuale | Background/primo piano ripetuti: nessun ANR, nessun crash, porta che si chiude e riapre | Alta |
| TC-21 | Manuale | Spegnimento dell'interruttore con una pagina aperta: la pagina segnala l'irraggiungibilità | Media |
| TC-22 | Manuale | Cambio di rete a schermata aperta: l'indirizzo mostrato si aggiorna | Media |
| TC-23 | Manuale | Resa su Chrome e Firefox, desktop e mobile; tema scuro; larghezza 360 px; formato data `dd/MM/yyyy HH:mm` | Media |
| TC-24 | Manuale | Non-regressione: allarmi, sincronizzazione fra due dispositivi, export ODS | Alta |

### Definition of Done

- [ ] `./gradlew :shared:desktopTest :desktopApp:test` verde.
- [ ] TC-01…TC-15 automatizzati e passanti.
- [ ] TC-16…TC-24 spuntati a mano su dispositivo reale, con esito annotato.
- [ ] Nessun ANR e nessun crash nei log durante la sessione di verifica.
- [ ] Tutti i criteri di accettazione di §4 verificati.
- [ ] Nessuna dipendenza nuova nel catalogo delle versioni; APK non apprezzabilmente più grande.
- [ ] `CLAUDE.md` aggiornato — nuova area **e** correzione della riga sulle porte.
- [ ] Rilettura del codice del server con occhio alla sicurezza (parsing, percorsi, header): è
      codice esposto in rete e merita un passaggio dedicato, non solo i test.

---

## 8. Rischi e mitigazioni

| Rischio | Prob. | Impatto | Mitigazione |
|---|---|---|---|
| **R6 · Un server HTTP scritto a mano è codice di sicurezza**: parsing da rete, gestione percorsi, servizio di file | Media | Alto | Superficie ridotta al minimo (solo `GET`, nessun upload); **elenco chiuso** di asset invece di una mappatura sul filesystem, che rende il path traversal impossibile per costruzione; limiti espliciti; TC-01/TC-04; rilettura dedicata in DoD |
| **R12 · La porta è una trappola già scattata**: due porte fisse nascono in moduli diversi e nessuno le vede insieme. Quando coincisero, la sincronizzazione era muta e lo si scoprì solo su due dispositivi veri | Media | Alto | T-02: costante commentata e **due** test di distinzione nello stesso commit in cui nasce |
| **R14 · Asset non simmetrici fra i target**: `jvmSharedMain/resources` finisce nel jar desktop ma **non** nell'artefatto Android; il guasto si vedrebbe solo a runtime, pagina bianca | Media | Medio | T-03 come verifica bloccante, con ripiego `AssetManager` già individuato |
| **R7 · ANR da chiamate bloccanti**: precedente reale documentato in `SyncService` — successo al primo tentativo di associazione su un telefono vero, invisibile ai test JVM | Media | Alto | Tutto il lavoro di rete su `Dispatchers.IO`; nessun percorso che parta da `viewModelScope` senza `withContext`; TC-20 |
| **R13 · `AppSettings`/`AppContainer` sono punti di rottura**: 5 implementazioni e 2 chiamanti in tre moduli | Alta | Basso | T-01 come commit isolato; default inerte per `AppContainer` |
| **R15 · La rotazione ricrea la Activity**: token legato al suo ciclo di vita cambierebbe a ogni rotazione | Media | Medio | `WebService` costruito in `ReminderApp.onCreate()`; la Activity manda solo `onForeground`/`onBackground`; TC-19. Da valutare una breve tolleranza prima di chiudere il socket |
| **R2 · Il token nell'URL lascia tracce** in cronologia, log di proxy e `Referer` | Alta | Medio | `Referrer-Policy: no-referrer`, nessun link uscente, token rigenerato a ogni accensione. **Da rivedere quando arriverà la scrittura (D-07):** un token in query string che autorizza mutazioni è una cosa diversa da uno che autorizza letture |
| **R3 · Reti che isolano i client** (AP isolation, reti ospiti): la feature semplicemente non passa | Media | Medio | Non mitigabile tecnicamente. Il messaggio già scritto per `DiscoveryStatus.Unavailable` descrive lo stesso fenomeno e si può riusare, così l'utente non lo scambia per un difetto |
| **R18 · Fuso del browser ≠ fuso del telefono** (conseguenza di D-04): orari e fasce cromatiche calcolati sul fuso del client | Bassa | Medio | **Accettato consapevolmente.** Su rete locale i dispositivi condividono quasi sempre il fuso; in cambio le fasce si aggiornano da sole senza rete (US-005). Se emergesse, si aggiunge al payload il fuso del telefono senza cambiare il resto |
| **R1 · «Progressive» non è ottenibile su HTTP in rete locale** | Certa | Basso | Dichiarato apertamente in tutti e tre i documenti; la pagina non promette funzionamento offline |
| **R17 · Due server in ascolto** sul telefono con la schermata di sincronizzazione aperta — che con D-02 è **la stessa schermata** in cui si accende la web app, quindi il caso è la norma e non l'eccezione | Media | Basso | Scope dedicato per il servizio web; nessuno stato condiviso; R12 esclude il conflitto di porta; TC-16 va eseguito con la schermata aperta |
| **R9 · Porta occupata** da un'altra app | Bassa | Basso | Messaggio in italiano nello stato, come già fa `SyncService`; mai un'eccezione che risale |
| **R4/R5 · Indirizzo che cambia, interfaccia sbagliata** | Media | Basso | `siteAddress()` risolve già il problema, con il commento su perché la via ovvia è sbagliata; ricalcolo a ogni avvio |

**R16 (barra della lista già piena) è chiuso da D-02:** con la sezione dentro Sincronizzazione la
barra non si tocca.

---

## 9. Rollout e feature flag

**Strategia di rilascio:**
- [x] **Graduale con feature flag** — il flag *è* la feature: la porta esiste solo quando l'utente
      la accende.
- [ ] Deploy diretto
- [ ] Canary release

Non c'è un canale di distribuzione da orchestrare: l'APK si installa a mano sui dispositivi
dell'utente. La gradualità sta tutta nel fatto che, dopo l'aggiornamento, **l'app si comporta
esattamente come prima** finché l'interruttore resta spento.

**Feature flag:** `webEnabled` in `AppSettings` — **spento di default**, persistito nelle
`SharedPreferences` `"impostazioni"` accanto a `syncEnabled`. Finché è spento non si apre nessun
socket, non si genera nessun token e non si annuncia nulla: la stessa disciplina già adottata per
la sincronizzazione.

**Piano di rollback:**
1. **Spegnere l'interruttore** dall'app. La porta si chiude, il token si invalida, non resta
   niente in ascolto. È sufficiente nella quasi totalità dei casi ed è immediato.
2. Se il difetto fosse nell'avvio stesso del servizio: reinstallare l'APK precedente. **Non serve
   toccare il database** — lo schema resta alla v5 e non c'è nessuna migrazione da annullare, il
   che rende il rollback reversibile senza perdita di dati.
3. Se il difetto fosse nel refactoring di `AppSettings` (T-01): è un commit isolato e si revoca da
   solo, senza trascinare il resto.

**Ciò che il rollback non può annullare:** un token già finito nella cronologia del browser di un
altro dispositivo. Resta comunque inservibile, perché il token si invalida a ogni spegnimento.

---

## 10. Checklist di approvazione

Progetto personale a sviluppatore unico: i ruoli coincidono nella stessa persona. La tabella resta
perché i quattro cappelli vanno indossati comunque, e separarli aiuta a non saltarne uno.

| Revisione | Responsabile | Stato | Data |
|---|---|---|---|
| Decisioni D-01…D-07 chiuse | Alberto Goldoni | ✅ Fatto | 2026-08-21 |
| Revisione tecnica (architettura, breaking changes) | Alberto Goldoni | ⏳ In attesa | — |
| Revisione prodotto (scope, sola lettura come primo passo) | Alberto Goldoni | ⏳ In attesa | — |
| Stima approvata (11,00 gg, +3,00 sulla Fase 1) | Alberto Goldoni | ⏳ In attesa | — |
| Rischi accettati (in particolare R6, R2 e R18) | Alberto Goldoni | ⏳ In attesa | — |
| Data di inizio confermata | Alberto Goldoni | ⏳ In attesa | — |

---

## Domande residue

Le sette decisioni aperte della versione 1.0 sono chiuse (§3). Restano due punti che **non
bloccano l'inizio dei lavori** ma vanno tenuti d'occhio.

1. **D-06 è stata decisa in assenza di indicazione contraria:** token di 8 caratteri, soglia di 10
   tentativi falliti per indirizzo. Otto caratteri da un alfabeto senza ambiguità danno un numero
   di combinazioni che una soglia di 10 tentativi rende impraticabile da indovinare, e restano
   digitabili a mano. Se preferisci valori diversi si cambiano in una riga, ma meglio prima di
   T-06 che dopo.
2. **La scrittura cambierà il modello di sicurezza, non solo aggiungerà endpoint** (D-07). Un token
   in query string che autorizza *letture* è una cosa; uno che autorizza *mutazioni* è un'altra —
   entra in gioco la falsificazione di richieste da altri siti, e la strada usuale è un cookie di
   sessione più `POST`. Non è lavoro di questa iterazione, ma quando la si pianificherà va
   riaperto R2 invece di dare per buono ciò che basta oggi.

---

## Riferimenti

- [phase-1-requirements.md](phase-1-requirements.md) — obiettivo, scope, user stories, stima iniziale
- [phase-2-analysis.md](phase-2-analysis.md) — analisi su codice reale, file coinvolti, pattern, rischi
- [docs/features/001-desktop-linux/](../001-desktop-linux/) — la feature da cui provengono i pattern
  riusati qui (interfaccia in `commonMain` + implementazione in `jvmSharedMain`, esiti invece di
  eccezioni, socket su `Dispatchers.IO`, interruttore spento di default)

---

*Documento generato con la skill `claude-code-feature`.*
