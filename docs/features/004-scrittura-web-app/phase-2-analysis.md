# Feature 004 — Analisi tecnica della codebase (Fase 2)

**Data:** 2026-08-23
**Requisiti:** [phase-1-requirements.md](phase-1-requirements.md) — D-01 e D-02 confermate dall'utente
**Base di partenza:** `main` @ `73d1097` (versione 2.3.0), albero pulito
**Suite alla partenza:** `./gradlew :shared:desktopTest :desktopApp:test` → **237 test, 0 falliti**
(225 in `:shared` + 12 in `:desktopApp`), di cui **115 nel package `web`**

> **Come leggere questo documento.** Ogni affermazione sul codice esistente porta il file e la riga.
> Dove si propone qualcosa che non esiste ancora, è scritto **[proposta]**. Dove una verifica manca
> ed è dirimente, è scritto **[da verificare]** e finisce in §F.

---

## Sommario delle scoperte che cambiano il piano

Tre cose emerse leggendo il codice che il documento di fase 1 non poteva sapere:

1. **L'anticipo della notifica è un insieme chiuso, e l'app si rompe se ne esce.**
   `EventEditScreen.kt:52` elenca cinque valori (0, 5, 15, 30, 60) e la riga 153 fa
   `advanceOptions.first { it.first == advanceMinutes }`. `first` senza `orNull`: un evento con
   `advanceMinutes = 7` scritto dal browser fa **lanciare `NoSuchElementException` all'apertura
   dell'editor sul telefono**. Non è un caso di validazione generica: è un guasto concreto, in un
   punto preciso, e chiude D-04 per quel campo — il server accetta i cinque valori e nient'altro.

2. **`updatedAt` deve uscire verso il browser.** Il controllo ottimistico scelto in fase 1 ha bisogno
   che il client dichiari che cosa credeva di modificare, e oggi `VoceWeb` (`WebPayload.kt:32`) non
   espone niente del genere. È l'unico allargamento del formato in uscita, e va deciso qui perché la
   fase 1 aveva scritto la regola opposta («`uuid` e `origin` non escono»).

3. **Il corpo della richiesta non può essere un `ByteArray` dentro `HttpRequest`.** È una data class
   (`HttpMessages.kt:59`), e questo codice ha già pagato due volte il prezzo di un array dentro una
   data class — `HttpResponse` (riga 199) e `CorpoJson` (`WebPayload.kt:48`) hanno entrambe `equals`
   e `hashCode` scritti a mano proprio per quello. Il corpo qui è JSON, cioè testo: tenerlo come
   `String` evita la terza ripetizione dello stesso rattoppo.

---

## A. File coinvolti

### A.1 — `:shared` / `jvmSharedMain` / `web` (il grosso del lavoro)

| File | Modifica | Motivazione |
|---|---|---|
| `web/HttpMessages.kt` | **modifica** | Aggiungere la lettura del corpo. Oggi `leggiRichiesta` (riga 85) si ferma alla riga vuota, e il commento alle righe 82-84 dice che è voluto: «qui si risponde solo a `GET`, e un corpo che non si legge è un corpo che non si deve interpretare». Quel commento va **riscritto**, non cancellato: la ragione per cui il corpo si legge deve essere lì accanto ai limiti che lo rendono sicuro. Servono anche: `MAX_CORPO` accanto a `MAX_LINEA` (riga 23), il rifiuto esplicito di `Transfer-Encoding`, il campo `body` in `HttpRequest` (riga 59) e i codici `409`/`415`/`422` in `descrizione()` (riga 249). |
| `web/AccessToken.kt` | **modifica** | Da un segreto a due. Oggi `corrente` è un solo `@Volatile var` (riga 47), `rigenera()` restituisce una `String` (riga 54) e `verifica()` restituisce `Accesso` (riga 68). Diventano: due segreti, `rigenera()` che restituisce la coppia, `Accesso` con due esiti positivi. **Il contatore dei tentativi resta uno solo** — è per indirizzo IP, non per token, e sdoppiarlo darebbe a chi sonda venti tentativi invece di dieci. |
| `web/Router.kt` | **modifica** | Il ramo che il commento alle righe 14-16 annuncia. Oggi 61 righe; diventeranno circa il doppio. `gestisci` (riga 23) smista già per metodo: si aggiungono `POST` e `PUT`. `autorizzato` (riga 52) diventa «autorizzato **a fare che cosa**». L'`Allow` fisso a `GET` (riga 25) diventa dipendente dal percorso. |
| `web/WebPayload.kt` | **modifica** | `WEB_PAYLOAD_VERSION` da 1 a 2 (riga 14). `VoceWeb` guadagna `updatedAt`. `WebPayload` guadagna `permessi`. `corpoEventi` (riga 69) resta **l'unico** posto che costruisce la rappresentazione, e diventa anche la risposta delle scritture. |
| `web/ScrittureWeb.kt` | **nuovo** *[proposta]* | Dove vivono validazione, `copy()` sulla riga esistente, controllo ottimistico e riprogrammazione dell'allarme. Separato dal router per la stessa ragione per cui `SyncEngine` è separato da `SyncService`: si prova senza mettere in mezzo la rete. |
| `web/WebService.kt` | **modifica** | Costruisce `Router` (riga 44) e quindi deve ricevere ciò che serve alle scritture: `AlarmScheduler` e l'identità del dispositivo. Ha già un `now: () -> Long` (riga 30) da riusare per `updatedAt`. `enable()` (riga 71) e `apri()` (riga 116) toccano il token e vanno adeguati alla coppia. |

### A.2 — `:shared` / `commonMain`

