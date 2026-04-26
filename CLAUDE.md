# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK (requires signing env vars)
./gradlew clean                  # Clean build artifacts
./install-all.sh                 # Install debug APK on all connected devices
./install-all.sh --build         # Build + install
```

Release signing requires env vars: `KEYSTORE_FILE` (default `~/.android/release-key.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (default `release`), `KEY_PASSWORD`.

There are no tests configured in this project.

## Architecture

Single-module Android app (`app/`) using MVVM with Jetpack Compose. Package: `it.agoldoni.reminder`.

**Layers:**
- **`data/`** — Room database (`reminder.db`), single entity `EventEntity` with DAO. Schema is at version 2; migrations live in `AppDatabase.Companion`. The DAO exposes `Flow` for reactive UI and suspend functions for writes.
- **`di/`** — Hilt `@SingletonComponent` module providing `AppDatabase` and `EventDao`.
- **`ui/`** — Compose screens with per-screen ViewModels injected via Hilt. Navigation via `NavHost` with string routes: `list`, `edit/{eventId}` (0 = new), `completed`.
- **`alarm/`** — `AlarmScheduler` sets exact alarms via `AlarmManager`. `AlarmReceiver` triggers notifications through `NotificationHelper`. `NotificationActionReceiver` handles snooze (+5min, +1h) and dismiss actions from notifications. `BootReceiver` reschedules all future alarms on device restart.
- **`export/`** — Reminder export to ODS file. `OdsExporter` writes a minimal ODF 1.2 spreadsheet (ZIP with `mimetype` STORED + `META-INF/manifest.xml` + `content.xml`) by hand — no third-party ODS library, since SODS pulls in `javax.xml.stream` (StAX) which is unavailable on Android. `ExportEventsUseCase` reads events from DAO based on `ExportFilter` (`ALL` / `OPEN_ONLY`), writes the file in `cacheDir/exports/`, and returns a `content://` Uri exposed via `FileProvider` (authority `${applicationId}.fileprovider`, paths in `res/xml/file_paths.xml`). `ShareHelper` builds the `ACTION_SEND` chooser. The `Exporter` interface allows future formats (CSV/XLSX) without rewriting callers.

**Key conventions:**
- UI language is Italian throughout (labels, messages, date formatting).
- Debug builds use application ID suffix `.debug` and distinct app name.
- `advanceMinutes` on `EventEntity` controls how far before `dateTimeMillis` the alarm fires (alarm time = `dateTimeMillis - advanceMinutes * 60000`).
- Dependencies are managed via version catalog in `gradle/libs.versions.toml`.
- Java 17 source/target compatibility, Kotlin 2.0, KSP for Room and Hilt code generation.
