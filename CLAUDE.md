# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
./build.sh debug                 # Android debug APK
./build.sh release               # Android release APK (requires signing env vars)
./build.sh desktop               # Linux AppImage (requires appimagetool in PATH or $APPIMAGETOOL)
./gradlew :desktopApp:run        # Run the desktop app
./gradlew :shared:desktopTest :desktopApp:test   # Tests
./install-all.sh [--build]       # Install debug APK on all connected devices
```

Release signing requires env vars: `KEYSTORE_FILE` (default `~/.android/release-key.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (default `release`), `KEY_PASSWORD` (defaults to `KEYSTORE_PASSWORD` when unset).

Tests live in `shared/src/jvmSharedTest` (ODS export, date helpers), `shared/src/desktopTest`
(scheduler desktop, migrazione 2→3), `desktopApp/src/test` (autostart, single instance) e
`shared/src/androidInstrumentedTest` (riprogrammazione al boot, richiede emulatore).

## Architecture

Compose Multiplatform project targeting Android and desktop JVM, MVVM throughout.
Package: `it.agoldoni.reminder`.

**Modules:** `:shared` (dominio, dati, ViewModel, schermate), `:androidApp` (Application,
MainActivity, manifest, risorse), `:desktopApp` (main, tray, autostart, packaging).

**Source set di `:shared`:** `commonMain` (Kotlin puro), `jvmSharedMain` (condiviso dai due
target JVM, dove `java.*` è disponibile — `OdsExporter` e le date stanno qui), `androidMain`,
`desktopMain`. Il gruppo `jvmShared` è dichiarato con `applyDefaultHierarchyTemplate`; il
`dependsOn` manuale non è ammesso.

**Layers:**
- **`data/`** — Room KMP (`reminder.db`), single entity `EventEntity` with DAO. Schema is at version 4 (`events` + `peers`); migrations live in `AppDatabase.Companion` and use `SQLiteConnection`, not `SupportSQLiteDatabase`. `MIGRATION_1_2` e `MIGRATION_3_4` sono `object`, mentre la 2→3 è la funzione `migration2to3(deviceId)`: serve l'identità del dispositivo per popolare `origin` delle righe preesistenti. `AppDatabase` needs `@ConstructedBy` plus the `expect object AppDatabaseConstructor`. Driver: `AndroidSQLiteDriver` su Android, `BundledSQLiteDriver` su desktop (dove `sqlite-bundled` è dichiarato solo in `desktopMain`, per non appesantire l'APK). Schema esportato in `shared/schemas/`.
  Dalla v3 l'entità porta i campi della sincronizzazione: `uuid` (identità globale, indice unico), `updatedAt`, `deleted`/`deletedAt` (tombstone) e `origin` (dispositivo di nascita, immutabile). `id` resta la chiave primaria perché è il requestCode dei `PendingIntent`. **La cancellazione è logica**: le letture dell'app filtrano `deleted = 0`, `changedSince()` e `getByUuid()` vedono anche i tombstone. Ogni mutazione riceve l'istante da scrivere in `updatedAt` come parametro (`softDelete(id, nowMillis)`, `markCompleted(id, nowMillis)`, …): il clock è del chiamante, così i test lavorano a tempo virtuale. Chi modifica un evento deve partire dalla riga esistente e usare `copy()`, mai ricostruire l'entità da zero: rigenererebbe `uuid` e perderebbe l'identità vista dagli altri dispositivi.
