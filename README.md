# Promemoria

App per la gestione di promemoria ed eventi con notifiche puntuali, su **Android** e
**desktop Linux**, da un'unica codebase Compose Multiplatform.

## Funzionalità

- Creazione, modifica ed eliminazione di eventi con data e ora
- Notifiche anticipate configurabili (0, 5, 15, 30, 60 minuti prima)
- Azioni rapide dalla notifica: posticipa di 5 minuti o 1 ora
- Archiviazione degli eventi completati con possibilità di ripristino
- Rischedulazione automatica degli allarmi al riavvio del dispositivo
- Export dei promemoria in formato ODS (condivisione su Android, salvataggio su desktop)
- Su desktop: icona nella system tray, chiusura-a-tray, istanza singola, avvio al login e
  recupero delle scadenze maturate ad app spenta

## Tech Stack

- **Kotlin** 2.3.21 · **Compose Multiplatform** 1.11.1 con Material Design 3
- **Room** 2.8.4 (KMP) per la persistenza locale: SQLite di sistema su Android, driver bundled su desktop
- **Navigation** e **ViewModel** multipiattaforma
- Dependency injection con un container scritto a mano (nessun framework)
- Android API 26+ (minSdk 26, targetSdk 34) · desktop JVM 17+

## Moduli

| Modulo | Contenuto |
|---|---|
| `:shared` | dominio, dati, ViewModel e schermate Compose condivisi |
| `:androidApp` | `Application`, `MainActivity`, manifest e risorse Android |
| `:desktopApp` | `main()`, tray, autostart, istanza singola, packaging AppImage |

Dentro `:shared` i source set sono `commonMain` (codice puro Kotlin), `jvmSharedMain` (condiviso
dai due target JVM, dove `java.*` è disponibile), `androidMain` e `desktopMain`.

## Build

Requisiti: Android SDK, Java 17.

```bash
# Android debug
./build.sh debug

# Android release (richiede keystore configurato)
./build.sh release

# Desktop Linux: AppImage singolo file
./build.sh desktop

# Pulizia
./build.sh clean
```

Oppure direttamente con Gradle:

```bash
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:assembleRelease
./gradlew :desktopApp:run                 # avvia l'app desktop
./gradlew :desktopApp:createDistributable # solo la cartella autoconsistente
./gradlew :shared:desktopTest :desktopApp:test   # test
```

### AppImage

`./build.sh desktop` richiede **appimagetool**, che non è nei repository delle distribuzioni:
scaricalo dalle [release ufficiali](https://github.com/AppImage/appimagetool/releases), rendilo
eseguibile e mettilo nel PATH — oppure indicane il percorso:

```bash
APPIMAGETOOL=~/bin/appimagetool ./build.sh desktop
```

L'AppImage risultante (~70 MB, runtime Java incluso) si trova in
`desktopApp/build/appimage/` e non richiede installazione: basta renderlo eseguibile e lanciarlo.
Il database desktop vive in `~/.local/share/promemoria/reminder.db`.

## Installazione

```bash
# Installa il debug APK su tutti i dispositivi connessi
./install-all.sh

# Build + installazione
./install-all.sh --build
```

## Emulatore e dispositivi

```bash
# Elenca i dispositivi/emulatori connessi
adb devices -l

# Elenca gli AVD (Android Virtual Device) configurati
emulator -list-avds

# Avvia un emulatore per nome
emulator -avd <nome_avd>

# Avvia un emulatore in background, senza audio e con wipe dei dati
emulator -avd <nome_avd> -no-audio -wipe-data &

# Termina tutti gli emulatori in esecuzione
adb emu kill

# Disinstalla l'app debug da un dispositivo specifico
adb -s <device_id> uninstall it.agoldoni.reminder.debug
```

## Configurazione Release

Per il build di release è necessario un keystore di firma. Variabili d'ambiente:

| Variabile | Descrizione | Default |
|---|---|---|
| `KEYSTORE_FILE` | Percorso del keystore | `~/.android/release-key.jks` |
| `KEYSTORE_PASSWORD` | Password del keystore | — |
| `KEY_ALIAS` | Alias della chiave | `release` |
| `KEY_PASSWORD` | Password della chiave | valore di `KEYSTORE_PASSWORD` |

## Struttura del progetto

```
shared/src/
├── commonMain/it/agoldoni/reminder/
│   ├── data/       # Room: entity, DAO, database e migrazioni
│   ├── di/         # AppContainer, il container delle dipendenze
│   ├── export/     # Export ODS: contratti e use case
│   ├── platform/   # expect: allarmi, date, colori, info di build
│   └── ui/         # Schermate Compose, ViewModel, tema, navigazione
├── jvmSharedMain/  # actual comuni ai due target JVM (OdsExporter, date)
├── androidMain/    # AlarmManager, notifiche, receiver, condivisione
└── desktopMain/    # scheduler in-process, notifiche notify-send, database XDG

androidApp/     # Application, MainActivity, manifest, risorse
desktopApp/     # main(), tray, autostart, istanza singola, packaging
```

## Licenza

Copyright (c) Alberto Goldoni
