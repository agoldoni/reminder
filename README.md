# Promemoria

App Android per la gestione di promemoria ed eventi con notifiche puntuali.

## Funzionalità

- Creazione, modifica ed eliminazione di eventi con data e ora
- Notifiche anticipate configurabili (0, 5, 15, 30, 60 minuti prima)
- Azioni rapide dalla notifica: posticipa di 5 minuti o 1 ora
- Archiviazione degli eventi completati con possibilità di ripristino
- Rischedulazione automatica degli allarmi al riavvio del dispositivo

## Tech Stack

- **Kotlin** 2.0.21
- **Jetpack Compose** con Material Design 3
- **Room** per la persistenza locale (SQLite)
- **Hilt** per la dependency injection
- **Navigation Compose** per la navigazione tra schermate
- Android API 26+ (minSdk 26, targetSdk 34)

## Build

Requisiti: Android SDK, Java 17.

```bash
# Debug
./build.sh debug

# Release (richiede keystore configurato)
./build.sh release

# Pulizia
./build.sh clean
```

Oppure direttamente con Gradle:

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

## Installazione

```bash
# Installa il debug APK su tutti i dispositivi connessi
./install-all.sh

# Build + installazione
./install-all.sh --build
```

## Configurazione Release

Per il build di release è necessario un keystore di firma. Variabili d'ambiente:

| Variabile | Descrizione | Default |
|---|---|---|
| `KEYSTORE_FILE` | Percorso del keystore | `~/.android/release-key.jks` |
| `KEYSTORE_PASSWORD` | Password del keystore | — |
| `KEY_ALIAS` | Alias della chiave | `release` |
| `KEY_PASSWORD` | Password della chiave | — |

## Struttura del progetto

```
app/src/main/java/it/agoldoni/reminder/
├── alarm/       # Scheduling allarmi e notifiche
├── data/        # Database Room (Entity, DAO, migrazioni)
├── di/          # Moduli Hilt
└── ui/          # Schermate Compose, ViewModel, tema, navigazione
```

## Licenza

Copyright (c) Alberto Goldoni
