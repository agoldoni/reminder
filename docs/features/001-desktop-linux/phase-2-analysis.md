# Feature: Desktop Linux — Analisi tecnica (Fase 2)

**Slug:** `desktop-linux`
**Data:** 2026-08-20
**Riferimento:** [phase-1-requirements.md](phase-1-requirements.md)
**Codebase analizzata:** commit `e18dffa` (branch `main`), 25 file Kotlin, 1.628 righe

**Metodo:** lettura integrale dei 25 sorgenti Kotlin, dei file Gradle, del manifest e delle
risorse; interrogazione diretta di Google Maven e Maven Central per le versioni realmente
pubblicate (nessuna versione citata a memoria); probe dell'ambiente desktop della macchina di
sviluppo. Dove un'informazione non è verificabile senza compilare, è marcata come tale.

---

## A. File coinvolti

### A.1 Struttura target

Da single-module a quattro moduli, con un source set intermedio condiviso fra i due target JVM:

```
settings.gradle.kts        → include(":shared", ":androidApp", ":desktopApp")
shared/
  src/commonMain/          dominio, dati, ViewModel, UI Compose, sync (codice puro Kotlin)
  src/jvmSharedMain/       codice che usa java.* condiviso da Android e desktop
  src/androidMain/         allarmi, notifiche, share, permessi
  src/desktopMain/         scheduler in-process, notifiche desktop, tray, autostart, file dialog
androidApp/                Application + Activity + manifest + risorse Android
desktopApp/                main(), packaging AppImage
```

Il source set `jvmSharedMain` non è opzionale: **entrambi i target sono JVM**, quindi un
gruppo intermedio nella gerarchia KMP consente di usare `java.*` in codice condiviso. Senza
di esso, [OdsExporter.kt](../../../app/src/main/java/it/agoldoni/reminder/export/OdsExporter.kt)
(`ZipOutputStream`, `CRC32`, `SimpleDateFormat`) e tutta la formattazione date delle schermate
andrebbero riscritti su `kotlinx-io` / `kotlinx-datetime`. Con esso, migrano invariati.

### A.2 Mappa dei file esistenti

