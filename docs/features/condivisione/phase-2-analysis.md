# Feature: Condivisione — Analisi tecnica codebase (Fase 2)

**Slug:** `condivisione`
**Data:** 2026-04-26
**Stato:** Bozza in attesa di approvazione

---

## Sintesi delle decisioni Fase 1

- **Cloud:** share intent generico (`ACTION_SEND` + `FileProvider`).
- **Colonne ODS:** `Titolo | Descrizione | Data e ora | Anticipo (min) | Stato`.
- **Naming file:** `promemoria_YYYYMMDD.ods`.
- **Foglio metadati:** assente.

---

## A. File coinvolti

### Nuovi file

| Percorso | Tipo | Motivazione |
|---|---|---|
| `app/src/main/java/it/agoldoni/reminder/export/Exporter.kt` | nuovo | Interfaccia astratta `Exporter` per consentire futuri formati (CSV/XLSX) senza riscrivere il chiamante |
| `app/src/main/java/it/agoldoni/reminder/export/OdsExporter.kt` | nuovo | Implementazione concreta che genera il file `.ods` da una `List<EventEntity>` |
| `app/src/main/java/it/agoldoni/reminder/export/ExportFilter.kt` | nuovo | Enum `ExportFilter { ALL, OPEN_ONLY }` |
| `app/src/main/java/it/agoldoni/reminder/export/ExportEventsUseCase.kt` | nuovo | Orchestrazione: legge da DAO secondo filtro, invoca exporter, ritorna `Uri` del file in cache |
| `app/src/main/java/it/agoldoni/reminder/export/ShareHelper.kt` | nuovo | Costruisce `Intent.ACTION_SEND` + chooser per il `Uri` esposto via `FileProvider` |
| `app/src/main/res/xml/file_paths.xml` | nuovo | Path `cache-path` per il `FileProvider` (cartella `cache/exports/`) |

### File modificati