| File | Modifica | Motivazione |
|---|---|---|
| `web/WebServerController.kt` | **modifica** | `WebStatus` (riga 20) ha un solo `token` e un solo `url` (riga 55). Diventano due di ciascuno. Il commento alle righe 45-48 — «si compone qui e non nella schermata, due punti che lo compongono per conto proprio prima o poi lo compongono in due modi diversi» — vale ancora e vale doppio adesso. |
| `ui/web/SezioneWebApp.kt` | **modifica** | Due indirizzi, uno visibile e uno a scomparsa (D-03, §C.8). Il blocco `url != null ->` (righe 88-137) va estratto in una funzione parametrica, altrimenti si duplicano cinquanta righe; la parte a scomparsa riusa la forma di `Impronta` (riga 179). Il sottotitolo di riga 69 — «Apre una pagina di sola lettura per chi è sulla stessa rete» — resta **vero per l'indirizzo visibile** e va completato, non riscritto. |
| `ui/web/WebViewModel.kt` | **nessuna** | Riespone il controller (riga 16) e basta; `WebStatus` cambia sotto senza toccarlo. |
| `data/EventDao.kt` | **modifica** *[proposta]* | Un aggiornamento condizionato su `updatedAt`, per rendere atomico il controllo ottimistico (§B.4). È l'unica modifica al livello dati, e **non tocca lo schema**: nessuna migrazione. |

### A.3 — Asset della pagina (`shared/src/webAssets/web/`)

| File | Modifica | Motivazione |
|---|---|---|
| `app.js` | **modifica sostanziale** | Oggi 164 righe che sanno solo disegnare. Si aggiungono: il modulo, le tre chiamate di scrittura, la gestione di `409`, la distinzione dei due `403` (righe 117-121) e la convivenza col ridisegno (§C.5). Stimabile in +200 righe. |
| `app.css` | **modifica** | Stili del modulo e dei comandi. Le variabili cromatiche ci sono già (righe 6-42) e vanno riusate: due vocabolari di colore sarebbero il difetto che il commento in testa al file mette in guardia dal fare. |
| `index.html` | **modifica** | Il contenitore del modulo, **fuori da `#elenco`** (riga 23). Vedi §C.5: dentro, verrebbe cancellato dal ridisegno. |
| `manifest.json`, icone | **nessuna** | — |
| `web/StaticAssets.kt` | **nessuna** | Nessun file nuovo, quindi nessuna voce nuova nell'elenco chiuso (riga 33). È un beneficio da dichiarare: la superficie servita non cresce. |

### A.4 — `:androidApp`

| File | Modifica | Motivazione |
|---|---|---|
| `ReminderApp.kt` | **modifica** | La costruzione di `WebService` (righe 66-76) passa `alarmScheduler` (già presente a riga 46) e `deviceId` (riga 44). Due argomenti, zero logica nuova. |
| `ui/MainActivity.kt` | **modifica** | D-08 (scrittura solo ad app aperta) ha bisogno di sapere quando l'Activity è visibile. `onStart` (riga 42) c'è già e chiama `web.resume()`; **manca il simmetrico**, e il commento delle righe 37-40 lo dice esplicitamente: «**Non c'è un `onStop` corrispondente**: la porta resta aperta ad app chiusa». Quel commento va riscritto con precisione, perché l'`onStop` che serve **non chiude la porta** — abbassa solo una bandiera. Confonderli richiuderebbe il socket a ogni rotazione, disfacendo la 002. |

### A.5 — `:desktopApp`

**Nessuna modifica.** `AppContainer.web` ha per default `WebServerNonDisponibile`
(`AppContainer.kt:43`, `WebServerController.kt:113`), e i campi nuovi di `WebStatus` avranno valori
predefiniti. `SezioneWebApp` esce subito su `!viewModel.supported` (riga 49).

### A.6 — Documentazione

`CLAUDE.md` — la sezione `web/` cambia in **quattro** punti, tutti oggi scritti al contrario:
«**in sola lettura**», «Il token protegge `/` e `/api/eventi`» (ora sono due token con due poteri),
la descrizione del formato di scambio, e il fatto che le richieste non hanno corpo.

---

## B. Contratti e interfacce da modificare

### B.1 — `HttpRequest` guadagna il corpo

```kotlin
internal data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    /** Corpo già decodificato UTF-8; vuoto quando non c'è. */
    val body: String = ""          // [proposta]
)
```

**Perché `String` e non `ByteArray`.** Perché `HttpRequest` è una data class e un array dentro una
data class rompe `equals`/`hashCode` generati. Non è teoria: succede già due volte in questo
codice — `HttpResponse` (`HttpMessages.kt:199-206`) e `CorpoJson` (`WebPayload.kt:48-51`) hanno
entrambe l'`equals` scritto a mano con quel commento. Il corpo qui è **sempre** JSON, cioè testo:
tenerlo testo evita di ripetere il rattoppo una terza volta. Il limite di dimensione si applica
**sui byte, prima di decodificare**.

Valore predefinito `""`: `HttpRequest` si costruisce in tre punti soltanto — due in
`HttpMessages.kt` e uno nell'aiutante `richiesta()` di `RouterTest.kt:17`, da cui passano tutti i
quindici test del router — e con il predefinito nessuno dei tre va toccato per il solo fatto che il
campo esiste.

**Non cambia** la firma di `HttpServer.gestisci` (`HttpServer.kt:45`), perché il corpo viaggia dentro
la richiesta. È una conseguenza voluta della scelta di dove metterlo.

### B.2 — `AccessToken`: due segreti, un contatore

```kotlin
internal enum class Accesso { LETTURA, SCRITTURA, NEGATO, BLOCCATO }   // [proposta]

internal data class CoppiaToken(val lettura: String, val scrittura: String)
```

