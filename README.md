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
- **Sincronizzazione fra telefono e PC** sulla rete locale, senza account e senza cloud:
  i dispositivi si trovano da soli, si associano confrontando un codice e restano allineati
- Su desktop: icona nella system tray, chiusura-a-tray, istanza singola, avvio al login e
  recupero delle scadenze maturate ad app spenta

## Sincronizzazione

I dispositivi si parlano **direttamente**: nessun server, nessun account, i promemoria non
lasciano la rete di casa.

### Come si associano

1. Apri la schermata **Sincronizzazione** (icona nella barra) su **entrambi** i dispositivi.
   Finché non lo fai non viene aperto nessun socket e non parte nessun annuncio sulla rete.
2. Aspetta che l'altro dispositivo compaia in «Trovati sulla rete» e premi **Associa**.
3. Su entrambi gli schermi compare **lo stesso numero di sei cifre**. Confrontali: sono uguali?
   Conferma su tutti e due. Se non coincidono qualcuno si è messo in mezzo — annulla.

Il codice **si confronta, non si digita**: è ciò che rende l'associazione sicura. Chi si
intromettesse negozierebbe due scambi distinti e i due numeri non coinciderebbero.

Da lì in poi i due dispositivi restano associati: il telefono sincronizza quando lo apri, il PC
è sempre in ascolto.

### Se non si trovano

La ricerca automatica usa mDNS, che è **link-local**: funziona solo se i due dispositivi sono
sulla **stessa sottorete**. Non passa fra reti diverse, su alcune reti ospiti, e dove
l'access point isola i client fra loro.

In quel caso ogni dispositivo mostra in alto il proprio indirizzo — *«Raggiungibile su
192.168.1.10:47700»* — e con il **+** si inserisce a mano quello dell'altro. Perché funzioni,
i due devono comunque potersi raggiungere via TCP sulla porta **47700**.

### Requisiti di rete

| | |
|---|---|
| Porta | **47700/TCP** sul dispositivo che ascolta |
| Scoperta | mDNS (UDP 5353), servizio `_promemoria-sync._tcp` |
| Chi ascolta | Il **desktop sempre**; il **telefono solo** mentre la schermata di sincronizzazione è aperta — Android non lascia tenere un socket aperto ad app chiusa |
| Cifratura | Canale AES-256-GCM con chiave derivata dall'associazione: chi intercetta non legge i promemoria |
| Dati in uscita | Nessuno verso internet |

Se il telefono non riesce a raggiungere il PC ma il PC raggiunge il telefono (capita con
sottoreti diverse e NAT in mezzo), si può partire dal PC: apri la schermata su entrambi e avvia
l'associazione dal lato che passa.

### Se qualcosa va storto

- **Dissociare** un dispositivo ne cancella le credenziali e interrompe ogni allineamento
  successivo. Si rifà l'associazione da capo quando serve.
- Modifiche fatte offline sui due lati si allineano al primo rientro in rete. A parità di
  evento vince la modifica più recente; le cancellazioni non riappaiono.

## Tech Stack

- **Kotlin** 2.3.21 · **Compose Multiplatform** 1.11.1 con Material Design 3
- **Room** 2.8.4 (KMP) per la persistenza locale: SQLite di sistema su Android, driver bundled su desktop
- Sincronizzazione punto-punto: mDNS (`NsdManager` su Android, `jmdns` su desktop), ECDH P-256 e
  AES-256-GCM da `javax.crypto`, messaggi `kotlinx.serialization` su socket TCP
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

I test di sincronizzazione che riguardano la scoperta usano la **rete reale** della macchina e si
dichiarano saltati dove non ce n'è una utilizzabile.

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

Al primo avvio l'app si registra fra le applicazioni del desktop (una voce in
`~/.local/share/applications/` e l'icona in `~/.local/share/icons/`), così la barra delle
applicazioni le dà la sua icona e la ritrovi nel menu. Spostando o aggiornando l'AppImage la voce
viene riscritta da sola al primo avvio dal nuovo percorso.

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
│   ├── platform/   # expect: allarmi, date, colori, info di build, impostazioni
│   ├── sync/       # Protocollo, regola di merge, motore di replica, contratti di rete
│   └── ui/         # Schermate Compose, ViewModel, tema, navigazione
├── jvmSharedMain/  # actual comuni ai due target JVM: OdsExporter, date, crittografia e trasporto
├── androidMain/    # AlarmManager, notifiche, receiver, condivisione, scoperta NsdManager
└── desktopMain/    # scheduler in-process, notifiche notify-send, database XDG, scoperta jmdns

androidApp/     # Application, MainActivity, manifest, risorse
desktopApp/     # main(), tray, autostart, istanza singola, packaging
```

## Dati e aggiornamenti

Il database locale è a **schema 5** e le migrazioni sono automatiche ma **non reversibili**: una
versione precedente dell'app non apre un database più recente. Prima di aggiornare da una versione
molto vecchia conviene copiare il file del database (`~/.local/share/promemoria/reminder.db` su
desktop) o fare un export ODS.

Gli eventi cancellati restano nel database come *tombstone*, invisibili nell'app: servono a
propagare la cancellazione agli altri dispositivi ed evitare che riappaia al giro dopo.

## Licenza

Copyright (c) Alberto Goldoni