| File attuale | Destinazione | Modifica | Motivazione |
|---|---|---|---|
| `ui/list/EventListScreen.kt` | commonMain | modifica | usa `BuildConfig` (righe 124-128) e `SimpleDateFormat`/`Calendar` (229-239): i primi vanno dietro un `AppInfo` expect, le seconde dietro un formatter con `actual` in `jvmSharedMain` |
| `ui/list/EventListViewModel.kt` | commonMain | modifica | `AndroidViewModel` → `ViewModel` multipiattaforma; `@HiltViewModel`/`@Inject` → DI KMP; `AlarmScheduler.cancel(getApplication(), …)` → interfaccia iniettata; `ShareHelper` → `ExportTarget` per piattaforma |
| `ui/edit/EventEditScreen.kt` | commonMain | modifica | righe 3-6 e 77-81: `Manifest.permission.POST_NOTIFICATIONS` + `rememberLauncherForActivityResult` → `expect @Composable fun EnsureNotificationPermission()` (no-op su desktop). `DatePickerDialog`/`TimePickerDialog` sono Material3 e il secondo è già un composable privato locale (riga 257): portabili così come sono |
| `ui/edit/EventEditViewModel.kt` | commonMain | modifica | `SavedStateHandle` resta disponibile con navigation multipiattaforma; rimozione Hilt e `AndroidViewModel` |
| `ui/completed/CompletedScreen.kt` | commonMain | modifica | solo formattazione date |
| `ui/completed/CompletedViewModel.kt` | commonMain | modifica | come sopra |
| `ui/navigation/NavGraph.kt` | commonMain | modifica | import da `androidx.navigation.*` a `org.jetbrains.androidx.navigation.*`; rotte invariate |
| `ui/theme/Theme.kt` | commonMain | modifica | righe 20-22: `dynamicDarkColorScheme(LocalContext.current)` è Android-only → `expect fun dynamicColorSchemeOrNull(dark: Boolean): ColorScheme?`, `null` su desktop |
| `ui/theme/Type.kt` | commonMain | invariato | 5 righe, nessuna API di piattaforma |
| `data/EventEntity.kt` | commonMain | modifica | schema v3 per la sync (vedi §B.1) |
| `data/EventDao.kt` | commonMain | modifica | soft-delete e query per la replica (vedi §B.2) |
| `data/AppDatabase.kt` | commonMain | modifica | Room KMP richiede `@ConstructedBy(...)` + `expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase>`; aggiunta migrazione 2→3 e tabella `peers`; `exportSchema` da `false` a `true` |
| `export/Exporter.kt` | jvmSharedMain | invariato | l'interfaccia espone `OutputStream`: resta valida nel source set JVM condiviso |
| `export/ExportFilter.kt` | commonMain | invariato | enum puro |
| `export/OdsExporter.kt` | jvmSharedMain | invariato* | *solo rimozione di `@Inject` (riga 21) |
| `export/ExportEventsUseCase.kt` | commonMain + actual | riscrittura parziale | oggi dipende da `Context`, `FileProvider`, `android.util.Log`: la scelta eventi e la generazione del nome file diventano comuni, la destinazione diventa `ExportTarget` per piattaforma |
| `export/ShareHelper.kt` | androidMain | modifica | diventa l'`actual` Android di `ExportTarget` (share intent); su desktop l'omologo è un dialog di salvataggio |
| `alarm/AlarmScheduler.kt` | androidMain | modifica | da `object` con `Context` in firma a `actual class` dell'interfaccia comune |
| `alarm/AlarmReceiver.kt` | androidMain | invariato | — |
| `alarm/NotificationHelper.kt` | androidMain | invariato | — |
| `alarm/NotificationActionReceiver.kt` | androidMain | invariato | — |
| `alarm/BootReceiver.kt` | androidMain | **modifica obbligatoria** | righe 18-20: apre un secondo database Room **senza `addMigrations(...)`** — vedi rischio R15 |
| `di/AppModule.kt` | — | eliminato | sostituito dal container DI multipiattaforma |
| `ReminderApp.kt` | androidApp | modifica | via `@HiltAndroidApp`, dentro l'inizializzazione del container e del canale notifiche |
| `ui/MainActivity.kt` | androidApp | modifica | via `@AndroidEntryPoint` |
| `app/src/main/AndroidManifest.xml` | androidApp | modifica | invariato nei permessi; cambiano i percorsi delle classi |
| `res/**` (9 file) | androidApp | invariato | `strings.xml` contiene solo `app_name`; **nessun uso di `stringResource`** nel codice (verificato): le stringhe UI sono letterali nei composable e migrano senza lavoro |

### A.3 File nuovi

| File | Modulo | Contenuto |
|---|---|---|
| `shared/build.gradle.kts` | shared | target `androidTarget()` + `jvm("desktop")`, gerarchia custom con `jvmShared`, plugin CMP/Room/KSP |
| `platform/AlarmScheduler.kt` | commonMain | interfaccia comune (schedule/cancel/scheduleSnooze) |
| `platform/Notifier.kt` | commonMain | interfaccia notifiche con azioni |
| `platform/AppInfo.kt` | commonMain | `expect` per autore/versione/data build (oggi da `BuildConfig`) |
| `platform/DateFormat.kt` | commonMain + jvmSharedMain | `expect`/`actual` per le tre formattazioni in uso (`dd/MM/yyyy HH:mm`, `dd/MM/yyyy`, `HH:mm`) |
| `platform/ExportTarget.kt` | commonMain | destinazione dell'export (share su Android, dialog su desktop) |
| `platform/DatabaseFactory.kt` | commonMain | `expect` builder Room; su desktop percorso XDG `~/.local/share/promemoria/reminder.db` |
| `sync/SyncModels.kt` | commonMain | DTO serializzabili degli eventi e delle risposte |
| `sync/SyncProtocol.kt` | commonMain | messaggi HELLO / PAIR / PULL / PUSH / ACK |
| `sync/SyncEngine.kt` | commonMain | merge LWW, tombstone, riprogrammazione allarmi |
| `sync/PeerDiscovery.kt` | commonMain + actual | `NsdManager` su Android, `jmdns` su desktop |
| `sync/SyncTransport.kt` | commonMain + actual | socket TLS + handshake |
| `data/PeerEntity.kt` + `PeerDao.kt` | commonMain | dispositivo associato, chiave, ultimo sync |
| `ui/sync/SyncScreen.kt` + `SyncViewModel.kt` | commonMain | stato sincronizzazione, pairing, sync manuale |
| `desktop/Main.kt` | desktopMain | `application { Window(...) }`, wiring DI |
| `desktop/DesktopAlarmScheduler.kt` | desktopMain | timer in-process + recupero scadenze all'avvio |
| `desktop/DesktopNotifier.kt` | desktopMain | notifica di sistema con azioni |
| `desktop/TrayController.kt` | desktopMain | icona tray, menù, chiudi-a-tray |
| `desktop/AutostartManager.kt` | desktopMain | scrittura/rimozione `~/.config/autostart/promemoria.desktop` |
| `desktop/SingleInstanceLock.kt` | desktopMain | lock file + risveglio della finestra esistente |
| `desktopApp/build.gradle.kts` | desktopApp | `jpackage --type app-image` + wrapping AppImage |