| Oggi | Domani | Nota |
|---|---|---|
| `val valore: String?` (riga 51) | `val coppia: CoppiaToken?` | `WebService.apri()` (riga 119) controlla `== null`: resta uguale |
| `fun rigenera(): String` (riga 54) | `fun rigenera(): CoppiaToken` | usato da `WebService.enable()` (riga 75) e da 12 test |
| `verifica(...): Accesso` (riga 68) | idem, con due esiti positivi | il chiamante confronta con il livello richiesto |

Quattro regole che vanno scritte nel codice perché non si perdano:

1. **Il confronto avviene contro entrambi i token, senza scorciatoie.** `constantTimeEquals`
   (`AccessToken.kt:71`) va chiamata due volte e i risultati combinati **dopo**: interrompere al
   primo successo reintrodurrebbe la differenza di tempo che quella funzione esiste per togliere.
2. **Il token di scrittura vale anche per leggere.** Altrimenti la pagina servita sull'indirizzo
   completo non potrebbe caricare la propria lista.
3. **Un token valido ma insufficiente non consuma tentativi.** Chi apre l'indirizzo di lettura e
   prova a scrivere è l'utente, non un attaccante: contarglielo lo chiuderebbe fuori dopo dieci
   tentativi. `registraFallimento` (riga 93) si chiama solo quando **nessuno** dei due coincide.
4. **I due token sono diversi.** Con quaranta bit la collisione è teorica, ma un test costa una riga.

### B.3 — Il formato di scambio passa alla versione 2

```jsonc
// GET /api/eventi?t=…   →   200
{
  "versione": 2,
  "permessi": "lettura",            // oppure "scrittura"        [nuovo]
  "eventi": [
    { "id": 12, "titolo": "Dentista", "descrizione": null,
      "dateTimeMillis": 1756000000000, "notificationMillis": 1755999100000,
      "updatedAt": 1755900000000 }  //                            [nuovo]
  ]
}
```

**`updatedAt` esce, e va detto perché.** La fase 1 aveva scritto che `uuid` e `origin` non escono
perché sono l'identità dell'evento fra dispositivi. `updatedAt` non è identità: è un numero di
versione della riga, e senza di esso il controllo ottimistico non ha su che cosa poggiare. Non
rivela niente che il browser non veda già — è il momento in cui è cambiato un promemoria di cui sta
guardando titolo, data e descrizione.

*Alternativa considerata e scartata:* un'impronta opaca della riga al posto del millisecondo. Non
nasconde nulla di più (chi ha la lista ha già i dati) e costa un calcolo per riga a ogni lettura.

**`permessi` è per la presentazione, non è un controllo d'accesso.** Serve a `app.js` per sapere se
disegnare i comandi. Il controllo vero è in `Router`, su ogni singola scrittura, e i due non devono
mai essere confusi (R-08).

### B.4 — Le due rotte di scrittura (D-02, confermata)

```
POST /api/eventi?t=<scrittura>            crea
PUT  /api/eventi/{id}?t=<scrittura>       modifica, completamento compreso
```

**Corpo della `POST`** — quattro campi, gli stessi dell'editor dell'app:

```jsonc
{ "titolo": "Dentista", "descrizione": "Portare la tessera",
  "dateTimeMillis": 1756000000000, "advanceMinutes": 15 }
```

**Corpo della `PUT`** — gli stessi più due:

```jsonc
{ "titolo": "…", "descrizione": null, "dateTimeMillis": 1756000000000,
  "advanceMinutes": 15,
  "completato": false,          // «fatto» e «annulla» sono questo campo
  "attesoUpdatedAt": 1755900000000 }
```

**La risposta di ogni scrittura riuscita è la lista intera, con il suo `ETag`** — cioè esattamente
ciò che restituisce `corpoEventi` (`WebPayload.kt:69`). Tre benefici in una scelta sola:

- US-06 è soddisfatta senza un giro di rete in più: la pagina ridisegna con la risposta che ha già;
- il `304` successivo funziona da sé, perché il client ha già l'impronta nuova;
- **una sola funzione costruisce la rappresentazione**, quindi lettura e scrittura non possono
  divergere. È la stessa ragione per cui `corpoEventi` riusa `getAllOpen()` invece di riscrivere i
  criteri della query (commento alle righe 64-68).

Anche il `409` porta lo stesso corpo: il client deve mostrare **il valore vero**, e ce l'ha già.

**Codici di stato:**

| Codice | Quando | Nota |
|---|---|---|
| `200` | `PUT` riuscita | corpo = lista + `ETag` |
| `201` | `POST` riuscita | corpo = lista + `ETag` |
| `400` | JSON illeggibile, corpo oltre il limite, `Content-Length` assente o non numerico, `Transfer-Encoding` presente | secco, come già oggi |
| `403` | token assente, sbagliato, oltre soglia, **o di sola lettura su una scrittura** | tutti indistinguibili sul filo |
| `404` | percorso ignoto, id inesistente **o tombstone** | `getById` filtra `deleted = 0` (`EventDao.kt:23`): un id cancellato è già `null`, senza codice in più |
| `405` | metodo sbagliato su un percorso valido | con `Allow` corretto per quel percorso |
| `409` | `attesoUpdatedAt` non coincide | corpo = lista aggiornata |
| `415` | `Content-Type` non è `application/json` | vedi sotto |
| `422` | validazione fallita | **con il nome del campo** |

**Perché `415` esiste.** Un modulo HTML su una pagina ostile può inviare solo
`application/x-www-form-urlencoded`, `multipart/form-data` o `text/plain`: pretendere
`application/json` fa scattare il preflight CORS, che una pagina cross-origin non supera. Costa una
riga e chiude la via più semplice di R-06.

**Perché il `422` dice quale campo, mentre il `403` non dice niente.** Non è incoerenza: parlano a
due interlocutori diversi. Il `403` risponde a uno sconosciuto che sta sondando, e ogni parola in più
lo aiuta (`Router.kt:55-59`). Il `422` risponde a un client che ha **già dimostrato** di avere il
token di scrittura, e tacere lì produce solo un utente che non capisce perché il salvataggio non
passa.