- **`di/`** — `AppContainer`, container scritto a mano (niente Hilt: non funziona in KMP). Le implementazioni di piattaforma vengono costruite dal modulo applicativo e passate al container; le schermate lo raggiungono via `LocalAppContainer`.
- **`platform/`** — `expect`/interfacce per ciò che cambia fra piattaforme: `AlarmScheduler`, date, colori dinamici, `AppInfo`, `ExportTarget`, `newUuid()`. `localDeviceId()`/`localDeviceName()` vivono nei rispettivi `platform/` di Android e desktop: identità stabile e nome leggibile del dispositivo.
- **`ui/`** — Compose screens with per-screen ViewModels injected via Hilt. Navigation via `NavHost` with string routes: `list`, `edit/{eventId}` (0 = new), `completed`.
- **`alarm/`** — `AlarmScheduler` sets exact alarms via `AlarmManager`. `AlarmReceiver` triggers notifications through `NotificationHelper`. `NotificationActionReceiver` handles snooze (+5min, +1h) and dismiss actions from notifications. `BootReceiver` reschedules all future alarms on device restart.
- **`sync/`** — scoperta dei dispositivi, associazione e canale cifrato. Le parti crittografiche stanno in `jvmSharedMain` (`javax.crypto` c'è su entrambi i target: niente `expect`/`actual`). Curva **P-256** e non X25519, che su Android arriva solo con l'API 31 mentre il minimo qui è 26. HKDF-SHA256 è scritto a mano (non c'è in JCA) e verificato contro i vettori della RFC 5869. L'associazione usa il **confronto a vista**: entrambi i lati derivano dallo scambio lo stesso codice a sei cifre e l'utente conferma di vederlo uguale sui due schermi — un codice digitato da un lato solo sarebbe forzabile offline da chi si mette in mezzo. `SecureChannel` è AES-256-GCM con **una chiave per direzione** (due contatori sulla stessa chiave produrrebbero nonce ripetuti, che in GCM azzerano ogni garanzia) e il numero di sequenza come dato autenticato, così un frame ripetuto o spostato non si decifra. Il `sharedSecret` sta in chiaro nella tabella `peers`: è protetto dai permessi del file, non da una cifratura a riposo.
  Scoperta dei dispositivi sulla rete locale: il contratto (`Discovery`, `DiscoveredPeer`, `DiscoveryStatus`, `Advertisement`) sta in `commonMain` con il tipo di servizio `_promemoria-sync._tcp`; l'attributo TXT `deviceId` porta l'identità. `NsdDiscovery` (Android) tiene un **multicast lock** — senza `CHANGE_WIFI_MULTICAST_STATE` il Wi-Fi scarta gli annunci — e serializza le `resolveService`, che `NsdManager` accetta una alla volta. `JmdnsDiscovery` (desktop) passa a jmdns un indirizzo esplicito da `siteAddress()`: `InetAddress.getLocalHost()` su Linux risolve spesso in `127.0.1.1` e l'annuncio non uscirebbe. Il tipo di servizio va scritto `_promemoria-sync._tcp` per `NsdManager` e `_promemoria-sync._tcp.local.` per jmdns. `PeerDirectory` (commonMain) unisce i peer trovati a quelli inseriti a mano quando mDNS non passa: è una sorgente in più della stessa lista, non una modalità separata.
- **`export/`** — Reminder export to ODS file. `Exporter` restituisce un `ByteArray` (il contratto vive in `commonMain`); la consegna passa da `ExportTarget`: share intent su Android, dialog di salvataggio nativo su desktop. `OdsExporter` writes a minimal ODF 1.2 spreadsheet (ZIP with `mimetype` STORED + `META-INF/manifest.xml` + `content.xml`) by hand — no third-party ODS library, since SODS pulls in `javax.xml.stream` (StAX) which is unavailable on Android. `ExportEventsUseCase` reads events from DAO based on `ExportFilter` (`ALL` / `OPEN_ONLY`), writes the file in `cacheDir/exports/`, and returns a `content://` Uri exposed via `FileProvider` (authority `${applicationId}.fileprovider`, paths in `res/xml/file_paths.xml`). `ShareHelper` builds the `ACTION_SEND` chooser. The `Exporter` interface allows future formats (CSV/XLSX) without rewriting callers.

**Desktop specifics:**
- Allarmi: una coroutine in attesa per evento; `DesktopAlarmScheduler.bootstrap()` riprogramma i futuri e notifica gli scaduti all'avvio.
- Notifiche: `notify-send` (libnotify ≥ 0.8) con azioni `-A`; l'azione scelta arriva su stdout.
- `kotlinx-coroutines-swing` è obbligatoria: senza `Dispatchers.Main` sulla JVM, `viewModelScope` non parte e la UI resta vuota senza errori.
- Database in `~/.local/share/promemoria/` (XDG), autostart in `~/.config/autostart/promemoria.desktop`.
- Identità del dispositivo: file `device-id` accanto al database su desktop, `SharedPreferences` su Android (`localDeviceId()` in entrambi i `platform/`).

**Key conventions:**
- UI language is Italian throughout (labels, messages, date formatting).
- Debug builds use application ID suffix `.debug` and distinct app name.
- `advanceMinutes` on `EventEntity` controls how far before `dateTimeMillis` the alarm fires (alarm time = `dateTimeMillis - advanceMinutes * 60000`).
- Dependencies are managed via version catalog in `gradle/libs.versions.toml`.
- Java 17 source/target compatibility, Kotlin 2.3.21, KSP 2.3.11 (è KSP a fissare la versione di Kotlin), AGP 8.13.2, Compose Multiplatform 1.11.1.
- Le icone vengono da `material-icons-core`: `material-icons-extended` pesa 37 MB e l'unica icona mancante (`Restore`) è ridefinita in `ui/icons/RestoreIcon.kt`.
- Il piano di lavoro del port desktop è in `docs/features/001-desktop-linux/`.
