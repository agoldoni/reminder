# Analisi tecnica della codebase (Fase 2)

**Feature:** `005-aggiornamento-mirato-web-app`
**Data:** 2026-08-23
**Base:** [phase-1-requirements.md](phase-1-requirements.md)
**Metodo:** lettura diretta dei sorgenti. Ogni percorso e ogni nome citati qui sono stati verificati;
dove una cosa **non** esiste, è detto esplicitamente.

---

## Riepilogo di ciò che l'analisi ha cambiato rispetto alla Fase 1

Tre riscontri, in ordine di importanza.

1. **L'architettura regge il long-poll senza modifiche strutturali.** `gestisci` è già
   `suspend`, ogni connessione ha già la sua coroutine, e `Router.eventi()` è già il punto unico in
   cui si decide fra `200` e `304`. Il lavoro lato telefono è più piccolo di quanto la Fase 1
   stimasse: **un ramo dentro una funzione che c'è già**, più una classe da ~40 righe.
2. **`InvalidationTracker.createFlow` esiste ed è pubblica in Room 2.8.4** (verificata nel sorgente
   dell'artefatto, `commonMain/androidx/room/InvalidationTracker.kt:90`). Il segnale unico chiesto
   da US-6 non va costruito: va collegato.
3. **C'è un difetto latente in `app.js` che il ridisegno totale sta nascondendo, e
   l'aggiornamento mirato lo farebbe emergere.** I gestori dei pulsanti catturano l'evento in una
   *closure*; riusare una scheda senza ricollegarli manderebbe un `attesoUpdatedAt` vecchio, cioè
   un `409` che l'utente non capirebbe. È **R11**, ed è la ragione per cui il lavoro su `app.js`
   non è «un diff invece di un rebuild». Vedi §E.

La stima della Fase 1 (≈ 6 giorni) resta valida ma si **sposta**: meno backend, più frontend.

---

## A. File coinvolti

### A.1 — Nuovi

| Percorso | Perché |
|---|---|
| `shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/Cambiamenti.kt` | La sorgente unica del segnale (US-6): un contatore monotono alimentato da un `Flow`. Sta in `jvmSharedMain` accanto al resto della web app, non in `commonMain`, perché nessuno fuori di qui lo usa |
| `shared/src/jvmSharedTest/kotlin/it/agoldoni/reminder/web/AttesaLungaTest.kt` | I casi dell'attesa: sveglia, scadenza, etag già diverso, tetto superato, client vecchio |
| `docs/features/005-aggiornamento-mirato-web-app/status.md` | Come le 001-004: stato, verifiche fatte, checklist manuale del browser (vedi §D.4) |

### A.2 — Modificati, lato telefono

| Percorso | Modifica | Motivazione |
|---|---|---|
| `shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/Router.kt` | Ramo di attesa dentro `eventi()` (righe 77-86 attuali); nuovo parametro di costruzione | È **già** il punto unico che confronta `if-none-match` con `corpo.etag`. L'attesa è la stessa decisione, presa più tardi invece che subito |
| `shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/WebService.kt` | Nuovo parametro `cambiamenti: Flow<*>`; costruisce `Cambiamenti` e lo passa a `Router` | È il punto di composizione della web app: costruisce già `Router`, `ScrittureWeb`, `AccessToken`, `CertificateStore`, `HttpServer` |
| `androidApp/src/main/kotlin/it/agoldoni/reminder/ReminderApp.kt` | Alla riga 66 passa il flusso di invalidazione | È l'unico posto che ha in mano `database`, non solo `database.eventDao()` |
| `shared/src/jvmSharedTest/kotlin/it/agoldoni/reminder/web/WebServiceTest.kt` | Nessuna, **se** il nuovo parametro ha un default inerte | Tre siti di costruzione (righe 70, 209, 292). Vedi §B.3 |
| `shared/src/jvmSharedTest/kotlin/it/agoldoni/reminder/web/RouterTest.kt` | Nessuna, per la stessa ragione; casi nuovi vanno in `AttesaLungaTest` | Costruisce `Router` alla riga 44 |

**Non toccati, e vale la pena dirlo:** `HttpMessages.kt`, `HttpServer.kt`, `ScrittureWeb.kt`,
`WebPayload.kt`, `AccessToken.kt`, `StaticAssets.kt`, `TlsIdentity.kt`, `CertificateStore.kt`,
`EventDao.kt`, `AppDatabase.kt`, il manifest, `WebServerService.kt`. Il trasporto, la
crittografia, il formato e il database restano **esattamente** come sono. È la misura di quanto
bene la 002 avesse scelto la struttura.

### A.3 — Modificati, lato browser

| Percorso | Modifica | Peso |
|---|---|---|
| `shared/src/webAssets/web/app.js` | **Il grosso del lavoro.** Riconciliazione per chiave, aggiornamento dei colori senza ricostruzione, ritmo nuovo con `AbortController` e backoff, dati legati al nodo invece che alla closure | ~150 righe cambiate su 461 |
| `shared/src/webAssets/web/index.html` | Solo il commento di 8 righe che precede `<dialog id="modulo">` | Quel commento **dichiara** che `disegna()` azzera l'elenco. Se la cosa smette di essere vera e il commento resta, il file mente |
| `shared/src/webAssets/web/app.css` | **Nessuna** prevista | I colori sono già in classi (`.evento.scaduto`, …): cambiare fascia è cambiare `className`, e il CSS non se ne accorge |

### A.4 — Documentazione

`CLAUDE.md`, paragrafo `web/`: contiene oggi la frase *«`disegna()` azzera l'elenco e lo
ricostruisce, e gira ogni 30 s per i dati e ogni 60 s per i colori»*, che è la motivazione scritta
di due scelte (il `<dialog>` fuori da `#elenco`, la sospensione del ridisegno). Con questa feature
la premessa cambia ma **le due scelte restano**, per ragioni diverse: vanno riscritte, non
cancellate.

---

## B. Contratti e interfacce da modificare

### B.1 — Il contratto HTTP

| | Oggi | Dopo |
|---|---|---|
| Percorso | `GET /api/eventi?t=<token>` | invariato |
| Parametro nuovo | — | `&attendi=1` (facoltativo) |
| Codici | `200`, `304`, `403` | **gli stessi** |
| Corpo | `WebPayload` v2 | **identico** |
| `ETag` | impronta SHA-256 del corpo | **identica** |

**Nessuna rottura, e nessun bump di `WEB_PAYLOAD_VERSION`** (resta 2, `WebPayload.kt:18`): il
formato non cambia di un byte. Un client vecchio non manda `attendi`, riceve la risposta immediata
di sempre; un client nuovo contro un server vecchio manda un parametro che quel server ignora, e
ricade sul polling. Le due direzioni degradano entrambe verso il comportamento di oggi.

**Il timeout lo decide il server, non il client.** `attendi` è un interruttore, non una durata. Un
client che potesse chiedere «aspetta dieci minuti» potrebbe inchiodare una coroutine e un socket
per dieci minuti, e ne bastano pochi per esaurire il tetto di §B.4. È una scelta di robustezza,
non di stile.

### B.2 — Il contratto della decisione, dentro `Router.eventi()`

Oggi (`Router.kt:77-86`):

```kotlin
val corpo = rappresentazione(accesso)
if (request.header("if-none-match") == corpo.etag) return HttpResponse.vuota(304, …)
return HttpResponse(200, …)
```

Dopo, la stessa decisione in un ciclo con una scadenza. **L'ordine delle due prime righe è il
cuore della correttezza** e va scritto con il suo perché:

```kotlin
// Si legge il contatore PRIMA di comporre il corpo. Al contrario, una scrittura che cadesse
// fra la composizione e la sottoscrizione non sveglierebbe nessuno: il client resterebbe
// appeso fino alla scadenza con in mano un dato già vecchio. È il difetto che si
// manifesterebbe come «ogni tanto la pagina è in ritardo di venticinque secondi», cioè nel
// modo più difficile da riprodurre.
var visto = cambiamenti.versione
var corpo = rappresentazione(accesso)
```

Poi: se l'impronta è già diversa → `200` subito, senza aspettare (il client si era perso un giro).
Se è uguale e non si aspetta → `304`, come oggi. Se è uguale e si aspetta → si attende che il
contatore superi `visto`, si **ricompone** il corpo e si ricontrolla, finché non cambia davvero o
finché non scade la deadline.

Il ricontrollo non è pignoleria: una scrittura su `events` che non tocca i promemoria aperti —
completare un evento già completato, un tombstone che arriva dalla sincronizzazione, un
`SyncEngine` che riscrive una riga identica — **muove il contatore senza cambiare l'impronta**.
Rispondere `200` su quel segnale manderebbe il browser a ridisegnare per niente, e in un ciclo
stretto.

### B.3 — Le firme Kotlin

```kotlin
// Cambiamenti.kt (nuovo)
internal class Cambiamenti(sorgente: Flow<*>, scope: CoroutineScope) {
    val versione: Long                       // monotono, parte da 0
    suspend fun attendiOltre(visto: Long)    // sospende finché versione > visto
}

// Router.kt — parametro aggiunto in coda, con default inerte
internal class Router(
    private val scritture: ScrittureWeb,
    private val token: AccessToken,
    private val dao: EventDao,
    private val cambiamenti: Cambiamenti = Cambiamenti.fermo   // non si sveglia mai
)

// WebService.kt — parametro aggiunto, con default inerte
class WebService(
    …,
    private val cambiamenti: Flow<*> = emptyFlow(),
    …
)
```

**I default inerti non sono comodità: sono ciò che tiene i 35 test esistenti di `RouterTest` e i 3
siti di costruzione di `WebServiceTest` compilabili senza toccarli.** Un test che non cambia è un
test che continua a dire la stessa cosa di prima, e in una feature che riscrive il percorso di
lettura è esattamente la garanzia che serve.

Il default inerte non nasconde un errore di cablaggio: senza segnale l'attesa **scade** e risponde
`304`, cioè la pagina torna al ritmo di oggi. Il presidio contro il cablaggio dimenticato è il caso
end-to-end di §D.2, non il tipo.

### B.4 — Il tetto delle attese

Un contatore atomico dentro `Router` (o dentro `Cambiamenti`), incrementato all'ingresso
dell'attesa e decrementato in un `finally`. Superato il tetto, si risponde **subito** come se
`attendi` non fosse stato chiesto: il client riceve un `304` immediato, ripiega da sé sul polling,
e non si accorge di niente se non che è tornato lento.

**Numero proposto: 8.** Il ragionamento, perché il numero da solo non vale niente: gli utenti di
questa porta sono le persone di una casa, e ciascuna ha al più due o tre schede aperte. Otto copre
il caso reale con margine, e otto coroutine sospese su `Dispatchers.IO` più otto socket TLS sono un
costo trascurabile perfino su un telefono. Alzarlo a trenta non regalerebbe niente a nessuno se non
a chi apre trenta connessioni di proposito.

### B.5 — Contratti che **non** cambiano

- **Database:** nessuna migrazione, nessuna query nuova. `EventDao` resta a 15 metodi.
- **`corpoEventi`** resta l'unico posto che costruisce la rappresentazione (`WebPayload.kt:103`).
- **`ScrittureWeb`** resta l'unico punto che tocca database e allarmi insieme.
- **`AccessToken.verifica`**: la soglia dei 10 tentativi al minuto per indirizzo non va toccata.
  Una richiesta in attesa è **una** richiesta, quindi non consuma più tentativi di una normale.
- **Porte:** `WEB_PORT` = 9888 invariata; `PorteWebTest` e `PorteTest` restano validi così come sono.

### B.6 — Il contratto interno del browser, che cambia davvero

Oggi il legame fra una scheda e i suoi dati è la **closure** (`app.js:141-142`):

```js
comandi.appendChild(bottone('Fatto', false, function () { completa(evento); }));
comandi.appendChild(bottone('Modifica', true, function () { apriModulo(evento); }));
```

Funziona perché ogni scheda vive meno di un aggiornamento: al giro dopo il nodo non c'è più, e con
lui la closure. Riusando il nodo, la closure sopravvive ai dati. Il contratto nuovo è: **i dati
vivono sul nodo** (una proprietà, `card._evento`), e i gestori li leggono al momento del click.
È R11, ed è la modifica più importante di tutto `app.js`.

---

## C. Pattern da rispettare

### C.1 — Kotlin

- **Un guasto è un esito, non un'eccezione da far risalire.** `RichiestaLetta`, `EsitoScrittura`,
  `SyncOutcome`: qui il tetto superato e la scadenza sono **risposte normali** (`304`), non errori.
- **`jvmSharedMain` quando basta `java.*`, `commonMain` quando serve a `commonMain`.**
  `Cambiamenti` prende un `Flow<*>`, che è multipiattaforma, ma non serve a nessuno fuori dalla web
  app: sta con lei, come `ScrittureWeb`.
- **Il chiamante porta il tempo.** `ScrittureWeb`, `WebService` e `AccessToken` ricevono tutti
  `now: () -> Long`. La durata dell'attesa dev'essere un parametro con un valore predefinito, non
  una costante letta dentro: senza, i test aspettano davvero venticinque secondi.
- **Costanti in cima al file, con il KDoc che dice perché quel valore.** Modello: `MAX_CORPO` in
  `HttpMessages.kt:47`, `READ_TIMEOUT_MILLIS` in `HttpServer.kt:14`.
- **Nessuna dipendenza nuova.** Nessuna serve, e la linea del progetto (ODS, HKDF, DER scritti a
  mano) non va incrinata per una comodità.
- **Test in italiano fra backtick**, `runBlocking` per il codice sospeso semplice (`RouterTest`),
  `runTest` quando serve tempo virtuale (`ScrittureWebTest`, `ScritturaCondizionataTest`), **porta 0**
  quando c'è un socket vero (`HttpServerTest`, `WebServiceTest`).

### C.2 — JavaScript

- **ES5 nello stile: `var`, `function`, nessun passo di build.** Il file è servito com'è dal
  classloader. `AbortController` e `<dialog>` sono API di runtime, non sintassi: non richiedono
  transpilazione e sono già date per buone (il `<dialog>` è in uso dalla 004).
- **`textContent`, mai `innerHTML`.** Il commento in `disegna()` lo motiva e vale ancora: nella
  riconciliazione ci saranno *più* punti che scrivono testo, quindi la regola diventa più
  importante, non meno.
- **`hidden` più `[hidden] { display: none !important }`.** Quattro elementi ci si reggono
  (`vuoto`, `avviso`, `nuovo`, `annulla-barra`). Nessuno va toccato, ma nessuno va nemmeno
  "ottimizzato" via.
- **I commenti dicono il perché e vanno corretti quando il perché cambia.** Il blocco di
  intestazione di `app.js` («la pagina fa tre cose separate, e tenerle separate è il punto») è la
  mappa del file: dopo questa feature le tre cose restano tre, ma la prima cambia natura.

### C.3 — Accessibilità: un effetto collaterale da conoscere

`<main id="elenco" aria-live="polite">` (`index.html:25`). Oggi ogni aggiornamento svuota e
ricostruisce quella regione, e un lettore di schermo **rilegge l'intero elenco**. Con la
riconciliazione leggerà solo il nodo cambiato. È un miglioramento, ma è un **cambio di
comportamento** e come tale va verificato, non dato per scontato.

---

## D. Test da creare o aggiornare

### D.1 — `CambiamentiTest` (nuovo, `jvmSharedTest`)

- il contatore **non** parte da un'emissione iniziale (`emitInitialState = false`);
- ogni emissione della sorgente lo fa salire;
- `attendiOltre(visto)` ritorna subito se il contatore è **già** oltre — è il caso che chiude la
  finestra di corsa di §B.2, e va provato con un ordine deliberatamente sfavorevole;
- più attese contemporanee si svegliano tutte con una sola emissione.

### D.2 — `AttesaLungaTest` (nuovo, `jvmSharedTest`)

Sul `Router`, con `FakeEventDao` — che va bene perché `getActiveSortedAsc()` è un
`MutableStateFlow.map` (`FakeEventDao.kt:15`) e quindi emette a ogni scrittura, esattamente come
farà l'`InvalidationTracker`.

| Caso | Atteso |
|---|---|
| `attendi=1` con `If-None-Match` **diverso** | `200` subito, senza aspettare |
| `attendi=1` con impronta uguale, poi una scrittura sul DAO | `200` con il corpo nuovo |
| `attendi=1` con impronta uguale e nessuna scrittura | `304` alla scadenza |
| Segnale che **non** cambia l'impronta (scrittura su un evento già completato) | resta appeso, **non** risponde `200` |
| Nona attesa contemporanea, con tetto 8 | risposta immediata, nessun errore |
| `GET /api/eventi` **senza** `attendi` | identico a oggi (compatibilità all'indietro) |
| Scrittura su un DAO raggiunto **senza passare da `ScrittureWeb`** | l'attesa si sveglia lo stesso — è il presidio di US-6 |

L'ultimo caso è quello che vale di più: dimostra che il segnale nasce dal database e non dal
percorso di scrittura della web app, cioè che un ramo futuro non può dimenticarselo.

### D.3 — `HttpServerTest` (aggiunta)

Un caso solo, ma necessario: **una connessione appesa non blocca le altre.** Il ciclo di `accept`
lancia una coroutine per connessione (`HttpServer.kt:65`) quindi in teoria non può; in pratica è
proprio il genere di garanzia che si scopre falsa il giorno in cui qualcuno la dà per buona.

### D.4 — Il browser: nessun test automatico, e va dichiarato

**Riscontro:** nel progetto non esiste alcun `package.json` (verificato: `find` su tutto l'albero,
esclusi i `build/`, non ne trova nessuno) e nessuna infrastruttura di prova per JavaScript. `node`
esiste sulla macchina (`~/.nvm/…/v23.5.0`), ma **il progetto non lo usa**.

Introdurre npm, jsdom e un runner per provare una funzione di riconciliazione significherebbe
aggiungere a un progetto senza dipendenze di terze parti — che scrive a mano ODS, HKDF e DER — un
albero di centinaia di pacchetti per provare 150 righe. **La proposta è di non farlo**, e di dirlo
invece di lasciarlo capire.

Al suo posto, due presidi reali:

1. **La riconciliazione resta una funzione pura di `(precedenti, nuovi) → operazioni`**, separata
   dall'applicazione al DOM. Non la prova nessuno oggi, ma è *provabile* il giorno in cui il
   progetto avrà una ragione indipendente per avere un runner JS. Farla impura chiuderebbe quella
   porta per sempre.
2. **Checklist manuale**, in `status.md`, eseguita con gli strumenti che il progetto ha già:
   `tools/sessione-x.sh browser <url>` apre un browser con profilo nuovo sul display `:2`, e con
   l'emulatore l'indirizzo è quello di `adb forward tcp:9888 tcp:9888`.

   - [ ] Modifico un titolo dall'app: cambia una scheda sola (verificabile con un
         `MutationObserver` incollato nella console, che stampa i nodi rimossi).
   - [ ] Scorro in fondo a un elenco lungo, modifico dall'app un evento in cima: `scrollY` non
         cambia.
   - [ ] Seleziono del testo su una scheda, modifico un'altra scheda dall'app: la selezione resta.
   - [ ] Sposto la data di un evento: la scheda cambia posizione, e non ne compaiono due.
   - [ ] «Fatto» dal browser: sparisce una scheda sola, la barra dell'annullamento compare, e
         «Annulla» rimette la scheda al suo posto.
   - [ ] Modifico dall'app **mentre il modulo è aperto**: quello che sto scrivendo non si muove; a
         modulo chiuso la modifica c'è.
   - [ ] Nascondo la scheda del browser: sul telefono nessuna attesa resta appesa.
   - [ ] Spengo e riaccendo il Wi-Fi: la pagina si riallinea da sola, senza ricarica.
   - [ ] Lettore di schermo (§C.3): l'aggiornamento annuncia la scheda cambiata, non tutto l'elenco.

### D.5 — Test esistenti da **non** rompere

`RouterTest` (35 casi), `WebServiceTest` (15), `HttpServerTest`, `HttpMessagesTest`,
`ScrittureWebTest`, `TlsHandshakeTest`, `PorteWebTest`, `AccessTokenTest`, e in
`androidInstrumentedTest` `CorpoPiattaformaTest` e `TlsPiattaformaTest`. Con i default inerti di
§B.3 **nessuno di questi file va toccato**, ed è il criterio di verifica del fatto che il percorso
di lettura di oggi è rimasto quello che era.

---

## E. Rischi tecnici aggiornati

I rischi della Fase 1, con l'evidenza trovata, più cinque nuovi che solo la lettura del codice
poteva far emergere.

### Aggiornati

| # | Stato dopo l'analisi |
|---|---|
| **R1** — connessioni appese | **Ridimensionato.** Il modello è già una coroutine per connessione (`HttpServer.kt:65`); un'attesa non aggiunge una macchina, aggiunge una sospensione. Resta il tetto (§B.4, proposto 8) |
| **R2** — Doze e app chiusa | **Confermato, e resta il rischio principale.** `WebServerService` è `specialUse` e `START_NOT_STICKY`, e la 004 ha già verificato sul dispositivo che *scrivere* ad app chiusa funziona. Ma «una richiesta che dura 25 secondi con lo schermo spento» è una domanda diversa da «una richiesta che dura 50 millisecondi», e nessuno l'ha fatta. **M0 resta bloccante** |
| **R3** — costo TLS | **Ridimensionato.** `SSLContext` costruito una volta sola (003), cache di sessione attiva, `Connection: close` su ogni risposta (`HttpMessages.kt`). Un'attesa che scade ogni 25 s costa **quanto** il polling di oggi ogni 30 s: stesso ordine, un handshake ripreso da sessione |
| **R4** — riordino | **Confermato.** `getAllOpen()` ordina per `dateTimeMillis ASC` (`EventDao.kt:90`) e `VoceWeb` porta `id` (`WebPayload.kt:56`): la chiave stabile c'è. Resta la parte difficile, che è l'algoritmo |
| **R5** — niente `uuid` nel payload | **Chiuso.** `id` è la chiave primaria ed è già nel payload dalla 002, messo lì per la scrittura futura. Nessuna modifica di formato |
| **R6** — modulo aperto | **Deciso: la sospensione resta.** Vedi §G.4 |
| **R7** — barra dell'annullamento | **Chiuso.** `daAnnullare` tiene `{evento, updatedAt}`, cioè una **copia dei dati**, non un riferimento al nodo (`app.js:308-311`). La riconciliazione non lo tocca |
| **R8** — due token, due impronte | **Chiuso.** Il contatore è globale, l'impronta è per client: il ciclo di §B.2 ricompone il corpo **con i permessi di chi ha chiesto**, quindi due sessioni con token diversi si svegliano insieme e ricevono ciascuna il proprio corpo |
| **R9** — `app.js` cresce | **Confermato e peggiorato da R11.** Vedi sotto |
| **R10** — provare il JavaScript | **Deciso: nessun test automatico, dichiarato.** §D.4 |

### Nuovi

| # | Rischio | Gravità | Mitigazione |
|---|---|---|---|
| **R11** | **La closure sopravvive ai dati.** `riga()` cattura `evento` nei gestori di «Fatto» e «Modifica» (`app.js:141-142`). Oggi è innocuo perché il nodo muore a ogni giro; riusandolo, un click manderebbe l'`updatedAt` che quella scheda aveva **quando è stata creata**. L'utente vedrebbe «questo promemoria è stato modificato sul telefono nel frattempo» dopo aver toccato un pulsante su una scheda che sullo schermo mostra il valore giusto | **Alta** | I dati vivono sul nodo (`card._evento`), i gestori li leggono al click. Da mettere in checklist: modificare dall'app e poi toccare «Fatto» sulla stessa scheda dal browser deve funzionare |
| **R12** | **Corsa fra composizione e sottoscrizione.** Leggere il contatore dopo aver composto il corpo perde le scritture che cadono in mezzo | Media | L'ordine di §B.2, con il commento che lo spiega, più il caso di `CambiamentiTest` che lo prova |
| **R13** | **`READ_TIMEOUT_MILLIS = 10_000` sembra vietare l'attesa, e non la vieta.** È `soTimeout`, cioè un timeout di *lettura*, impostato prima di `leggiRichiesta` (`HttpServer.kt:89`); dopo la lettura non si legge più, e sulle scritture non ha effetto | Bassa, ma insidiosa | Un commento nel punto dell'attesa. Senza, un lettore futuro "aggiusterà" una contraddizione che non esiste — o peggio, alzerà quel timeout credendo che serva |
| **R14** | **La scheda nascosta lascia un'attesa appesa.** Oggi `visibilitychange` ferma un `setInterval` (`app.js:447`), che è istantaneo. Con il long-poll una richiesta è già partita, e senza `AbortController` il telefono tiene una coroutine e un socket per tutta la durata dell'attesa per **ogni** scheda dimenticata — cioè il tetto di §B.4 consumato da schede che nessuno guarda | Media | `AbortController`, annullato su `visibilitychange`. È anche ciò che rende onesto il tetto a 8 |
| **R15** | **Un `fetch` che non si risolve mai** blocca il ciclo auto-schedulato per sempre, e la pagina muore in silenzio mostrando dati vecchi come se fossero freschi | Media | Guardia lato client: `attesa + margine`, poi `abort` e ripartenza. Sostituisce il vago «polling lento di sicurezza» della Fase 1 §2.9, ed è più preciso: non un secondo ciclo, ma una sveglia sul primo |
| **R16** | **`aria-live` cambia comportamento** (§C.3) | Bassa | In checklist |

---

## F. Prerequisiti e task bloccanti

### F.1 — Bloccanti

1. **M0 — la prova d'attrito su R2.** Prima di scrivere qualunque riga. È un'ora di lavoro:
   `adb shell dumpsys deviceidle force-idle` con una richiesta appesa in corso, e si guarda se
   risponde. Se non regge, non cambia il codice: cambia **la promessa** — il push diventa «mentre
   guardi il telefono o lo schermo grande», e la pagina deve saperlo dire.
2. **Due numeri da fissare e da motivare per iscritto:** durata dell'attesa (proposta **25 s**) e
   tetto (proposto **8**). Un numero senza il suo perché è un numero che il prossimo cambierà a caso.

### F.2 — Da verificare presto, non bloccante

**`InvalidationTracker.createFlow` con `AndroidSQLiteDriver`.** L'API è pubblica e confermata nel
sorgente di Room 2.8.4; quello che nessun test su desktop può dire è se si comporta come atteso con
il driver di Android, che è un driver diverso da `BundledSQLiteDriver`. È la stessa classe di
trappola di `KeyStore.getDefaultType()` nella 003 e di `readNBytes` nella 004: **qualcosa che si
rompe solo dove i test unitari non arrivano.** Uno smoke test sull'emulatore, subito dopo M1.

### F.3 — Nessun refactoring preliminare

Non ne serve. `Router.eventi()` è già isolata, `corpoEventi` è già il punto unico, `riga()` è già
una funzione a sé. La scomposizione di `riga()` in «crea» e «aggiorna» fa parte del lavoro di M3,
non è un prerequisito da fare prima.

### F.4 — Ordine consigliato

Quello della Fase 1 regge, con una precisazione: **M3 (riconciliazione) può iniziare in parallelo a
M1/M2**, perché non dipende dal server — si prova a ritmo invariato di 30 s. Ed è anche il lavoro
più rischioso, per via di R11: cominciarlo presto lascia margine.

---

## G. Risposte alle domande aperte della Fase 1

**G.1 — R2 è il rischio principale?** Sì, e l'analisi lo conferma senza poterlo chiudere: è l'unica
domanda a cui il codice non risponde. `WebServerService` fa tutto ciò che si può fare
(`specialUse`, `START_NOT_STICKY`, notifica permanente), ma il comportamento di Doze verso una
connessione *già aperta e ferma* non è deducibile: va misurato. **M0 resta bloccante.**

**G.2 — Endpoint nuovo o parametro?** **Parametro**, `?attendi=1`. Tre ragioni concrete: (a) la
risorsa è la stessa, cambia solo la consegna; (b) un percorso nuovo andrebbe aggiunto anche alla
tabella `Allow` di `metodoSbagliato` (`Router.kt:174-181`) e ragionato contro `StaticAssets`; (c) la
compatibilità all'indietro viene gratis, in entrambe le direzioni (§B.1).

**G.3 — Quante attese contemporanee?** **8**, con il ragionamento di §B.4 scritto accanto alla
costante. Superato il tetto si risponde subito: si degrada in «torna lenta», mai in un errore.

**G.4 — Che cosa fa l'aggiornamento mirato mentre il modulo è aperto?** **La sospensione resta.** Il
dato continua ad aggiornarsi in `eventi`, il DOM no, e alla chiusura si applica la riconciliazione —
che è ciò che `chiudiModulo()` già fa chiamando `disegna()` (`app.js:388`).

La tentazione è aggiornare il resto dell'elenco: tecnicamente si può, ora. Non conviene, e la
ragione non è la prudenza generica. La scheda in modifica è **una riga dell'elenco sotto il
dialogo**; aggiornare le altre può spostarla (R4: cambia l'ordinamento), e chi chiude il modulo si
ritrova il contesto visivo cambiato sotto. Il guadagno è vedere aggiornarsi schede che in quel
momento sono coperte da un dialogo modale. Non vale il caso particolare — e i casi particolari in
questo file sono già quattro.

**G.5 — Come si prova il JavaScript?** Non lo si prova automaticamente, e §D.4 dice perché e che
cosa si fa al suo posto: funzione di riconciliazione pura (provabile domani) più una checklist
manuale eseguibile con `tools/sessione-x.sh`, scritta in `status.md`.

---

## Ciò che questa analisi **non** ha potuto verificare

Onestà su cosa resta aperto:

- **Il comportamento di Doze verso una connessione appesa** (R2). Solo il dispositivo lo sa.
- **`createFlow` sul driver di Android** (F.2). Solo l'emulatore lo sa.
- **Il costo reale di un handshake TLS ripreso da sessione su questo telefono.** La 003 ha misurato
  77-115 ms per sette handshake di una pagina intera; il numero per uno solo è dedotto, non misurato.
- **Se 8 attese siano il numero giusto.** È un ragionamento, non una misura. Va rivisto se la prova
  sul dispositivo dice altro.