### B.5 — Validazione (chiude D-04)

Ricavata dal codice, non inventata:

| Campo | Regola | Evidenza |
|---|---|---|
| `titolo` | obbligatorio, `trim()`, non vuoto dopo il trim; **max 200 caratteri** *[proposta]* | `EventEditViewModel.save():64` esce su `isBlank()`; riga 74 fa `trim()`. Il campo è `singleLine` (`EventEditScreen.kt:109`) ma senza limite: 200 è un numero nuovo, dichiarato qui |
| `descrizione` | `trim()`, vuota ⇒ `null`; **max 2000 caratteri** *[proposta]* | `EventEditViewModel.save():75` — `trim().ifBlank { null }`. Senza questa normalizzazione le due strade divergono: l'app salverebbe `null`, il web `" "` |
| `dateTimeMillis` | intero positivo entro un intervallo dichiarato *[proposta: 1970–2100]* | Nessun limite nel codice attuale; il `DatePicker` di Material ne ha uno suo. Serve un limite perché un valore assurdo produce un allarme assurdo |
| `advanceMinutes` | **uno di {0, 5, 15, 30, 60}** | `EventEditScreen.kt:52-58`. **Vincolante**: riga 153 usa `first { … }` senza `orNull`, quindi un valore fuori elenco fa lanciare l'editor dell'app |
| `completato` | booleano | — |
| `attesoUpdatedAt` | obbligatorio nella `PUT` | Se manca, `400`: **non** «vada avanti lo stesso». Un client che dimentica il controllo ottimistico non deve poterlo aggirare per distrazione |

### B.6 — `WebStatus`: due indirizzi

```kotlin
data class WebStatus(
    …
    val tokenLettura: String? = null,      // sostituisce `token`
    val tokenScrittura: String? = null,
    …
) {
    val urlLettura: String? get() = …      // sostituisce `url`
    val urlScrittura: String? get() = …
}
```

`url` (riga 55) e `token` (riga 32) **spariscono**: lasciarli come alias del solo indirizzo di
lettura sarebbe la trappola perfetta — codice che compila, funziona, e dà per sbaglio i pieni poteri
o li nega. Meglio l'errore di compilazione. Sono toccati da `SezioneWebApp.kt:80` e da cinque test
in `WebServiceTest`.

### B.7 — `EventDao`: un aggiornamento condizionato *[proposta]*

```kotlin
@Query("""
    UPDATE events SET title = :title, description = :description,
        dateTimeMillis = :dateTimeMillis, advanceMinutes = :advanceMinutes,
        completed = :completed, updatedAt = :nowMillis
    WHERE id = :id AND deleted = 0 AND updatedAt = :attesoUpdatedAt
""")
suspend fun updateIfUnchanged(…): Int      // righe toccate: 1 = fatto, 0 = conflitto
```

**Perché non basta leggere-confrontare-scrivere.** `HttpServer` serve ogni connessione in una
coroutine sua (`HttpServer.kt:65`), quindi due `PUT` sullo stesso evento possono superare entrambe
il confronto e sovrascriversi a vicenda. In SQLite un singolo `UPDATE … WHERE` è atomico e il numero
di righe toccate *è* l'esito del confronto: una domanda sola invece di due.

**Perché non un `Mutex` nel livello web.** Serializzerebbe le scritture del browser ma non quelle del
telefono, che passano dai ViewModel — ed è proprio la corsa fra browser e telefono il caso che il
controllo ottimistico esiste per coprire.

**Perché non `@Transaction`.** Sarebbe l'altra strada, ma il supporto ai metodi con corpo predefinito
nelle interfacce DAO di Room KMP va verificato prima di poggiarci sopra → §F.

