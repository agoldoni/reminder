# Feature: Web app locale su Android — Analisi tecnica (Fase 2)

**Slug:** `web-app-android`
**Data:** 2026-08-21
**Stato:** Analisi su codice reale, in attesa di conferma
**Riferimento:** [phase-1-requirements.md](phase-1-requirements.md)

**Metodo.** Sono stati letti i sorgenti dei tre moduli (`:shared`, `:androidApp`, `:desktopApp`),
il catalogo delle versioni, i tre `build.gradle.kts` e i due manifest Android. La configurazione
dei source set Android è stata verificata eseguendo `./gradlew :shared:sourceSets`, non dedotta.
Ogni percorso e ogni riga citati qui sotto esistono; dove una cosa **non** esiste è detto
esplicitamente.

> **Aggiornamento 2026-08-21 — le decisioni aperte di questo documento sono state chiuse.**
> Porta **9888**; punto d'ingresso = **sezione dentro la schermata Sincronizzazione** (niente
> schermata nuova, niente rotta, niente quinta icona); asset dal **classloader**; date formattate
> **dal browser**; aggiornamento **solo quando qualcosa è cambiato** (richiesta condizionale con
> `304`); token di 8 caratteri con soglia di 10 tentativi; la sola lettura è **un primo passo**,
> non un traguardo. Il testo qui sotto è stato allineato dove avrebbe detto il contrario; il
> ragionamento completo sta in [phase-3-implementation-plan.md §3](phase-3-implementation-plan.md).

---

## Correzione preliminare — le porte in uso non sono quelle documentate

`CLAUDE.md` afferma che la sincronizzazione usa la porta fissa **47653**. Il codice dice altro:

| Costante | Valore | Dove | A cosa serve |
|---|---|---|---|
| `SYNC_PORT` | **47700** | [Discovery.kt:20](shared/src/commonMain/kotlin/it/agoldoni/reminder/sync/Discovery.kt#L20) | Server di sincronizzazione |
| `SingleInstance.DEFAULT_PORT` | **47653** | [SingleInstance.kt:45](desktopApp/src/main/kotlin/it/agoldoni/reminder/desktop/SingleInstance.kt#L45) | Istanza singola desktop |

Il commento su `Discovery.kt:16-18` racconta anche il perché: *«Non può coincidere con
`SingleInstance.DEFAULT_PORT` del modulo desktop … È già successo, con 47653.»* Le due porte
coincidevano, l'istanza singola teneva la sua per tutta la vita del processo e il server di
sincronizzazione non riusciva a legarsi — un guasto silenzioso, scoperto solo provando su due
dispositivi veri. La difesa lasciata a presidio è
[PorteTest.kt](desktopApp/src/test/kotlin/it/agoldoni/reminder/desktop/PorteTest.kt), che afferma
la disuguaglianza fra le due costanti.

**Conseguenze per questa feature:** (1) la porta della web app deve essere distinta da *entrambe*,
non da una sola; (2) `PorteTest` va esteso alla terza costante, altrimenti si ricrea esattamente
la trappola che quel test esiste per impedire; (3) la riga di `CLAUDE.md` va corretta — il §2 della
Fase 1 la cita in buona fede e va corretta anche lì.

---

## A. File coinvolti

### A.1 File nuovi

| Percorso | Perché lì |
|---|---|
| `shared/src/commonMain/kotlin/it/agoldoni/reminder/web/WebServerController.kt` | Interfaccia del comando + `WebStatus` + costante della porta. Sta in `commonMain` per la stessa ragione per cui ci sta `SyncController`: `AppContainer` e le schermate vivono qui, mentre socket e I/O stanno in `jvmSharedMain`. La costante della porta sta qui e non nel trasporto, come `SYNC_PORT`, perché la schermata deve poterla mostrare |
| `shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/HttpMessages.kt` | Modello di richiesta e risposta HTTP, parsing e serializzazione, con i limiti espliciti (lunghezza della riga di richiesta, numero e lunghezza degli header). Separato dal server perché è la parte interamente provabile senza socket |
| `shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/HttpServer.kt` | Ciclo di `accept()` su `Dispatchers.IO`, una connessione per volta servita e chiusa. Calco strutturale di `SyncServer` |
| `shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/AccessToken.kt` | Generazione del token, confronto a tempo costante, limitazione dei tentativi per indirizzo |
| `shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/StaticAssets.kt` | Caricamento degli asset con **elenco chiuso** di percorsi ammessi e tipi MIME. L'elenco chiuso è ciò che rende il path traversal impossibile per costruzione invece che per validazione |
| `shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/WebPayload.kt` | Modello `@Serializable` della risposta JSON |
| `shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/Router.kt` | Smistamento dei percorsi e applicazione del controllo d'accesso |
| `shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/WebService.kt` | Implementazione di `WebServerController`: interruttore, ciclo di vita, stato osservabile. Calco di `SyncService` |
| `shared/src/androidMain/resources/web/index.html`, `app.css`, `app.js`, `manifest.json`, `icona-192.png`, `icona-512.png` | Asset statici. Sulla scelta di `androidMain/resources` vedi §F.T1 |
| `shared/src/commonMain/kotlin/it/agoldoni/reminder/ui/web/WebViewModel.kt` | ViewModel della sezione. Separato da `SyncViewModel`, che resta il sottile passa-carte che è oggi: due ViewModel in una schermata sono leciti in Compose |
| `shared/src/commonMain/kotlin/it/agoldoni/reminder/ui/web/SezioneWebApp.kt` | Sezione innestata in `SyncScreen`: interruttore, indirizzo completo, messaggi. **Non** una schermata a sé (decisione D-02) |

### A.2 File da modificare

| Percorso | Modifica | Motivazione |
|---|---|---|
| [platform/AppSettings.kt](shared/src/commonMain/kotlin/it/agoldoni/reminder/platform/AppSettings.kt) | Due membri nuovi (`webEnabled`, `setWebEnabled`) | È il posto designato per le preferenze persistenti. **Rompe la compilazione di 5 implementazioni**: vedi §B.1 |
| [platform/AndroidPlatform.kt:59-76](shared/src/androidMain/kotlin/it/agoldoni/reminder/platform/AndroidPlatform.kt#L59-L76) | `AndroidAppSettings`: chiave nuova nelle stesse `SharedPreferences` `"impostazioni"` | Implementazione reale su Android |
| [platform/DesktopPlatform.kt:57](shared/src/desktopMain/kotlin/it/agoldoni/reminder/platform/DesktopPlatform.kt#L57) | `DesktopAppSettings`: stessa aggiunta | Deve compilare anche se il desktop non usa la feature |
| [di/AppContainer.kt](shared/src/commonMain/kotlin/it/agoldoni/reminder/di/AppContainer.kt) | Parametro `web: WebServerController` **con default inerte** + `initializer { WebViewModel(web) }` | Il default evita di toccare il desktop (§B.2) |
| [ReminderApp.kt:31-67](androidApp/src/main/kotlin/it/agoldoni/reminder/ReminderApp.kt#L31-L67) | Costruzione di `WebService` e passaggio al container | Unico punto in cui si compongono le implementazioni di piattaforma su Android |
| [ui/MainActivity.kt](androidApp/src/main/kotlin/it/agoldoni/reminder/ui/MainActivity.kt) | `onStart` → avvio, `onStop` → arresto | Oggi esiste solo `onStart` (riga 36), e serve alla sincronizzazione. È l'unica Activity dell'app |
| [ui/sync/SyncScreen.kt](shared/src/commonMain/kotlin/it/agoldoni/reminder/ui/sync/SyncScreen.kt) | Sezione nuova nella `LazyColumn`, accanto a «Dispositivi associati» e «Trovati sulla rete» | Decisione D-02. **Attenzione:** la sezione non deve avviare il server da un `DisposableEffect` come fa la schermata per `beginInteractive` — il server deve sopravvivere all'uscita dalla schermata |
| ~~`ui/navigation/NavGraph.kt`~~ | **Nessuna modifica** | D-02 elimina la rotta `web` |
| ~~`ui/list/EventListScreen.kt`~~ | **Nessuna modifica** | D-02 lascia intatta la barra della vista principale |
| [desktopApp/…/PorteTest.kt](desktopApp/src/test/kotlin/it/agoldoni/reminder/desktop/PorteTest.kt) | Terza costante nel confronto | Vedi la correzione preliminare |
| [CLAUDE.md](CLAUDE.md) | Nuova area `web/`; correzione della porta di sincronizzazione | La riga sulle porte è oggi sbagliata |

### A.3 File esplicitamente **non** toccati

- **Nessuna migrazione di schema.** Lo schema resta alla **v5**; `AppDatabase`, `EventEntity`,
  `EventDao` e `shared/schemas/` non cambiano. La feature è di sola lettura e le query che le
  servono esistono già.
- **`AndroidManifest.xml`**: `INTERNET` è già dichiarato in
  [shared/src/androidMain/AndroidManifest.xml:11](shared/src/androidMain/AndroidManifest.xml#L11)
  ed è tutto ciò che serve per legarsi a un socket in ascolto (verifica sul dispositivo in §F.T6).
  Nessun permesso nuovo, coerentemente con la decisione «solo primo piano».
- **`gradle/libs.versions.toml`**: nessuna dipendenza nuova. `kotlinx-serialization-json` è già
  fra le dipendenze di `commonMain` ([shared/build.gradle.kts:41](shared/build.gradle.kts#L41)).
- **`sync/`**: nessuna modifica. I due servizi convivono senza conoscersi.

---

## B. Contratti e interfacce da modificare

### B.1 `AppSettings` — **breaking change su 5 punti**

L'interfaccia ha oggi due soli membri. Aggiungerne di astratti rompe la compilazione di ogni
implementazione. Le implementazioni sono cinque, tre di produzione e due di prova:

| Implementazione | Percorso | Natura |
|---|---|---|
| `AndroidAppSettings` | [AndroidPlatform.kt:59](shared/src/androidMain/kotlin/it/agoldoni/reminder/platform/AndroidPlatform.kt#L59) | Produzione, `SharedPreferences` |
| `DesktopAppSettings` | [DesktopPlatform.kt:57](shared/src/desktopMain/kotlin/it/agoldoni/reminder/platform/DesktopPlatform.kt#L57) | Produzione, file di properties |
| `FakeSettings` | [SyncServiceTest.kt:22](shared/src/jvmSharedTest/kotlin/it/agoldoni/reminder/sync/SyncServiceTest.kt#L22) | Test |
| `SettingsInMemoria` | [DueIstanzeTest.kt:26](shared/src/desktopTest/kotlin/it/agoldoni/reminder/sync/DueIstanzeTest.kt#L26) | Test |
| *(eventuali nuove nei test della feature)* | — | Test |

**È un breaking change gestibile ma va fatto per primo e da solo** (§F.T3): mescolarlo alla logica
nuova produce un commit in cui non si distingue il refactoring meccanico dalla feature.

*Alternativa scartata:* membri con implementazione di default nell'interfaccia. Eviterebbe le
cinque modifiche, ma darebbe silenziosamente a `DesktopAppSettings` un flag non persistito, e in
questa codebase le preferenze persistono per contratto.

### B.2 `AppContainer` — parametro nuovo, con difesa

`AppContainer` ha due soli chiamanti:
[ReminderApp.kt:52](androidApp/src/main/kotlin/it/agoldoni/reminder/ReminderApp.kt#L52) e
[Main.kt](desktopApp/src/main/kotlin/it/agoldoni/reminder/desktop/Main.kt) (desktop).

Dichiarando il parametro **con un default inerte** in `commonMain` — un `object` che espone uno
stato spento e i cui metodi non fanno nulla — il desktop non va toccato affatto e resta vero ciò
che la Fase 1 ha messo fuori scope. È lo stesso espediente già usato per `navigationRequests` in
[AppRoot.kt:21](shared/src/commonMain/kotlin/it/agoldoni/reminder/platform/AppRoot.kt#L21) e in
[NavGraph.kt:19](shared/src/commonMain/kotlin/it/agoldoni/reminder/ui/navigation/NavGraph.kt#L19)
(`Flow<String> = emptyFlow()`).

### B.3 `WebServerController` — contratto nuovo (commonMain)

Modellato su `SyncController`, ma molto più piccolo: niente scoperta, niente peer, niente
associazione. Elementi che il contratto deve esporre, ciascuno con la sua ragione:

- **`status: StateFlow<WebStatus>`** — con `enabled`, `host`, `port`, `token`, `url` già composto
  e `lastMessage` in italiano. Che l'URL sia composto dal controller e non dalla schermata evita
  che due punti diversi lo compongano in modo diverso.
- **`supported: Boolean`** — serve a `EventListScreen`, che vive in `commonMain` e non sa su quale
  piattaforma sta girando: senza, il punto d'ingresso comparirebbe anche su desktop, dove la
  feature non è cablata.
- **`enable()` / `disable()`** — accendono e spengono l'interruttore persistente. `disable()` deve
  invalidare il token, non solo chiudere il socket (criterio di §4 US-2 della Fase 1).
- **`onForeground()` / `onBackground()`** — legano il socket al ciclo di vita **senza** toccare
  l'interruttore. La distinzione fra «spento dall'utente» e «sospeso perché l'app è in background»
  esiste già in `SyncService` fra `disable()` e `endInteractive()`
  ([SyncService.kt:106-119](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/sync/SyncService.kt#L106-L119))
  ed è lo stesso problema.

**Il token deve vivere nel servizio, non nella schermata né nella Activity.** Una rotazione dello
schermo distrugge e ricrea la Activity: un token legato al suo ciclo di vita cambierebbe a ogni
rotazione e l'URL già digitato sull'altro dispositivo smetterebbe di funzionare.

### B.4 Contratto con il browser (nuovo, verso l'esterno)

| Percorso | Risposta | Note |
|---|---|---|
| `GET /?t=<token>` | `text/html` | Pagina unica |
| `GET /app.css`, `/app.js`, `/manifest.json`, `/icona-*.png` | tipo statico | Elenco chiuso |
| `GET /api/eventi?t=<token>` | `application/json` | Alimenta l'aggiornamento periodico |
| qualunque altro percorso | `404` | |
| metodo ≠ `GET` | `405` | |
| token assente o errato | `403`, corpo vuoto | Indistinguibili fra loro |

Il modello JSON va tenuto **separato da `EventEntity`**, esattamente come `SyncEvent`
([SyncProtocol.kt:129-143](shared/src/commonMain/kotlin/it/agoldoni/reminder/sync/SyncProtocol.kt#L129-L143)):
*«l'id è locale e non ha senso altrove, e il formato di scambio non deve cambiare ogni volta che
cambia lo schema»*. Vale identico qui, con in più il fatto che `uuid` e `origin` non servono a una
vista e non hanno ragione di uscire dal dispositivo.

**Deciso (D-04): la formattazione la fa il browser.** Il JSON porta solo numeri; il client
formatta con `Intl.DateTimeFormat('it-IT')` per ottenere lo stesso `dd/MM/yyyy HH:mm` di
`formatDateTime`
([DateTime.jvm.kt:16](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/platform/DateTime.jvm.kt#L16)).
Il costo è che orari e fasce cromatiche seguono il fuso del client (§E.R18, accettato); il
beneficio è che la fascia cromatica si aggiorna al passare dell'ora **senza** una richiesta di
rete, che è esattamente ciò che D-05 vuole evitare.

`notificationMillis` resta però calcolato dal **server** e mandato come numero: così la formula
`dateTimeMillis - advanceMinutes * 60000` non viene duplicata in JavaScript. Si divide per bene il
lavoro — al server il calcolo, al client la resa.

**Deciso (D-07): la sola lettura è un primo passo.** Conseguenze immediate sul modello: la busta
porta un `versione` (stessa ragione di `protocolVersion` in `Hello`) e ogni voce porta `id`, che è
l'identificatore con cui una scrittura futura nominerà l'evento. Aggiungerlo dopo sarebbe una
rottura del formato; ora costa un campo. `uuid` e `origin` restano fuori.

### B.5 `EventDao` — nessuna modifica

Serve solo `getAllOpen()`
([EventDao.kt:55-56](shared/src/commonMain/kotlin/it/agoldoni/reminder/data/EventDao.kt#L55-L56)):
`suspend`, restituisce una lista, filtra già `deleted = 0 AND completed = 0` e ordina per
`dateTimeMillis ASC`. È esattamente la semantica della vista principale, in forma di istantanea —
che è la forma giusta per una richiesta HTTP. `getActiveSortedAsc()` restituisce un `Flow` e
servirebbe solo se si volesse spingere gli aggiornamenti al browser, cosa esclusa in Fase 1.

---

## C. Pattern da rispettare

**C.1 Interfaccia in `commonMain`, implementazione in `jvmSharedMain`.** Regola già applicata
due volte, con la motivazione scritta in
[SyncController.kt:23-27](shared/src/commonMain/kotlin/it/agoldoni/reminder/sync/SyncController.kt#L23-L27).
Il gruppo `jvmShared` è dichiarato con `applyDefaultHierarchyTemplate`
([shared/build.gradle.kts:21-28](shared/build.gradle.kts#L21-L28)); **il `dependsOn` manuale non è
ammesso**.

**C.2 I guasti diventano esiti, non eccezioni.** `SyncOutcome.Failed(reason)` porta un messaggio
già in italiano fino alla UI
([SyncTransport.kt:75-96](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/sync/SyncTransport.kt#L75-L96)).
Una porta occupata, qui, deve diventare un messaggio nello stato — come già fa `SyncService` in
[SyncService.kt:145-151](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/sync/SyncService.kt#L145-L151)
— non un'eccezione che risale.

**C.3 Le chiamate bloccanti stanno su `Dispatchers.IO`.** Il commento su
[SyncService.kt:174-181](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/sync/SyncService.kt#L174-L181)
è un verbale d'incidente: *«è successo al primo tentativo di associazione su un telefono vero, ed
è invisibile ai test JVM»*. Vale per ogni percorso che parta da `viewModelScope`.

**C.4 Il ciclo di `accept()` non muore per una connessione andata male.**
[SyncTransport.kt:133-144](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/sync/SyncTransport.kt#L133-L144):
`while (!socket.isClosed)`, `IOException` sull'`accept()` = uscita normale da `stop()`, ogni client
servito in una `launch` propria dentro un `try` che cattura tutto.

**C.5 `start(requestedPort = 0)` per i test.** Il commento su
[SyncTransport.kt:128](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/sync/SyncTransport.kt#L128)
lo dice: *«[requestedPort] a zero fa scegliere la porta al sistema: serve ai test»*. Va replicato,
altrimenti i test dipendono da una porta libera sulla macchina di build.

**C.6 Ci si dichiara con la porta effettiva.** `SyncServer.announced`
([SyncTransport.kt:121-126](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/sync/SyncTransport.kt#L121-L126))
usa `serverSocket?.localPort`, non quella richiesta. L'URL mostrato all'utente deve seguire la
stessa regola.

**C.7 Riuso di `Crypto.kt` per il token.** Esistono già, in `jvmSharedMain`:
`randomBytes(size)` ([Crypto.kt:111](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/sync/Crypto.kt#L111)),
`constantTimeEquals(a, b)` ([:97](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/sync/Crypto.kt#L97)),
`ByteArray.toHex()` ([:104](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/sync/Crypto.kt#L104)),
tutti su un `SecureRandom` condiviso ([:27](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/sync/Crypto.kt#L27)).
Il token si costruisce con questi tre. Scrivere un generatore nuovo, o confrontare con `==`,
sarebbe un regresso rispetto a codice già presente e già provato.

**C.8 L'indirizzo locale si chiede a `siteAddress()`.**
[LocalAddress.kt:22-34](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/sync/LocalAddress.kt#L22-L34),
già usato da `SyncService` per popolare `listeningHost`. Il commento spiega perché scandire le
interfacce non equivale: su una macchina con bridge la prima è `docker0`. È `internal` al modulo
`:shared`, quindi visibile dal nuovo package `web/` senza cambiarne la visibilità.

**C.9 Stato → ViewModel → schermata.** `SyncStatus` è un `data class` «direttamente mostrabile»;
`SyncViewModel` non fa che riesporre gli `StateFlow` del controller
([SyncViewModel.kt:32-35](shared/src/commonMain/kotlin/it/agoldoni/reminder/ui/sync/SyncViewModel.kt#L32-L35));
la schermata li raccoglie con `collectAsState()`. I ViewModel senza argomenti di navigazione si
registrano nel `viewModelFactory` di `AppContainer`
([AppContainer.kt:41-45](shared/src/commonMain/kotlin/it/agoldoni/reminder/di/AppContainer.kt#L41-L45)).

**C.10 Ciclo di vita della schermata con `DisposableEffect`.**
[SyncScreen.kt:75-78](shared/src/commonMain/kotlin/it/agoldoni/reminder/ui/sync/SyncScreen.kt#L75-L78):
`DisposableEffect(Unit) { viewModel.apriSchermata(); onDispose { viewModel.chiudiSchermata() } }`.
Attenzione: qui **non** va usato per avviare il server — il server deve restare vivo anche quando
l'utente torna alla lista, altrimenti la pagina web muore appena si esce dalla schermata delle
impostazioni. È l'`Activity`, non la schermata, il confine giusto.

**C.11 Lingua e stile.** Tutto in italiano: identificatori nuovi, messaggi, nomi dei test in
backtick (`` `l'istanza singola e la sincronizzazione non usano la stessa porta` ``). I commenti
spiegano **perché**, non cosa; quelli lunghi documentano una decisione o un incidente. Convenzione
osservabile ovunque nel modulo `sync/`.

**C.12 Icone da `material-icons-core`.** `material-icons-extended` è escluso per peso (37 MB) e le
icone mancanti sono ridefinite a mano in
[ui/icons/](shared/src/commonMain/kotlin/it/agoldoni/reminder/ui/icons/) (`Restore`, `Sync`). Se
l'icona scelta per la web app non è in `core`, si segue quella strada (§F.T5).

---

## D. Test da creare o aggiornare

Dove girano i test, per come è configurato il progetto: `jvmSharedTest` e `commonTest` vengono
eseguiti da `./gradlew :shared:desktopTest`; `desktopApp/src/test` da `:desktopApp:test`; gli
strumentati richiedono un emulatore. `FakeEventDao`
([commonTest](shared/src/commonTest/kotlin/it/agoldoni/reminder/data/FakeEventDao.kt)) è visibile
da `jvmSharedTest` — `SyncServiceTest` già lo importa — e implementa `getAllOpen()` con la stessa
semantica della query reale.

### D.1 Nuovi — `shared/src/jvmSharedTest/kotlin/it/agoldoni/reminder/web/`

| File | Tipo | Che cosa afferma |
|---|---|---|
| `HttpMessagesTest.kt` | unitario | Riga di richiesta ben formata; metodo, percorso e query estratti; richiesta troncata; riga oltre il limite; header oltre il limite; header senza `:`; byte non ASCII; richiesta vuota. Nessuno di questi casi deve lanciare fuori dal parser |
| `RouterTest.kt` | unitario | Ogni percorso dell'elenco chiuso risponde `200` col tipo giusto; percorso sconosciuto `404`; `POST`/`PUT`/`DELETE` `405`; `../`, `..%2f`, `%2e%2e/`, percorsi assoluti e doppie barre **non** escono dall'elenco; header di sicurezza presenti su ogni risposta |
| `AccessTokenTest.kt` | unitario | Token assente e token errato producono risposte **identiche**; il token cambia a ogni accensione; superata la soglia i tentativi vengono respinti; il confronto passa da `constantTimeEquals` |
| `WebPayloadTest.kt` | unitario | Il JSON contiene i soli promemoria aperti, nell'ordine di `dateTimeMillis`; niente completati, niente tombstone; `advanceMinutes` produce l'orario di notifica atteso; `uuid` e `origin` **non** compaiono; descrizione nulla serializzata senza rompere il client |
| `HttpServerTest.kt` | integrazione su socket | Server su porta 0; giro completo con un client reale; una connessione chiusa a metà non abbatte il ciclo; `stop()` libera davvero la porta; cento cicli avvio/arresto non lasciano socket appesi |
| `WebServiceTest.kt` | integrazione | A interruttore spento nessun socket (calco di `SyncServiceTest`, che afferma la stessa cosa per la sincronizzazione); `enable()` apre e pubblica host, porta e token; `disable()` chiude **e invalida il token**; `onBackground()` chiude ma **conserva** il token; `onForeground()` riapre e l'URL precedente funziona ancora |

### D.2 Nuovi — `shared/src/commonTest/kotlin/it/agoldoni/reminder/web/`

| File | Che cosa afferma |
|---|---|
| `PorteWebTest.kt` | `WEB_PORT != SYNC_PORT`. Sta in `commonTest` perché entrambe le costanti sono in `commonMain` |

### D.3 Da aggiornare

| File | Modifica |
|---|---|
| [PorteTest.kt](desktopApp/src/test/kotlin/it/agoldoni/reminder/desktop/PorteTest.kt) | Terza costante: `WEB_PORT` ≠ `SingleInstance.DEFAULT_PORT`. È il modulo `:desktopApp` l'unico che vede tutte e tre |
| [SyncServiceTest.kt:22](shared/src/jvmSharedTest/kotlin/it/agoldoni/reminder/sync/SyncServiceTest.kt#L22) | `FakeSettings` implementa i membri nuovi |
| [DueIstanzeTest.kt:26](shared/src/desktopTest/kotlin/it/agoldoni/reminder/sync/DueIstanzeTest.kt#L26) | `SettingsInMemoria` idem |

### D.4 Verifiche manuali (non automatizzabili)

Il ciclo di vita legato al primo piano, la resa della pagina su browser reali e la raggiungibilità
attraverso una rete vera restano manuali. Due strade, e servono entrambe:

- **`adb forward tcp:<porta> tcp:<porta>`** — prova dall'host senza dipendere dalla topologia
  della rete; isola il server dal problema della raggiungibilità.
- **Secondo dispositivo sulla stessa rete Wi-Fi** — l'unica prova che dice qualcosa su §E.R3.

Da spuntare a mano: rotazione dello schermo (il token non deve cambiare), background/primo piano
ripetuti, spegnimento dell'interruttore con una pagina aperta, cambio di rete a schermata aperta.

---

## E. Rischi tecnici aggiornati

Rispetto alla Fase 1, con quel che si è visto nel codice.

| # | Rischio | Variazione | Evidenza |
|---|---|---|---|
| **R1** | Niente PWA piena su HTTP | invariato | Vincolo dei browser, non della codebase |
| **R2** | Token nell'URL lascia tracce | **ridotto** | `randomBytes`/`constantTimeEquals`/`toHex` già esistono e sono già provati ([CryptoTest.kt](shared/src/jvmSharedTest/kotlin/it/agoldoni/reminder/sync/CryptoTest.kt)) |
| **R3** | Reti che isolano i client | invariato, **già modellato** | `DiscoveryStatus.Unavailable` ([Discovery.kt:58-63](shared/src/commonMain/kotlin/it/agoldoni/reminder/sync/Discovery.kt#L58-L63)) descrive lo stesso fenomeno con un messaggio già scritto in italiano: il testo si può riusare |
| **R4** | L'IP cambia | **ridotto** | `SyncService` ricalcola `listeningHost` a ogni avvio: stessa struttura |
| **R5** | Quale interfaccia annunciare | **ridotto** | `siteAddress()` risolve già il problema e porta il commento che spiega perché la via ovvia è sbagliata |
| **R6** | Server HTTP scritto a mano = codice di sicurezza | invariato, **mitigabile per costruzione** | L'elenco chiuso di asset (§A.1) rende il path traversal impossibile invece che filtrato. Restano parsing e limiti, coperti da `HttpMessagesTest` |
| **R7** | ANR da chiamate bloccanti | invariato, **precedente reale** | [SyncService.kt:174-181](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/sync/SyncService.kt#L174-L181) |
| **R8** | Concorrenza sul database | **quasi nullo** | Sole letture via `getAllOpen()`; Room serializza |
| **R9** | Porta occupata | invariato | Struttura di gestione già pronta in `SyncService` |
| **R10** | Verificabilità | **ridotto** | `adb forward` + porta 0 nei test: il grosso è provabile senza dispositivo |
| **R11** | Dipendenze nuove | **nullo** | Verificato: nessuna serve |

### Rischi nuovi, emersi dal codice

**R12 — Il numero di porta è una trappola già scattata una volta.** Vedi la correzione
preliminare: due porte fisse nascono in moduli diversi, nessuno le vede insieme, e quando hanno
coinciso il guasto è stato silenzioso. Con la terza il rischio cresce.
*Mitigazione:* scegliere la porta accanto a `SYNC_PORT` in `Discovery.kt` o in un file che le
raccolga, con il commento sul perché, ed estendere `PorteTest` **nello stesso commit**.

**R13 — `AppSettings` e `AppContainer` sono punti di rottura.** Cinque implementazioni e due
chiamanti, in tutti e tre i moduli. *Mitigazione:* refactoring meccanico in un commit isolato
(§F.T3) e default inerte per `AppContainer` (§B.2).

**R14 — La collocazione degli asset non è simmetrica fra i target.** `./gradlew :shared:sourceSets`
mostra per il source set `main`:
`Java-style resources: [shared/src/main/resources, shared/src/androidMain/resources]` e
`Assets: [shared/src/main/assets, shared/src/androidMain/assets]`. **`shared/src/jvmSharedMain/resources`
non compare**: gli asset messi lì finirebbero nel jar desktop ma non nell'artefatto Android — e il
guasto si vedrebbe solo a runtime, con una pagina bianca. *Mitigazione:* asset in
`shared/src/androidMain/resources/web/`, caricamento via classloader dal codice in `jvmSharedMain`
(funziona qualunque source set li abbia forniti); per il futuro riuso desktop basterà una `srcDir`
in più. Prova a runtime obbligatoria: §F.T1.

**R15 — La rotazione dello schermo ricrea la Activity.** Ogni cambio di configurazione distrugge e
ricrea `MainActivity`, quindi `onStop`/`onStart` scattano. Se token o socket fossero legati alla
Activity, ruotare il telefono invaliderebbe l'URL già digitato sull'altro dispositivo.
*Mitigazione:* `WebService` è costruito in `ReminderApp.onCreate()` e vive quanto il processo,
come `SyncService`; la Activity gli manda solo `onForeground()`/`onBackground()`. Da verificare a
mano (§D.4). Da valutare una breve tolleranza prima di chiudere il socket, così una rotazione non
interrompe una richiesta in corso.

**R16 — La barra della vista principale è già piena.** *(chiuso da D-02: la sezione va dentro la
schermata Sincronizzazione e la barra non si tocca. Il paragrafo resta come traccia del perché.)*
[EventListScreen.kt:177-197](shared/src/commonMain/kotlin/it/agoldoni/reminder/ui/list/EventListScreen.kt#L177-L197)
ospita quattro `IconButton` (Esporta, Fatti, Sincronizzazione, Info) più il titolo. Una quinta
icona su uno schermo stretto stringe il titolo o esce.
*Mitigazione:* fra le opzioni — voce dentro la schermata di sincronizzazione (che è già «i
dispositivi e la rete»), menù a tendina che raccoglie le azioni secondarie, o accettare la quinta
icona. Decisione in §F.T2.

**R17 — Due server in ascolto contemporaneamente sul telefono.** Con la schermata di
sincronizzazione aperta, `SyncService` apre il suo socket (`beginInteractive`); con la web app
accesa, se ne apre un secondo. Non c'è conflitto di porta — se R12 è rispettato — ma sono due cicli
di `accept()` e due scope. **Con D-02 il caso diventa la norma e non l'eccezione**, perché l'interruttore
della web app si trova proprio su quella schermata. *Mitigazione:* scope dedicato per il servizio
web, come già fa `ReminderApp` per la sincronizzazione
([ReminderApp.kt:40](androidApp/src/main/kotlin/it/agoldoni/reminder/ReminderApp.kt#L40)); nessuna
condivisione di stato fra i due servizi; la verifica sul campo va fatta con la schermata aperta.

**R18 — Il fuso orario del browser non è quello del telefono.** Con la formattazione lato client,
un PC in un altro fuso mostra orari diversi da quelli dell'app, e il criterio «la lista coincide
riga per riga» cade in modo subdolo. *Esito:* **accettato consapevolmente con D-04.** Su rete
locale i due dispositivi condividono quasi sempre il fuso, e in cambio si ottiene
l'aggiornamento della fascia cromatica senza rete. Se emergesse davvero, si aggiunge al payload il
fuso del telefono senza toccare nient'altro.

---

## F. Prerequisiti e task bloccanti

### Bloccanti — da chiudere prima di scrivere la logica

**T1 — Provare a runtime che gli asset arrivano nell'APK.** *(blocca M4)*
La configurazione dice che `shared/src/androidMain/resources` è raccolto fra le Java-style
resources, ma nessun file ci sta oggi — l'unica `resources/` esistente nel progetto è
`desktopApp/src/main/resources`. Serve la prova che `ClassLoader.getResourceAsStream("web/…")`
funzioni **dentro l'APK** e non solo nei test JVM: un file di prova, un test strumentato o una
lettura in `ReminderApp.onCreate()` su dispositivo. Se non funzionasse, il ripiego è
`shared/src/androidMain/assets/` + `AssetManager`, che però richiede un `Context` e quindi
un'interfaccia di caricamento in `commonMain` con due implementazioni — più lavoro, da sapere
prima e non a metà di M4.

**T2 — ~~Decidere il punto d'ingresso nell'interfaccia.~~** ✅ **Chiuso (D-02):** sezione dentro la
schermata Sincronizzazione. Niente schermata nuova, niente rotta, niente quinta icona.

**T3 — Estendere `AppSettings` in un commit isolato.** *(blocca tutto il resto)*
Cinque implementazioni, nessuna logica nuova: dev'essere un commit che si legge in un minuto e in
cui la verifica è «compila e i test passano».

**T4 — Estendere `PorteTest`.** *(blocca M1)*
✅ Il valore è deciso (D-01): **9888**, lontano da 47653 e 47700. Resta da fare la parte che conta:
commentare la costante con il perché e coprirla con i **due** test di distinzione **nello stesso
commit** in cui nasce. Vedi R12 — è il presidio che esiste perché la trappola è già scattata.

### Non bloccanti, ma da chiarire prima della Fase 3

**T5 — Icona.** Verificare se l'icona scelta è in `material-icons-core`; se no, ridefinirla in
`ui/icons/` come già fatto per `Restore` e `Sync`.

**T6 — Permessi su Android recente.** Verificare sul dispositivo di prova che legarsi a un
`ServerSocket` con l'app in primo piano non richieda nulla oltre a `INTERNET`. Atteso: nulla. Va
verificato e non dato per scontato, perché è il genere di cosa che cambia fra versioni di Android.

**T7 — ~~Formattazione delle date.~~** ✅ **Chiuso (D-04):** lato browser. Vedi §B.4 per ciò che
comporta nel modello JSON.

**T8 — ~~Intervallo di aggiornamento e comportamento a scheda nascosta.~~** ✅ **Chiuso (D-05):**
si aggiorna **solo quando qualcosa è cambiato**. Richiesta condizionale con impronta del contenuto
e `304`; controllo ogni 30 s, sospeso a scheda nascosta e ripreso subito al ritorno.
**Un dettaglio che sembra una contraddizione e non lo è:** `Cache-Control: no-store` impedisce alla
cache del browser di rivalidare da sé, ma l'impronta la tiene il nostro JavaScript in una
variabile e manda `If-None-Match` esplicitamente — funziona lo stesso, e i dati non finiscono su
disco. **L'impronta si calcola sul corpo della risposta**, non su `max(updatedAt)`: completare un
promemoria lo toglie dall'insieme degli aperti e quel massimo può *scendere*.

**T9 — Correggere `CLAUDE.md` sulle porte.** Indipendente da questa feature: la riga è sbagliata
adesso e chiunque la legga sbaglia. Da fare subito, anche se la feature non partisse.

### Non prerequisiti — verificato che non servono

- Nessuna migrazione di database; lo schema resta alla v5.
- Nessun permesso nuovo nei manifest.
- Nessuna dipendenza nuova nel catalogo.
- Nessuna modifica al modulo `:desktopApp`, se si adotta il default inerte di §B.2.
- Nessun refactoring del modulo `sync/`.