---

## B. Contratti e interfacce da modificare

### B.1 `EventEntity` — schema v2 → v3 (breaking a livello di DB)

Oggi ([EventEntity.kt](../../../app/src/main/java/it/agoldoni/reminder/data/EventEntity.kt)):

```kotlin
@PrimaryKey(autoGenerate = true) val id: Long = 0,
val title: String, val description: String?, val dateTimeMillis: Long,
val advanceMinutes: Int = 0, val completed: Boolean = false
```

`id` è autoincrementale locale: due istanze generano lo stesso `id` per eventi diversi, quindi
**non è utilizzabile come identità di sincronizzazione**. Non va però sostituito: è usato come
`requestCode` dei `PendingIntent` ([AlarmScheduler.kt:24](../../../app/src/main/java/it/agoldoni/reminder/alarm/AlarmScheduler.kt#L24),
`event.id.toInt()`) e come id di notifica
([NotificationHelper.kt:28](../../../app/src/main/java/it/agoldoni/reminder/alarm/NotificationHelper.kt#L28)).

Proposta: **doppia identità** — `id` locale invariato (chiave degli allarmi), `uuid` globale
per la replica.

| Campo nuovo | Tipo | Note |
|---|---|---|
| `uuid` | `String` | identità globale, indice unico, generato alla creazione |
| `updatedAt` | `Long` | epoch UTC ms dell'ultima modifica; base della regola "ultima scrittura vince" |
| `deleted` | `Boolean` | tombstone; conservato per sempre (decisione utente) |
| `deletedAt` | `Long?` | istante della cancellazione |
| `origin` | `String` | id del dispositivo che ha prodotto l'ultima modifica (diagnostica) |

Migrazione 2→3 in `AppDatabase.Companion`, accanto a `MIGRATION_1_2`: `ALTER TABLE` per le
cinque colonne, poi popolamento degli `uuid` esistenti con SQL puro
(`lower(hex(randomblob(4)))||'-'||…`) e `updatedAt = dateTimeMillis` come valore iniziale.
Nessuna perdita di dati, nessun impatto sulla UI.

### B.2 `EventDao` — firme che cambiano

| Metodo attuale | Cambiamento |
|---|---|
| `getActiveSortedAsc()` / `getCompletedSortedDesc()` | aggiungere `AND deleted = 0` |
| `getAll()` / `getAllOpen()` (usati dall'export) | escludere i tombstone |
| `delete(event)` | da `@Delete` fisica a soft-delete (`deleted = 1`, `deletedAt`, `updatedAt`) — **breaking**: i due chiamanti sono `EventListViewModel.delete` e `CompletedViewModel.delete` |
| `insert` / `update` / `markCompleted` / `markActive` | devono aggiornare `updatedAt` |
| `getFutureEvents(now)` | invariata nella semantica, filtra i tombstone |
| *(nuovi)* `changedSince(millis)`, `getByUuid(uuid)`, `upsertFromRemote(...)` | necessari alla replica |

### B.3 `AlarmScheduler` — da oggetto statico a dipendenza

Oggi è un `object` che riceve il `Context` in ogni chiamata, invocato direttamente dai
ViewModel via `getApplication()`. Diventa:

```kotlin
interface AlarmScheduler {
    fun schedule(event: EventEntity)
    fun cancel(eventId: Long)
    fun scheduleSnooze(eventId: Long, title: String, description: String?, snoozeMinutes: Int)
}
```

iniettata nei tre ViewModel. È la modifica che sblocca la rimozione di `AndroidViewModel`.

### B.4 `ExportEventsUseCase` — ritorno non più `Uri`

Oggi ritorna `Result<Uri>` da `FileProvider`. Diventa `Result<ExportedFile>` (percorso +
mime type), e la consegna passa a `ExportTarget`: share intent su Android (l'attuale
`ShareHelper` con `FileProvider`, invariato nella sostanza), dialog di salvataggio su desktop.
`EmptyExportException` e `ExportUiState` restano come sono.

### B.5 Nuovi contratti di sincronizzazione

- Tabella `peers`: `deviceId`, `displayName`, `publicKey`/`sharedSecret`, `lastHost`, `lastPort`, `lastSyncAt`, `pairedAt`.
- Servizio mDNS annunciato come `_promemoria-sync._tcp` con TXT record `deviceId` e versione di protocollo.
- Messaggi (kotlinx.serialization su canale cifrato): `HELLO`, `PAIR_REQUEST`, `PAIR_CONFIRM`, `PULL(since)`, `PUSH(events)`, `ACK(highWatermark)`.
- Versione di protocollo esplicita nel primo messaggio: un peer con versione diversa rifiuta invece di corrompere i dati.

### B.6 Stack tecnico — versioni verificate il 2026-08-20

| Componente | Oggi | Target proposto | Fonte |
|---|---|---|---|
| Kotlin | 2.0.21 | ≥ 2.2 (ultima stabile **2.4.10**) | Maven Central |
| AGP | 8.7.3 | ≥ 8.9, ultima **9.3.1** | Google Maven |
| Compose | BOM androidx `2024.12.01` | Compose Multiplatform **1.11.1** (1.12.0-rc01 in RC) | Maven Central |
| Room | 2.6.1, solo Android | **2.8.4**; artefatti KMP (`room-runtime-android`, `room-runtime-jvm`) disponibili da **2.7.0** | Google Maven |
| Driver SQLite | implicito | `androidx.sqlite:sqlite-bundled` **2.7.0** (varianti `-android` / `-jvm` pubblicate) | Google Maven |
| Navigation Compose | androidx 2.8.5 | `org.jetbrains.androidx.navigation:navigation-compose` **2.9.2** (stabile) | Maven Central |
| ViewModel Compose | androidx lifecycle 2.8.7 | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` **2.11.0** | Maven Central |
| DI | Hilt 2.53.1 | Koin **4.2.2** oppure container manuale | Maven Central |
| mDNS | — | `org.jmdns:jmdns` **3.6.3** (desktop) + `NsdManager` (Android) | Maven Central |

> Le versioni esistono e sono pubblicate: è verificato. **Non** è verificato quale terna
> Kotlin/AGP/CMP compili insieme in questo progetto — è esattamente il deliverable di M0.

---

## C. Pattern da rispettare

Rilevati leggendo il codice, non ipotizzati:

1. **UI in italiano, stringhe letterali nei composable.** Nessun `stringResource`; `strings.xml`
   contiene solo `app_name`. Mantenere lo stile: niente introduzione di un sistema di risorse.
2. **DAO reattivo in lettura, `suspend` in scrittura** (`Flow` per le liste, `suspend` per gli
   update). Le nuove query di sync seguono la stessa regola.
3. **Un ViewModel per schermata**, stato esposto con `MutableStateFlow` privato + `asStateFlow()`,
   liste con `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())`.
4. **Stati UI come `sealed interface` con `data object`**, sul modello di `ExportUiState`
   ([EventListViewModel.kt:74-79](../../../app/src/main/java/it/agoldoni/reminder/ui/list/EventListViewModel.kt#L74-L79)):
   lo stato della sync userà la stessa forma.
5. **Package per feature** (`alarm`, `data`, `di`, `export`, `ui/<schermata>`), file
   `XxxScreen.kt` + `XxxViewModel.kt`. I nuovi `sync` e `platform` si allineano.
6. **Migrazioni Room come costanti in `AppDatabase.Companion`** — la 2→3 va lì.
7. **Interfaccia + implementazione per le astrazioni** (`Exporter`/`OdsExporter`): stesso schema
   per `AlarmScheduler`, `Notifier`, `ExportTarget`.
8. **Dipendenze solo via version catalog** `gradle/libs.versions.toml`, mai versioni inline.
9. **Convenzioni di progetto già scritte:** buildType `debug` con `applicationIdSuffix ".debug"`,
   icona launcher con background per buildType (blu debug / verde release), firma release da
   variabili d'ambiente, script `build.sh` / `install-all.sh`. Il modulo desktop deve estendere
   `build.sh` (`./build.sh desktop`) invece di introdurre un secondo script.
10. **Documentazione:** `CLAUDE.md` descrive l'architettura a layer e va aggiornato con i moduli.

---

## D. Test da creare o aggiornare

**Stato attuale: nessun test.** `app/src/` contiene solo `debug/`, `main/`, `release/`; non
esiste alcun source set di test, coerentemente con quanto dichiarato in `CLAUDE.md`.
Introdurli è un prerequisito, non un optional: la sincronizzazione è la prima parte del
progetto non verificabile a occhio.

| Area | Tipo | Dove | Contenuto |
|---|---|---|---|
| Merge sync | unit | `shared/src/commonTest` | LWW su modifiche concorrenti, tombstone che non risorge, idempotenza di due PUSH identici, clock skew entro tolleranza |
| Protocollo | unit | `shared/src/commonTest` | serializzazione/deserializzazione dei messaggi, rifiuto di versione incompatibile |
| Pairing | unit | `shared/src/commonTest` | peer non associato rifiutato, codice errato rifiutato, credenziali persistite |
| Migrazione 2→3 | strumentale | `shared/src/androidInstrumentedTest` | `MigrationTestHelper`: dati v2 conservati, `uuid` popolati e univoci — **richiede `exportSchema = true`** (oggi `false`, [AppDatabase.kt:8](../../../app/src/main/java/it/agoldoni/reminder/data/AppDatabase.kt#L8)) |
| Export ODS | unit | `shared/src/jvmSharedTest` | golden test sullo ZIP: `mimetype` STORED e primo entry, `content.xml` con le righe attese, filtro `OPEN_ONLY` |
| Formattazione date | unit | `shared/src/jvmSharedTest` | le tre maschere in uso, locale `it` |
| Scheduler desktop | unit | `shared/src/desktopTest` | scadenza futura programmata, scadenza passata → recupero all'avvio, snooze +5/+60 |
| Autostart / istanza singola | unit | `shared/src/desktopTest` | creazione e rimozione del `.desktop`, secondo avvio che non duplica il processo |
| Discovery + sync end-to-end | integrazione | `desktopTest` | due istanze desktop sulla stessa macchina che si scoprono, si associano e convergono |
| Non-regressione Android | manuale | matrice in Fase 3 | allarme, snooze da notifica, riavvio device, export/share, aggiornamento sopra installazione esistente |

---

## E. Rischi tecnici aggiornati

Rispetto alla Fase 1, con evidenze dal codice. **R15-R17 sono nuovi**, emersi dalla lettura.

| # | Aggiornamento |
|---|---|
| R1 (Hilt) | **Confermato, superficie misurata:** 12 punti da toccare — `@HiltAndroidApp` (ReminderApp.kt), `@AndroidEntryPoint` (MainActivity.kt), 3 `@HiltViewModel`, `@Module` AppModule.kt, 4 `@Inject constructor` (OdsExporter, ExportEventsUseCase, ShareHelper), `hiltViewModel()` nelle 3 schermate. Con questi numeri un container manuale è realistico quanto Koin |
| R2 (Room KMP) | **Ridimensionato:** gli artefatti KMP esistono e sono stabili (Room 2.8.4, `room-runtime-jvm` da 2.7.0; `sqlite-bundled` 2.7.0). Resta da verificare la terna Kotlin/AGP/CMP: il salto è ampio (Kotlin 2.0.21 → ≥2.2, AGP 8.7.3 → ≥8.9) |
| R3 (ViewModel/Navigation) | **Ridimensionato:** esistono versioni **stabili** multipiattaforma (navigation-compose 2.9.2, lifecycle-viewmodel-compose 2.11.0). Nessun ricorso ad alpha necessario |
| R4 (background Android) | Confermato: il desktop resta in ascolto, Android sincronizza in foreground |
| R5 (mDNS Android) | Confermato, più il multicast lock |
| R7 (chiavi primarie) | **Risolto in progettazione:** doppia identità `id` locale + `uuid` globale (§B.1), che preserva l'uso di `id` come requestCode degli allarmi |
| R9 (tombstone) | Confermato, retention illimitata |
| R11 (AppImage) | Confermato: `jpackage` presente (JDK 21.0.11-tem), `appimagetool` da installare |
| R13 (`applicationVariants`) | **Aggravato:** l'ultima AGP è la 9.3.1 e AGP 9 rimuove `applicationVariants`. Se M0 impone AGP 9.x, il rename dell'APK ([app/build.gradle.kts:49-58](../../../app/build.gradle.kts#L49-L58)) va riscritto **subito**, non "in futuro" |
| R14 (tray) | ✅ **Decaduto (2026-08-20):** target confermato Cinnamon/X11. la macchina di sviluppo è Cinnamon/X11, con applet in tray già attive. Su Cinnamon la tray funziona senza estensioni |
| **R15** | **Bug latente in `BootReceiver`** ([BootReceiver.kt:18-20](../../../app/src/main/java/it/agoldoni/reminder/alarm/BootReceiver.kt#L18-L20)): apre un secondo `Room.databaseBuilder` **senza `addMigrations(MIGRATION_1_2)`**. Oggi un device fermo alla v1 va in `IllegalStateException` al boot; con l'arrivo della v3 il problema si estende a **tutti** i device non ancora migrati. Inoltre istanzia un secondo database mentre il container ne fornisce già uno singleton. Da correggere prima di M8 |
| **R16** | **Collisioni di id notifica/allarme**: `eventId.toInt()` come id notifica e `notificationId * 10 + n` come requestCode ([NotificationHelper.kt:28,35,48,61](../../../app/src/main/java/it/agoldoni/reminder/alarm/NotificationHelper.kt#L28-L61)) collidono con id grandi o vicini (es. id 1 e 10). Con la sync gli id locali crescono più in fretta: adottare uno spazio di requestCode disgiunto (es. `id * 4 + slot`) |
| **R17** | **Richiesta permesso notifiche nel posto sbagliato**: `POST_NOTIFICATIONS` è chiesto in `LaunchedEffect` dentro la schermata di modifica ([EventEditScreen.kt:77-81](../../../app/src/main/java/it/agoldoni/reminder/ui/edit/EventEditScreen.kt#L77-L81)), quindi a ogni apertura dell'editor e mai altrove. Nel passaggio a `expect/actual` conviene spostarla all'avvio dell'app Android |

**Rischio ridotto rispetto alle attese:** l'assenza di `stringResource` e la presenza di un
`TimePickerDialog` già scritto a mano in Compose eliminano due lavori che erano dati per
probabili (migrazione risorse, sostituzione dei dialog Android).

---

## F. Prerequisiti e task bloccanti

| # | Task | Blocca | Note |
|---|---|---|---|
| P1 | **M0 — prototipo di allineamento versioni**: progetto minimo con Kotlin/AGP/CMP/Room KMP che compili su Android e desktop e apra un DB Room su entrambi | tutto | senza questo ogni stima successiva è ipotetica |
| P2 | ✅ **fatto (2026-08-20)** — **`exportSchema = true`** in `@Database` + `room.schemaLocation`, con commit dello schema v2 corrente | M8, test di migrazione | oggi `false`: senza schema esportato la migrazione 2→3 non è testabile |
| P3 | **Creare i source set di test** (`commonTest`, `jvmSharedTest`, `desktopTest`, `androidInstrumentedTest`) | D | oggi non esiste alcun test |
| P4 | ✅ **fatto (2026-08-20)** — **Correggere `BootReceiver`** (R15): riusare il database del container e registrare tutte le migrazioni | M8 | fix piccolo, da fare prima di introdurre la v3 |
| P5 | **Congelare la strategia di identità** `id` locale + `uuid` globale (§B.1) | M8, M10 | cambiarla dopo la migrazione costa una seconda migrazione |
| P6 | **Riscrivere il rename APK con la Variant API** se M0 impone AGP 9.x | M1 | `applicationVariants` non esiste più in AGP 9 |
| P7 | **Installare `appimagetool`** sulla macchina di build | M7 | assente (verificato) |
| P8 | ✅ **chiuso (2026-08-20)** — target confermato: **Cinnamon / X11**, la macchina di sviluppo | — | tray nativa, nessuna estensione necessaria |
| P9 | ✅ **chiuso (2026-08-20)** — DI: **container manuale**, niente Koin | — | 12 punti di iniezione non giustificano una dipendenza in più |

**R16 e R17: ✅ risolti il 2026-08-20** insieme a P2 e P4, prima dell'apertura del cantiere KMP.

---

## Note di scostamento dalla Fase 1

- **Stima invariata**: le semplificazioni (niente risorse da migrare, navigation/lifecycle
  stabili, dialog già portabili) compensano i due prerequisiti nuovi (P2, P4) e l'eventuale
  riscrittura del rename APK (P6).
- **Un dato da confermare**: l'ambiente desktop resta l'unica incognita di prodotto (P8);
  tutto il resto è verificato sul codice o sui repository.