Nota: `updateIfUnchanged` **non** tocca `uuid` e `origin`, che non compaiono nella `SET`. La regola
di `CLAUDE.md` («partire dalla riga esistente e usare `copy()`, mai ricostruire l'entità») è così
rispettata per costruzione e non per disciplina.

`FakeEventDao` (`commonTest/data/FakeEventDao.kt`) va aggiornato con lo stesso comportamento,
compreso il **conteggio delle righe**.

---

## C. Pattern da rispettare

### C.1 — La riprogrammazione dell'allarme ha già una regola scritta

`SyncEngine.reschedule` (`SyncEngine.kt:82-85`):

```kotlin
private fun reschedule(event: EventEntity) {
    alarms.cancel(event.id)
    if (!event.deleted && !event.completed) alarms.schedule(event)
}
```

Il commento sopra dice perché il `cancel` non è ridondante: «un evento diventato completato,
cancellato o spostato indietro deve perdere l'allarme che aveva, e `schedule` da solo non lo
toglierebbe». **È la stessa identica regola che serve alle scritture dal web**, e va riusata alla
lettera — non riscritta accanto. È la mitigazione di R-05, che è il rischio più grave della feature.

Da sapere: `AlarmScheduler.schedule` su Android (`alarm/AlarmScheduler.kt:14`) **esce senza fare
niente** se l'istante è già passato. Quindi un evento creato dal web con data nel passato non ottiene
allarme — che è il comportamento dell'app, non un caso da trattare a parte.

### C.2 — `SyncEngine` è anche il precedente strutturale

`SyncEngine` (`commonMain/sync/`) è costruito in `ReminderApp.kt:56` con
`SyncEngine(database.eventDao(), alarmScheduler)` — esattamente le due dipendenze che servono a
`ScrittureWeb`. La forma «una classe che riceve DAO + programmatore di allarmi, scrive e rimette in
riga le sveglie» **esiste già ed è accettata in questo codice**. `ScrittureWeb` non introduce una
struttura nuova: ne ripete una.

*Dove metterlo:* `jvmSharedMain/web/`, accanto a `WebPayload.kt`, non in `commonMain`. Serve solo al
web, i test del web stanno in `jvmSharedTest`, e la vicinanza al formato di scambio è la cosa che si
vuole quando si cambia un campo.

### C.3 — Copiare la riga, mai ricostruirla

`EventEditViewModel.save()` (righe 63-91) è il modello, con il commento delle righe 35-38 che spiega
perché. La `PUT` dal web è la stessa operazione con un'origine diversa, e la tentazione è peggiore:
lì si parte da un JSON, che *sembra* già un evento. La `SET` esplicita di §B.7 rende la scorciatoia
impossibile.

Nota di simmetria: la creazione dal web usa `EventEntity(…)` con i valori predefiniti, che generano
`uuid` (`EventEntity.kt:25`) e prendono `origin = deviceId` — **l'identità del telefono**, non del
browser, che in questo sistema non ne ha.

### C.4 — Le convenzioni del package `web`

- **Italiano** per tutto ciò che è nuovo: nomi, commenti, messaggi (`corpoEventi`, `VoceWeb`,
  `negato()`, `RigaTroppoLunga`).
- **`internal`** per tutto ciò che sta in `jvmSharedMain/web/` tranne `WEB_PORT` e `WebStatus`.
- **Il commento dice *perché*, non *cosa*.** Ogni file del package ha in testa la ragione di una
  scelta e la conseguenza dell'alternativa. Un file nuovo senza quel blocco stona.
- **I limiti sono espliciti, piccoli e costanti nominate** (`HttpMessages.kt:20-26`).
- **I test si chiamano con una frase in italiano fra backtick** e citano il caso, non il metodo.

### C.5 — Il ridisegno e il modulo: il problema di `app.js` (R-07)

Tre punti del file, oggi:

- `disegna()` (riga 91): `elenco.textContent = ''` — **azzera tutto** e ricostruisce.
- `controlla()` ogni 30 s (riga 143) chiama `disegna()` se i dati sono cambiati.
- `setInterval(disegna, INTERVALLO_COLORI)` (riga 161): ogni 60 s, **sempre**, anche a rete ferma.

Un modulo aperto dentro `#elenco` verrebbe cancellato entro un minuto, con dentro ciò che l'utente
sta scrivendo. Non è un caso limite: succede sempre.

**Proposta — due difese, entrambe piccole:**

1. **Il modulo vive fuori da `#elenco`**, in un `<dialog>` dichiarato in `index.html` accanto a
   `<main>` (riga 23). Il ridisegno non lo può raggiungere perché non è nel sottoalbero che
   ricostruisce.
2. **Mentre il modulo è aperto, il ridisegno è sospeso** (i dati si continuano a chiedere, ma
   `disegna()` non gira). Serve perché la lista sotto non deve cambiare sotto le mani mentre si
   guarda il modulo, e perché al `409` si vuole ridisegnare **una volta sola**, alla chiusura.

Da sapere: `INTERVALLO_RETE` è 30 s (riga 15), quindi **una modifica fatta sul telefono può restare
invisibile alla pagina per mezzo minuto**. Il controllo ottimistico non è una comodità: è ciò che
rende innocua quella finestra.

### C.6 — Lo stile di `app.js`

`'use strict'`, `var`, `function`, `fetch` con `.then` (righe 108-139), nessuna freccia, nessun
`const`, nessun `async`. Non è codice vecchio per caso: è coerente e va continuato. In particolare
`textContent` e mai `innerHTML` — il commento alle righe 89-90 spiega che così non c'è niente da
sfuggire, quindi niente da dimenticare, e con un modulo che rilegge testo scritto dall'utente quella
regola diventa più importante, non meno.

### C.6-bis — La bandiera del primo piano (D-08)

`MainActivity` ha oggi **solo** `onStart` (riga 42) e nessun `onStop`, per la ragione scritta alle
righe 37-40. D-08 chiede di aggiungerlo, e ci sono tre modi di sbagliarlo:

1. **Chiudere la porta invece di abbassare una bandiera.** Sarebbe la 002 disfatta: la porta deve
   restare aperta e servire letture, come oggi.
2. **Controllare la bandiera prima del token.** Darebbe a uno sconosciuto senza credenziali il modo
   di sapere se il telefono è in uso. L'ordine è: token, *poi* disponibilità.
3. **Dimenticare che la rotazione è un giro completo di `onStop`/`onStart`.** Per un istante la
   bandiera è bassa. È la **terza** volta che questo progetto incontra questa trappola — la prima è
   il token rigenerato (`WebService.kt:17-22`), la seconda il certificato riemesso
   (`CertificateStore.caricaOCrea`) — e le prime due sono documentate proprio perché in prova non si
   notano.

### C.7 — Il `403` ha due significati (R-12)

`app.js:117-121` mappa ogni `403` su «Indirizzo non più valido: la porta è stata riaperta…». Con due
token quel messaggio è sbagliato per metà dei casi. La distinzione si fa **nel client, in base a che
cosa si stava facendo** — una lettura fallita significa davvero indirizzo scaduto, una scrittura
fallita significa indirizzo di sola lettura — e **non** differenziando la risposta sul filo, perché
la ragione di `Router.kt:55-59` vale ancora.

### C.8 — L'interfaccia dell'app, e perché D-03 si è decisa al contrario

`SezioneWebApp.kt:89-115`: la riga intera è cliccabile e copia, l'icona è un secondo bersaglio, la
conferma la dà l'app solo sotto Android 13 (`sistemaConfermaLaCopia()`, riga 94).

**Il problema che D-03 risolve non è l'ordine dei due indirizzi: è che si somigliano troppo.**
Differiscono solo negli otto caratteri finali —

```
https://192.168.1.42:9888/?t=b3xnq7km
https://192.168.1.42:9888/?t=k7mqb3xn
```

— e in un `Text` con `maxLines = 2` e `TextOverflow.Ellipsis` (righe 108-109), affiancati, sono due
stringhe indistinguibili a colpo d'occhio. I due errori possibili **non pesano uguale**:

| Sbaglio | Conseguenza |
|---|---|
| Volevo condividere in lettura, ho copiato quello completo | Ho dato il telecomando. **Non me ne accorgo mai** |
| Volevo modificare, ho copiato quello di lettura | La pagina non ha i comandi. Me ne accorgo in tre secondi |

Da qui la decisione: **si vede l'indirizzo di sola lettura; quello completo sta dietro una riga da
toccare** («Serve anche modificare dal browser?»), con accanto la frase che dice che non va dato a
chi deve solo guardare. Il gesto che sbaglia in silenzio è l'unico che richiede un tocco in più.

Due conseguenze che fanno risparmiare lavoro:

- **L'idioma esiste già in questo file**: `Impronta` (riga 179) è esattamente la stessa forma —
  un `Text` cliccabile con `remember { mutableStateOf(false) }` che rivela un blocco e la sua
  spiegazione. La parte a scomparsa di D-03 la ricalca, non la inventa.
- **La card non si allunga.** Sullo schermo resta un indirizzo alla volta, e i cinque blocchi di
  testo che già circondano la riga (righe 116-136) non raddoppiano.

La proposta della fase 1 era l'opposto — prima quello completo, perché è quello che si usa per sé —
e ordinava per **frequenza d'uso**. È il criterio sbagliato: il token si rigenera solo alla
riaccensione e l'interruttore resta acceso, quindi *entrambi* gli indirizzi si copiano di rado. Il
criterio che decide è l'asimmetria della tabella qui sopra.

**Il messaggio di conferma della copia deve dire quale indirizzo è stato copiato**, e sotto Android
13 lo dà l'app (`onMessaggio`, riga 94). Dalla 13 lo dà il sistema, che mostra il testo copiato ma
non l'etichetta: è un limite noto e accettato, e il tocco in più sulla riga a scomparsa è ciò che lo
compensa.

---

## D. Test da creare o aggiornare

### D.1 — Da aggiornare (rompono alla compilazione o all'asserzione)

| File | Che cosa | Perché |
|---|---|---|
| `RouterTest.kt:43-50` | `ogni metodo diverso da GET riceve 405` | Elenca `POST` e `PUT`, che non saranno più sempre `405`. Va **spaccato in due**: i metodi mai ammessi (`DELETE`, `PATCH`, `HEAD`, `OPTIONS`) e i metodi ammessi solo su certi percorsi |
| `RouterTest.kt:143` | `assertTrue("\"versione\":1" in corpo)` | Diventa 2 |
| `RouterTest.kt:30` | `Router(dao, token)` | Nuove dipendenze nel costruttore |
| `AccessTokenTest` (10 test) | `rigenera()` e `valore` | Cambiano tipo |
| `WebServiceTest` (~5 test) | `stato.token`, `stato.url` | Diventano due campi ciascuno |
| `WebServiceTest:327` | `un giro completo sopra TLS, dal token alla pagina` | Va **esteso**, non sostituito: è l'unico test che attraversa il servizio vero sopra TLS, ed è il posto giusto per una scrittura completa |
| `FakeEventDao` | `updateIfUnchanged` | Stessa semantica, conteggio compreso |

### D.2 — Da creare

**`HttpMessagesTest` (+~12)** — il corpo, che è la superficie nuova (R-01):

- un corpo di `Content-Length` dichiarato si legge per intero, esattamente N byte e non uno di più;
- `Content-Length` assente su una `POST` ⇒ malformata;
- `Content-Length` non numerico, negativo, o con spazi ⇒ malformata;
- `Content-Length` oltre `MAX_CORPO` ⇒ malformata **senza aver letto il corpo**;
- `Content-Length` maggiore dei byte davvero inviati ⇒ non si resta appesi (c'è già il timeout di
  `HttpServer.kt:14`, ma va visto);
- `Content-Length` **minore** del corpo inviato ⇒ si legge solo quanto dichiarato;
- `Transfer-Encoding: chunked` ⇒ malformata, senza tentare di interpretarlo;
- **due** `Content-Length` in disaccordo ⇒ malformata (è la forma classica del *request smuggling*;
  qui non c'è keep-alive, ma il costo del test è zero);
- UTF-8 multibyte nel corpo si decodifica intero;
- byte non validi UTF-8 non fanno lanciare;
- corpo vuoto con `Content-Length: 0` è lecito;
- `409`, `415`, `422` compaiono in `descrizione()`.

**`AccessTokenTest` (+~6)** — i due livelli:

- il token di scrittura consente anche la lettura;
- il token di lettura **non** consente la scrittura;
- i due token sono diversi;
- entrambi cambiano insieme alla riaccensione, e i vecchi non valgono più;
- **un token valido ma insufficiente non incrementa i tentativi** (B.2/3);
- dieci fallimenti contro l'uno e contro l'altro sommano sullo **stesso** contatore, non su due.

**`RouterTest` (+~18)** — smistamento e autorizzazione delle scritture:

- `POST /api/eventi` con token di lettura ⇒ `403`, **e il database non è cambiato** (è il test di
  R-08: quello che conta non è il codice, è che non sia successo niente);
- `PUT /api/eventi/{id}` con token di lettura ⇒ `403`, database invariato;
- `POST /` ⇒ `405` con `Allow: GET`;
- `PUT /api/eventi` senza id ⇒ `405`;
- `POST /api/eventi/{id}` ⇒ `405`;
- `/api/eventi/abc` ⇒ `404`;
- id inesistente ⇒ `404`; id di un tombstone ⇒ `404`;
- `Content-Type` mancante o sbagliato ⇒ `415`;
- JSON malformato ⇒ `400`;
- `attesoUpdatedAt` assente ⇒ `400`;
- `attesoUpdatedAt` diverso ⇒ `409` **con la lista aggiornata nel corpo**;
- scrittura riuscita ⇒ corpo identico a quello di `GET /api/eventi` e `ETag` coerente;
- `POST` riuscita ⇒ `201`;
- `permessi` nel payload riflette il token usato;
- `"versione":2`;
- `uuid` e `origin` **restano fuori** dal payload anche in versione 2.

**`ScrittureWebTest` (nuovo, ~20)** — il cuore della feature, senza rete:

- i cinque valori di `advanceMinutes` passano, un sesto ⇒ `422` (il caso che romperebbe l'editor
  dell'app: il test cita il perché);
- titolo vuoto, di soli spazi, oltre il limite ⇒ `422` con il nome del campo;
- il titolo viene `trim`ato e la descrizione vuota diventa `null`, **come fa il ViewModel**;
- data fuori intervallo ⇒ `422`;
- **`uuid` e `origin` identici prima e dopo una modifica** (R-10);
- un evento creato nasce con `origin = deviceId` e un `uuid` nuovo;
- `updatedAt` avanza a ogni scrittura;
- completare ⇒ `alarms.cancel` chiamata, `schedule` **no**;
- annullare il completamento ⇒ `cancel` **e** `schedule`;
- spostare la data ⇒ `cancel` e `schedule` con il nuovo istante;
- creare ⇒ `schedule`;
- una scrittura rifiutata (`409`, `422`) ⇒ **nessuna chiamata al programmatore di allarmi** e
  nessuna riga toccata;
- **due `PUT` simultanee sullo stesso evento: una sola passa** (R-04). Con `updateIfUnchanged` è
  verificabile chiamando due volte con lo stesso `attesoUpdatedAt`.

**`WebServiceTest` (+~4)** — il giro completo sopra TLS:

- una `POST` vera attraverso il servizio vero, con verifica che l'evento sia nel DAO;
- una `PUT` con `attesoUpdatedAt` sbagliato ⇒ `409` sopra TLS;
- una scrittura con il token di lettura ⇒ `403` sopra TLS;
- spegnendo e riaccendendo, **entrambi** gli indirizzi cambiano.

### D.3 — Verifiche manuali (nessun test le può dare)

| ID | Verifica | Perché serve un dispositivo |
|---|---|---|
| **V-01** | Spostare l'orario dal browser e **vedere arrivare la notifica all'ora nuova** | È il criterio principale della feature. `AlarmManager` non esiste nei test su desktop |
| **V-02** | Aprire l'evento modificato dal web nell'editor dell'app | È la prova di B.5: se `advanceMinutes` fosse fuori elenco, l'editor lancerebbe |
| **V-03** | Le tre operazioni con l'app **chiusa**, porta tenuta da `WebServerService` | Il servizio in primo piano non c'è su desktop; una scrittura che richiedesse il thread principale fallirebbe solo lì |
| **V-04** | I due indirizzi copiati dalla schermata funzionano davvero e hanno poteri diversi | Il giro completo passa da appunti, browser e certificato |
| **V-05** | La pagina in un browser vero: modulo, tastiera, `<dialog>`, il ridisegno che non cancella | R-07 è un problema di DOM, non di logica |
| **V-06** | Non-regressione della 003: l'**impronta del certificato non cambia** | Se cambiasse, l'avviso del browser tornerebbe |

---

## E. Rischi tecnici aggiornati

| ID | Stato dopo l'analisi |
|---|---|
| **R-01** *(corpo della richiesta)* | **Confermato e circoscritto.** La lettura è oggi un ciclo `input.read()` byte per byte (`HttpMessages.kt:38-53`) senza alcuna nozione di `Content-Length`. Va aggiunto tutto: dichiarazione obbligatoria, limite, rifiuto di `Transfer-Encoding` e del doppio `Content-Length`. Attenuante strutturale: **non c'è keep-alive** (`HttpServer.kt:22-24`), quindi un corpo letto male non può contaminare una richiesta successiva — non ce n'è una. Copertura: 12 test in D.2 |
| **R-02** *(il token completo è un telecomando)* | Invariato. Attenuato dalla scelta di §B.2/3 |
| **R-03** *(fuso orario)* | **Declassato a nota.** La pagina già oggi *mostra* le date nel fuso del browser (`app.js:37-41`), quindi scriverle nello stesso fuso è **coerente con ciò che l'utente vede**: sulla pagina, quel che si imposta è quel che si legge. La divergenza col telefono esiste già in lettura e la scrittura non ne aggiunge. Resta da documentare, non da risolvere |
| **R-04** *(corsa fra scritture)* | **Confermato e risolvibile in modo pulito** con `updateIfUnchanged` (§B.7). Il rischio si sposta su una domanda: `@Transaction` in Room KMP → §F |
| **R-05** *(allarme non riprogrammato)* | **Confermato, e la mitigazione esiste già scritta**: `SyncEngine.reschedule` (§C.1). Il rischio diventa «qualcuno riscrive la regola invece di riusarla», presidiato da cinque test in `ScrittureWebTest` |
| **R-06** *(CSRF / rebinding)* | **Attenuato a costo quasi nullo** dal `415` su `Content-Type` (§B.4) |
| **R-07** *(il ridisegno cancella il modulo)* | **Confermato con le righe in mano** (§C.5): `app.js:91`, `:143`, `:161`. Due difese proposte. Resta il rischio più probabile della feature |
| **R-08** *(nascondere ≠ proteggere)* | Confermato. `permessi` è dichiarato per iscritto come campo di presentazione (§B.3), e i due test di R-08 verificano **che il database non sia cambiato**, non solo il codice di stato |
| **R-09** *(nuove dipendenze di `WebService`)* | **Ridimensionato.** `ReminderApp.kt` ha già sia `alarmScheduler` (riga 46) sia `deviceId` (riga 44) nello scope della costruzione: due argomenti in più, zero logica |
| **R-10** *(ricostruire l'entità)* | **Reso impossibile per costruzione**: la `SET` di `updateIfUnchanged` non contiene `uuid` né `origin` (§B.7). Da rischio di disciplina a proprietà del codice |
| **R-11** *(valori assurdi)* | **Chiuso da B.5**, e con una scoperta che lo rende più grave di come era scritto: non produce «un allarme strano», produce **un'eccezione nell'editor dell'app** |
| **R-12** *(due significati del 403)* | Confermato, riga individuata (`app.js:117-121`) |
| **R-13** *(payload v2 e client vecchio)* | Invariato, e più concreto: `RouterTest:143` è il test che presidia la versione |
| **R-14** ⚠️ **nuovo** | **`WebStatus.url` e `.token` spariscono, ed è voluto.** Tenerli come alias del solo indirizzo di lettura darebbe codice che compila e sbaglia in silenzio. Il costo è un giro di correzioni in `SezioneWebApp` e in cinque test; il beneficio è che nessun chiamante può prendere il token sbagliato senza accorgersene |
| **R-15** ⚠️ **nuovo** | **La finestra di 30 secondi.** `INTERVALLO_RETE` (`app.js:15`) fa sì che una modifica dal telefono resti invisibile alla pagina fino a mezzo minuto. È un fatto, non un difetto — ma è la ragione per cui il `409` è obbligatorio e non opzionale |

---

## F. Prerequisiti e task bloccanti

### Nessun refactoring preliminare è necessario

Cinque cose che si sarebbero potute temere e che l'analisi esclude:

- **Nessuna migrazione di schema.** `events` ha già tutte le colonne: è la stessa scrittura dell'app.
- **Nessun asset nuovo** ⇒ `StaticAssets.AMMESSI` (riga 33) non cambia, la superficie servita non
  cresce.
- **Nessuna modifica a `HttpServer`.** Il corpo viaggia dentro `HttpRequest` (§B.1), quindi la firma
  di `gestisci` (riga 45) resta com'è.
- **Nessuna modifica al modulo desktop** (§A.5).
- **Nessuna dipendenza nuova.** `kotlinx.serialization` è già usata in `WebPayload.kt`.

### Verifiche da fare prima di poggiarci sopra

| ID | Verifica | Blocca | Ripiego se va male |
|---|---|---|---|
| **V-A** | **`@Transaction` su metodi con corpo predefinito nelle interfacce DAO di Room KMP.** Serve solo se si preferisse la transazione all'`UPDATE` condizionato | Solo la scelta fra le due strade di §B.7 | `updateIfUnchanged`, che è già la proposta principale e non ha questa incognita |
| **V-B** | **Il conteggio delle righe restituito da un `@Query("UPDATE …")` in Room KMP** con `BundledSQLiteDriver` (desktop) e `AndroidSQLiteDriver` (Android) | **Sì**, R-04 dipende da questo | Un `getById` in transazione, oppure un `Mutex` con il limite dichiarato di §B.7 |
| **V-C** | **`<dialog>` con `showModal()` sui browser bersaglio** (Chromium e Firefox recenti sul computer di casa) | Solo la forma del modulo | Un `<div>` con sovrapposizione, fuori da `#elenco`: la difesa 1 di §C.5 non dipende da `<dialog>` |
| ~~**V-D**~~ | ~~Una scrittura al DAO mentre l'app è chiusa~~ | — | **Decaduta.** D-08 (23/08/2026) esclude del tutto la scrittura ad app chiusa: non c'è più niente da verificare, ed è sparita l'unica incognita senza piano B |

**V-B è ora l'unica davvero bloccante** (V-D è decaduta con D-08), e si chiude in mezz'ora con un test: inserire una riga,
chiamare l'`UPDATE` condizionato due volte con lo stesso `attesoUpdatedAt` e verificare che
restituisca `1` e poi `0`. Va fatta **prima** di scrivere `ScrittureWeb`, non dopo.

### Ordine consigliato

L'ordine di §7 della fase 1 regge, con una precisazione: **V-B va eseguita all'inizio di M1**,
insieme al corpo delle richieste, perché è l'unica incognita che potrebbe cambiare la forma di §B.7 e
quindi le firme che M2 usa.

### Decisioni che restano aperte

| ID | Stato |
|---|---|
| **D-01** | ✅ Confermata: annullare la completazione appena fatta, nessuna vista dei completati |
| **D-02** | ✅ Confermata: `POST /api/eventi` + `PUT /api/eventi/{id}` con `completato` fra i campi |
| **D-08** | ✅ **Chiusa in fase 3**: la scrittura richiede l'app aperta (Activity visibile). Fa decadere V-D e aggiunge un `onStop` a `MainActivity` — vedi §A.4 e §C.6-bis |
| **D-03** | ✅ **Chiusa da §C.8**, e **al contrario** della proposta di fase 1: sola lettura visibile, indirizzo completo dietro una riga a scomparsa. Decisa sull'asimmetria degli errori, non sulla frequenza d'uso |
| **D-04** | ✅ **Chiusa da §B.5**, con i limiti ricavati dal codice. Restano tre numeri *nuovi* che nessuna riga imponeva e che vanno approvati: **200** caratteri di titolo, **2000** di descrizione, **1970–2100** come intervallo delle date |