| Percorso | Tipo | Motivazione |
|---|---|---|
| [app/src/main/java/it/agoldoni/reminder/data/EventDao.kt](app/src/main/java/it/agoldoni/reminder/data/EventDao.kt) | modifica | Aggiungere query `suspend fun getAll(): List<EventEntity>` (oggi non c'è — solo `getActiveSortedAsc()` e `getCompletedSortedDesc()` come `Flow`, e `getById`). Serve una variante non-Flow per l'export "Tutti" |
| [app/src/main/java/it/agoldoni/reminder/data/EventDao.kt](app/src/main/java/it/agoldoni/reminder/data/EventDao.kt) | modifica | Aggiungere `suspend fun getAllOpen(): List<EventEntity>` (lista snapshot, non Flow) per export "Solo aperti" |
| [app/src/main/java/it/agoldoni/reminder/ui/list/EventListScreen.kt](app/src/main/java/it/agoldoni/reminder/ui/list/EventListScreen.kt) | modifica | Aggiungere voce icona "Esporta" (es. `Icons.Default.Share` o `IosShare`) nelle `actions` della `TopAppBar` (riga 99-103); aggiungere dialog Compose per scegliere il filtro |
| [app/src/main/java/it/agoldoni/reminder/ui/list/EventListViewModel.kt](app/src/main/java/it/agoldoni/reminder/ui/list/EventListViewModel.kt) | modifica | Iniettare `ExportEventsUseCase` + `ShareHelper`; esporre `fun export(filter: ExportFilter)` + `StateFlow<ExportUiState>` per loading/success/error |
| [app/src/main/java/it/agoldoni/reminder/di/AppModule.kt](app/src/main/java/it/agoldoni/reminder/di/AppModule.kt) | modifica | `@Provides` per `Exporter` (binding a `OdsExporter`), `ExportEventsUseCase`, `ShareHelper` |
| [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml) | modifica | Dichiarare `<provider>` `androidx.core.content.FileProvider` con authority `${applicationId}.fileprovider` |
| [app/build.gradle.kts](app/build.gradle.kts) | modifica | Aggiungere dipendenza libreria ODS scelta (vedi sezione Rischi) |
| [gradle/libs.versions.toml](gradle/libs.versions.toml) | modifica | Censire la nuova dipendenza nel version catalog |
| [app/src/main/res/values/strings.xml](app/src/main/res/values/strings.xml) | modifica | Stringhe localizzate in italiano: `export_action`, `export_dialog_title`, `export_filter_all`, `export_filter_open`, `export_empty`, `export_error`, `export_chooser_title` |

### Eliminazioni

Nessuna.

---

## B. Contratti e interfacce da modificare

### B.1 — `EventDao` (breaking? no, additivo)

```kotlin
@Query("SELECT * FROM events ORDER BY dateTimeMillis ASC")
suspend fun getAll(): List<EventEntity>

@Query("SELECT * FROM events WHERE completed = 0 ORDER BY dateTimeMillis ASC")
suspend fun getAllOpen(): List<EventEntity>
```

**Nota:** la differenza tra `getAllOpen()` e l'esistente `getActiveSortedAsc()` è solo
il tipo di ritorno: `List` invece di `Flow`. Necessaria perché l'export è
un'operazione one-shot — una `Flow.first()` sarebbe accettabile ma più rumorosa.

### B.2 — Nuova interfaccia `Exporter`

```kotlin
interface Exporter {
    val mimeType: String
    val fileExtension: String
    suspend fun export(events: List<EventEntity>, output: OutputStream)
}
```

**Implementazione `OdsExporter`:**
- `mimeType = "application/vnd.oasis.opendocument.spreadsheet"`
- `fileExtension = "ods"`
- Foglio singolo "Promemoria" con header + N righe.

### B.3 — `ExportEventsUseCase`

```kotlin
class ExportEventsUseCase @Inject constructor(
    private val dao: EventDao,
    private val exporter: Exporter,
    @ApplicationContext private val context: Context
) {
    suspend fun execute(filter: ExportFilter): Result<Uri>
}
```

Restituisce `Result.success(uri)` con `content://...` esposto da `FileProvider`,
oppure `Result.failure(EmptyExportException)` / generico errore I/O.

### B.4 — Nuovo `ExportUiState` nel ViewModel

```kotlin
sealed interface ExportUiState {
    data object Idle : ExportUiState
    data object Loading : ExportUiState
    data class Ready(val uri: Uri) : ExportUiState   // pronto per share
    data class Error(val messageRes: Int) : ExportUiState
}
```

### B.5 — Manifest

Nuovo `<provider>` da inserire dentro `<application>`:

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

`res/xml/file_paths.xml`:
```xml
<paths>
    <cache-path name="exports" path="exports/" />
</paths>
```

**Breaking changes:** nessuno. Solo aggiunte.

---

## C. Pattern da rispettare

Pattern già consolidati nel progetto, verificati nei sorgenti:

1. **Hilt + ViewModel injection.**
   `EventListViewModel` è `@HiltViewModel` con costruttore injection ([EventListViewModel.kt:17-20](app/src/main/java/it/agoldoni/reminder/ui/list/EventListViewModel.kt#L17-L20)).
   `ExportEventsUseCase` e `ShareHelper` vanno aggiunti come parametri nello stesso modo.
   Nessuna repository layer in mezzo: il DAO viene iniettato direttamente — manteniamo questa scelta per coerenza.

2. **Coroutine in `viewModelScope`.**
   Tutte le scritture in `EventListViewModel` usano `viewModelScope.launch { ... }` ([EventListViewModel.kt:25-37](app/src/main/java/it/agoldoni/reminder/ui/list/EventListViewModel.kt#L25-L37)). L'export deve seguire lo stesso schema; la generazione del file dentro l'use case deve usare `withContext(Dispatchers.IO)`.

3. **Lingua italiana ovunque.**
   Tutte le label UI sono in italiano hardcoded ([EventListScreen.kt:66-86](app/src/main/java/it/agoldoni/reminder/ui/list/EventListScreen.kt#L66-L86)) ma anche in `strings.xml` quando usate dal sistema. Per l'export usare `strings.xml` (per consentire futura traduzione) — è un leggero miglioramento rispetto allo stile attuale, da concordare; in alternativa hardcodare come il resto.

4. **Date formatting.**
   `SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())` è il pattern usato in [EventListScreen.kt:139](app/src/main/java/it/agoldoni/reminder/ui/list/EventListScreen.kt#L139). Stesso formato nelle celle ODS.

5. **DAO con `Flow` per UI reattiva, `suspend` per scritture.**
   Convenzione documentata in `CLAUDE.md` e visibile in [EventDao.kt](app/src/main/java/it/agoldoni/reminder/data/EventDao.kt). Le nuove query export sono `suspend` one-shot (no Flow): coerente con i casi `getById` / `getFutureEvents`.

6. **Material 3 + Compose.**
   `TopAppBar` con `actions = { IconButton(...) }` ([EventListScreen.kt:99-103](app/src/main/java/it/agoldoni/reminder/ui/list/EventListScreen.kt#L99-L103)). Aggiungere una seconda `IconButton` accanto a quella esistente "Fatti".

7. **AlertDialog Material 3 per scelta utente.**
   Pattern già usato ([EventListScreen.kt:64-90](app/src/main/java/it/agoldoni/reminder/ui/list/EventListScreen.kt#L64-L90)) — stesso template per il dialog di scelta filtro.

8. **Naming package per layer.**
   La codebase ha package separati: `data/`, `di/`, `ui/`, `alarm/`. Coerente creare un nuovo package `export/` allo stesso livello.

---

## D. Test da creare o aggiornare

⚠️ **Nota:** il progetto **non ha test configurati** (confermato da `CLAUDE.md` e dall'assenza
di moduli `androidTest` / `test`). L'opzione realistica è:

**Opzione consigliata (allineata allo stato attuale del progetto):**
- Nessun test automatizzato.
- Validazione esclusivamente manuale (vedi M6 in Fase 1).

**Opzione estesa (se si vuole introdurre testing con questa feature):**
- `app/src/test/java/.../export/OdsExporterTest.kt` — unit test che verifica struttura del file generato (header, numero righe, mime type).
- `app/src/test/java/.../export/ExportEventsUseCaseTest.kt` — test con DAO fake/in-memory per i due filtri.
- Setup richiesto: aggiungere `junit`, `kotlinx-coroutines-test`, eventualmente `androidx-room-testing` al version catalog e a `app/build.gradle.kts`.

**Decisione da prendere con l'utente** in Fase 3 (sezione "Domande aperte").

---

## E. Rischi tecnici aggiornati

| Rischio | Stato post-analisi | Note |
|---|---|---|
| Nessuna libreria ODS pure-Java compatibile con Android | **Aperto** — bloccante per M1 | `SODS` (com.github.miachm.sods:SODS) è pure-Java JVM, *senza* dipendenze AWT — risulta il candidato più solido. `FastODS` ha invece dipendenza `java.awt.Color` per stili, problematico su Android. **POC obbligatorio** in M1 |
| Aumento APK size | Basso | Stima libreria ODS pura ~200-400 KB. Nessun minify attivo (`isMinifyEnabled = false` su release [app/build.gradle.kts:41](app/build.gradle.kts#L41)) → impatto pieno. Valutare attivazione R8 se diventa un problema |
| `compileSdk = 35` / `minSdk = 26` ([app/build.gradle.kts:11-15](app/build.gradle.kts#L11-L15)) | Risolto | Android 8.0+, `FileProvider` e share intent supportati nativamente. Nessun permesso storage runtime necessario (file in `cacheDir` esposto via FileProvider) |
| `applicationIdSuffix = ".debug"` su build debug ([app/build.gradle.kts:35](app/build.gradle.kts#L35)) | Da gestire | L'authority del FileProvider deve usare `${applicationId}.fileprovider` (con suffix), per evitare conflitti tra debug e release installati insieme. Già garantito usando la variabile manifest |
| Necessità `requestLegacyExternalStorage` | Risolto | Non serve: file solo in cache, share via FileProvider |
| Lista vuota dopo filtro | Mitigato | Use case ritorna `Result.failure(EmptyExportException)`, gestito a UI con messaggio dedicato |
| Override `targetSdk = 34` mentre `compileSdk = 35` | Informativo | Non blocca, ma flaggare al maintainer (out of scope di questa feature) |

---

## F. Prerequisiti e task bloccanti

### Bloccanti per iniziare l'implementazione

1. **POC libreria ODS** (M1)
   Creare un branch spike, aggiungere `com.github.miachm.sods:SODS:1.6.7` (o ultima stabile)
   come dipendenza, generare un file `.ods` di prova e aprirlo in LibreOffice + Google Sheets.
   Se SODS non funziona su Android, valutare alternative (eventualmente generare manualmente
   l'XML interno dell'ODS — costoso ma fattibile, ~1 gg in più).

2. **Decisione su test automatizzati** (vedi sezione D).
   Risposta utente in Fase 3.

### Non bloccanti, ma raccomandati

- **Estrarre stringhe in `strings.xml`** anche per le UI esistenti (debito tecnico noto): out of scope qui, ma andare a posizionare le nuove stringhe della feature direttamente in `strings.xml` (non hardcoded) non aggiunge complessità.
- **Considerare R8/minify** per release: out of scope, ma utile traccia.

### Ordine di esecuzione consigliato

1. POC libreria → conferma fattibilità → merge dipendenza nel version catalog
2. Implementazione `Exporter` + `OdsExporter` (testabile in isolamento)
3. Aggiunta query DAO + use case
4. Configurazione `FileProvider` + manifest + `file_paths.xml`
5. Bottone export + dialog filtro nella UI
6. ViewModel state machine + integrazione `ShareHelper`
7. Test manuale end-to-end su almeno un device fisico
