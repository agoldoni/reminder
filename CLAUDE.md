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

Tests live in `shared/src/jvmSharedTest` (ODS export, date helpers) and `desktopApp/src/test`
(autostart, single instance). There is no Android instrumented test source set yet.

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
- **`data/`** — Room KMP (`reminder.db`), single entity `EventEntity` with DAO. Schema is at version 2; migrations live in `AppDatabase.Companion` and use `SQLiteConnection`, not `SupportSQLiteDatabase`. `AppDatabase` needs `@ConstructedBy` plus the `expect object AppDatabaseConstructor`. Driver: `AndroidSQLiteDriver` su Android, `BundledSQLiteDriver` su desktop (dove `sqlite-bundled` è dichiarato solo in `desktopMain`, per non appesantire l'APK). Schema esportato in `shared/schemas/`.
- **`di/`** — `AppContainer`, container scritto a mano (niente Hilt: non funziona in KMP). Le implementazioni di piattaforma vengono costruite dal modulo applicativo e passate al container; le schermate lo raggiungono via `LocalAppContainer`.
- **`platform/`** — `expect`/interfacce per ciò che cambia fra piattaforme: `AlarmScheduler`, date, colori dinamici, `AppInfo`, `ExportTarget`.
- **`ui/`** — Compose screens with per-screen ViewModels injected via Hilt. Navigation via `NavHost` with string routes: `list`, `edit/{eventId}` (0 = new), `completed`.
- **`alarm/`** — `AlarmScheduler` sets exact alarms via `AlarmManager`. `AlarmReceiver` triggers notifications through `NotificationHelper`. `NotificationActionReceiver` handles snooze (+5min, +1h) and dismiss actions from notifications. `BootReceiver` reschedules all future alarms on device restart.
- **`export/`** — Reminder export to ODS file. `Exporter` restituisce un `ByteArray` (il contratto vive in `commonMain`); la consegna passa da `ExportTarget`: share intent su Android, dialog di salvataggio nativo su desktop. `OdsExporter` writes a minimal ODF 1.2 spreadsheet (ZIP with `mimetype` STORED + `META-INF/manifest.xml` + `content.xml`) by hand — no third-party ODS library, since SODS pulls in `javax.xml.stream` (StAX) which is unavailable on Android. `ExportEventsUseCase` reads events from DAO based on `ExportFilter` (`ALL` / `OPEN_ONLY`), writes the file in `cacheDir/exports/`, and returns a `content://` Uri exposed via `FileProvider` (authority `${applicationId}.fileprovider`, paths in `res/xml/file_paths.xml`). `ShareHelper` builds the `ACTION_SEND` chooser. The `Exporter` interface allows future formats (CSV/XLSX) without rewriting callers.

**Desktop specifics:**
- Allarmi: una coroutine in attesa per evento; `DesktopAlarmScheduler.bootstrap()` riprogramma i futuri e notifica gli scaduti all'avvio.
- Notifiche: `notify-send` (libnotify ≥ 0.8) con azioni `-A`; l'azione scelta arriva su stdout.
- `kotlinx-coroutines-swing` è obbligatoria: senza `Dispatchers.Main` sulla JVM, `viewModelScope` non parte e la UI resta vuota senza errori.
- Database in `~/.local/share/promemoria/` (XDG), autostart in `~/.config/autostart/promemoria.desktop`.

**Key conventions:**
- UI language is Italian throughout (labels, messages, date formatting).
- Debug builds use application ID suffix `.debug` and distinct app name.
- `advanceMinutes` on `EventEntity` controls how far before `dateTimeMillis` the alarm fires (alarm time = `dateTimeMillis - advanceMinutes * 60000`).
- Dependencies are managed via version catalog in `gradle/libs.versions.toml`.
- Java 17 source/target compatibility, Kotlin 2.3.21, KSP 2.3.11 (è KSP a fissare la versione di Kotlin), AGP 8.13.2, Compose Multiplatform 1.11.1.
- Le icone vengono da `material-icons-core`: `material-icons-extended` pesa 37 MB e l'unica icona mancante (`Restore`) è ridefinita in `ui/icons/RestoreIcon.kt`.
- Il piano di lavoro del port desktop è in `docs/features/001-desktop-linux/`.
