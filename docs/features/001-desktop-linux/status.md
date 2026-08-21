# Stato del lavoro — port desktop Linux

**Aggiornato:** 2026-08-21
**Branch:** `feature/desktop-linux` (15 commit, non ancora unito in `main`)

---

## Dove siamo

**Tranche 1 — app desktop: completa.** L'app gira su Android e su Linux dalla stessa codebase
Compose Multiplatform, ed è distribuibile come AppImage.

| | Android | Desktop Linux |
|---|---|---|
| Lista, editor, completati | ✅ verificato su device | ✅ verificato con dati reali |
| Allarmi | ✅ `AlarmManager`, verificato in `dumpsys` | ✅ coroutine per evento + recupero all'avvio |
| Notifiche con azioni | ✅ snooze +5 min verificato a runtime | ✅ `notify-send` con azioni, verificato su Cinnamon |
| Export ODS | ✅ share intent, file aperto in LibreOffice | ✅ dialog di salvataggio nativo |
| Tray / autostart | — | ✅ tray, chiusura-a-tray, istanza singola, avvio al login |
| Distribuzione | APK debug 12,2 MB | AppImage 69,6 MB |

**Tranche 2 — sincronizzazione: iniziata.** Lo schema del database è pronto a ospitarla.

| | Stato |
|---|---|
| Schema v3 e migrazione 2→3 (T-17) | ✅ fatto, verificato anche su una copia del database reale |
| Test di migrazione (T-26) | ✅ 4 test in `desktopTest`, senza emulatore |
| Discovery mDNS (T-18) | ✅ fatto, round-trip reale verificato su desktop e cablaggio verificato su emulatore |
| Associazione e canale cifrato (T-19) | ✅ fatto, schema v4 con la tabella `peers` |
| Motore di replica (T-20) | ✅ fatto, convergenza verificata anche su due database veri |
| Integrazione (T-21) | ✅ fatto, tutto collegato all'app e verificato all'avvio su entrambe |
| UI di associazione e stato (T-22) | ✅ fatto, verificata a runtime su emulatore |

**Tutte le funzionalità del piano sono implementate.** Restano i collaudi e la documentazione.

**Avanzamento:** 39,7 gg completati su 46,0 stimati. Restano **6,3 gg**.

**103 test automatici** (prima non ce n'erano): export ODS, formattazione date, scheduler desktop,
autostart, istanza singola, migrazioni di schema, elenco dei dispositivi, round-trip mDNS reale,
primitive crittografiche, associazione e sessione cifrata, regola di merge, convergenza fra due
dispositivi, scambio completo su due database Room veri, giro su socket in ascolto,
orchestrazione col flag spento e ViewModel della schermata; strumentati su emulatore la
riprogrammazione al boot e il cablaggio di `NsdDiscovery`.

## Come si lavora

```bash
./build.sh debug            # APK Android
./build.sh desktop          # AppImage (serve appimagetool nel PATH o in $APPIMAGETOOL)
./gradlew :desktopApp:run   # avvia l'app desktop
./gradlew :shared:desktopTest :desktopApp:test                    # test JVM
ANDROID_SERIAL=emulator-5554 ./gradlew :shared:connectedDebugAndroidTest   # test strumentati
```

## Che cosa è cambiato con lo schema v3

- `EventEntity` porta `uuid` (identità globale, indice unico), `updatedAt`, `deleted`/`deletedAt`
  e `origin`. `id` resta la chiave primaria: è il requestCode dei `PendingIntent`.
- **La cancellazione è logica.** `EventDao.delete()` non esiste più: al suo posto
  `softDelete(id, nowMillis)`, che lascia un tombstone. Tutte le letture dell'app filtrano
  `deleted = 0`; `getByUuid()` e `changedSince()` no, perché la sincronizzazione deve poter
  propagare le cancellazioni.
- **Ogni mutazione riceve l'istante da scrivere in `updatedAt`.** Il clock resta del chiamante,
  come già per `getFutureEvents`/`getOverdueEvents`, così i test lavorano a tempo virtuale.
- **`origin` è il dispositivo di nascita dell'evento, non l'ultimo che l'ha scritto.** Non cambia
  mai dopo l'inserimento, quindi non va aggiornato dalle mutazioni. L'identità del dispositivo è
  persistita per piattaforma da `localDeviceId()`: file `device-id` accanto al database su
  desktop, `SharedPreferences` su Android.
- **Modificare un evento parte sempre dalla riga esistente** (`EventEditViewModel` la rilegge e ci
  fa `copy()`): ricostruire l'entità da zero rigenererebbe l'`uuid` e romperebbe l'identità vista
  dagli altri dispositivi.

## Che cosa c'è nella scoperta dei dispositivi

- Servizio `_promemoria-sync._tcp`, con l'identità nell'attributo TXT `deviceId`. Il tipo va
  scritto **senza** dominio per `NsdManager` e **con** `.local.` finale per jmdns.
- **Android**: senza multicast lock il Wi-Fi scarta gli annunci, e `NsdManager` accetta una sola
  `resolveService` per volta — le richieste passano da una coda.
- **Desktop**: a jmdns va passato un indirizzo esplicito, perché `InetAddress.getLocalHost()` su
  Linux risolve spesso in `127.0.1.1` e l'annuncio non uscirebbe dalla macchina.
- **I permessi di rete stanno nel manifest di `:shared`**, non in quello dell'app: il merger li
  propaga a `:androidApp` e anche all'APK dei test strumentati, che dal manifest dell'app non li
  prenderebbe. È così che è emerso il problema: il test falliva con `SecurityException`.
- Il fallback manuale non è una modalità separata: `PeerDirectory` unisce i dispositivi trovati e
  quelli digitati nella stessa lista, e l'annuncio ha la meglio sull'indirizzo digitato uguale.
  Gli indirizzi manuali non vengono salvati — a essere persistita è l'associazione, in T-19.
- Nulla di tutto questo è ancora collegato all'app: `Discovery` non è nell'`AppContainer` e non
  c'è UI. Il cablaggio arriva con T-21, l'interfaccia con T-22.

## Che cosa c'è nell'associazione

- **Il codice si confronta a vista, non si digita.** Entrambi i lati derivano dallo scambio lo
  stesso codice a sei cifre e l'utente conferma di vederlo uguale sui due schermi. È uno
  **scostamento voluto** dal piano, che diceva «mostrato da uno e confermato dall'altro»: un
  codice digitato, sopra uno scambio ECDH, è forzabile offline in millisecondi da chi si mette in
  mezzo. Il motivo per esteso è in §5 del piano. **Ha una conseguenza su T-22**: la schermata
  mostra un codice e chiede «vedi questo stesso numero sull'altro dispositivo?», non un campo in
  cui digitarlo.
- Curva **P-256**, non X25519: su Android quest'ultima arriva con l'API 31 e il minimo qui è 26.
  Le chiavi dello scambio sono effimere — a sopravvivere è solo il segreto derivato.
- **HKDF-SHA256 è scritto a mano** (non c'è in JCA) e verificato contro tre vettori della
  RFC 5869: una derivazione sbagliata non fallisce rumorosamente, dà solo chiavi diverse.
- Il canale è AES-256-GCM con **una chiave per direzione** — due contatori sulla stessa chiave
  produrrebbero nonce ripetuti, che in GCM azzerano ogni garanzia — e il numero di sequenza come
  dato autenticato, così un frame ripetuto o spostato non si decifra.
- Un dispositivo non associato viene rifiutato **prima** di ricevere i nonce: a uno sconosciuto
  non si dà nemmeno materiale su cui lavorare.
- Il `sharedSecret` sta **in chiaro** nella tabella `peers`: è protetto dai permessi del file
  (sandbox dell'app su Android, cartella dell'utente su desktop) e non da una cifratura a riposo,
  che richiederebbe un portachiavi diverso per ogni piattaforma.
- Dalla T-21 tutto questo **è collegato all'app**: `SyncService` è nell'`AppContainer` e parte
  all'avvio su entrambe le piattaforme. Manca solo l'interfaccia (T-22).

## Che cosa c'è nel motore di replica

Due scelte sembrano dettagli e sono invece il motivo per cui la replica non perde dati. Entrambe
sono emerse da test falliti, non dal disegno iniziale.

1. **`changedSince` include l'estremo** (`>=`, non `>`). Il watermark è l'`updatedAt` più recente
   già ricevuto; nello stesso millisecondo può esserci un altro evento, scritto subito dopo lo
   scambio precedente. Escluderlo lo perderebbe **per sempre**. Rispedire ogni volta l'ultimo
   millisecondo costa un evento, che il merge scarta da sé.
2. **I due lati si scambiano prima le domande e poi le risposte.** Chi rispondesse dopo aver
   applicato il lotto dell'altro gli rimanderebbe indietro i suoi stessi eventi; il watermark
   finirebbe per riflettere l'orologio del destinatario e le modifiche del dispositivo con
   l'orologio indietro non partirebbero mai più. Per lo stesso motivo il watermark è `Push.upTo`,
   l'istante dichiarato dal **mittente**, e non il massimo `updatedAt` del lotto — che può
   contenere eventi nati altrove.

Il resto:

- `resolveMerge` è una **funzione pura**, separata dall'applicazione: la convergenza si verifica
  senza database né rete.
- A parità di `updatedAt` vince la firma testuale maggiore del contenuto. Serve un criterio che dia
  lo **stesso** vincitore su entrambi i dispositivi: «vince il remoto» darebbe risultati opposti sui
  due lati, cioè esattamente il modo di non convergere.
- Un tombstone non è un caso a parte: è un evento con `deleted = true`, e per questo una
  cancellazione non risorge.
- L'idempotenza non viene da un controllo sui duplicati, ma dal fatto che il confronto è per `uuid`
  e la regola scarta ciò che non è più recente.
- Dopo il merge gli allarmi vengono rimessi in riga: annullati **e poi** riprogrammati, perché un
  evento diventato completato o cancellato deve perdere la sveglia che aveva.

**Il clock skew resta un rischio accettato sulla regola LWW**: un dispositivo con l'orologio avanti
vince anche quando è anteriore. Risolverlo davvero richiede orologi vettoriali; con due dispositivi
il danno si limita a una modifica concorrente allo stesso evento.

## Come sta insieme, adesso

- `SyncController` è un'**interfaccia in `commonMain`**: `AppContainer` e le schermate vivono lì,
  socket e crittografia in `jvmSharedMain`. Stessa ragione per cui `AlarmScheduler` è
  un'interfaccia. `SyncService` la implementa.
- **Il desktop ascolta sempre, il telefono mai.** Non è una scelta: Android non lascia tenere un
  socket aperto ad app chiusa. Il telefono sincronizza da `MainActivity.onStart()`, cioè quando è
  in mano all'utente.
- **Ci si annuncia solo se si è davvero in ascolto, e con la porta effettiva.** Annunciarne
  un'altra manderebbe il peer contro un muro. Porta fissa **47653**, così chi deve digitarla a
  mano ha qualcosa da digitare.
- **`sync_enabled` è spento di default** ed è ciò che rende innocuo tutto il resto: finché è
  spento non si apre un socket né parte un annuncio. Si accende quando l'utente completa la prima
  associazione. Verificato sull'emulatore: dopo l'avvio dell'app `shared_prefs/` contiene solo
  `device.xml`, nessun file di impostazioni.
- **Un guasto di rete non risale come eccezione**: diventa un esito con un messaggio in italiano,
  perché è quello che l'utente deve leggere.
- Chi riceve un'associazione deve poter mostrare il codice: senza una schermata pronta, il
  servizio **nega**. Accettare senza che nessuno abbia guardato il codice vanificherebbe il
  confronto a vista.

## La schermata di sincronizzazione

Si raggiunge dall'icona nella barra della lista. Mostra stato e ultimo allineamento, i dispositivi
associati (con dissociazione) e quelli trovati sulla rete (con «Associa»), ha «sincronizza ora» e
l'inserimento manuale di host e porta con la porta già compilata.

Due cose non ovvie, entrambe emerse provandola davvero:

1. **Aprire la schermata accende ricerca e ascolto anche a interruttore spento.** La prima
   versione rispettava il flag e il risultato era un vicolo cieco: per associare il primo
   dispositivo bisogna trovarlo, per trovarlo serve la ricerca accesa, e per accenderla serviva
   un'associazione. Aprire quella schermata **è** l'atto esplicito dell'utente; chiuderla
   rispegne tutto se nessuna associazione è stata completata.
2. **Finché è aperta è l'unico posto in cui si può mostrare il codice di un'associazione in
   arrivo.** Fuori da qui quelle associazioni vengono **negate**: accettarle mentre nessuno
   guarda il codice vanificherebbe il confronto a vista.

Il dialogo del codice chiede «vedi questo stesso numero sull'altro dispositivo?» e non offre un
campo in cui digitarlo — è la conseguenza diretta della scelta crittografica di T-19.

## Prossimo passo

Le funzionalità ci sono tutte. Restano:

- **T-29** (1,5 gg) — test di integrazione: due istanze desktop che si scoprono, si associano e
  convergono. È il primo che eserciterà mDNS e trasporto insieme.
- **T-25** (2,5 gg) — resta poco: merge, tombstone, idempotenza, protocollo e associazione sono
  già coperti dai test scritti lungo la strada. Va rivisto quanto ne rimanga davvero.
- **T-30** (1,0 gg) — collaudo telefono ↔ desktop su rete reale, inclusi offline e conflitto.
- **T-32/T-33** (1,3 gg) — requisiti di rete, guida al pairing e note di distribuzione.

Il dettaglio task per task è in [phase-3-implementation-plan.md](phase-3-implementation-plan.md).

## Punti aperti

1. **I database reali sono stati migrati alla v5** su entrambi i dispositivi, dopo aver preso i
   backup in `~/promemoria-backup-2026-08-21/` (che sono della v2 e restano l'unica via di
   ritorno: le migrazioni non sono reversibili).
2. **`upsertFromRemote()` non c'è.** Il piano la elencava fra le modifiche al DAO di T-17, ma le
   sue semantiche *sono* la regola di merge: nasce in T-20 insieme ai test che la definiscono.
3. **Riprogrammazione al boot su device reale**: `BOOT_COMPLETED` è un broadcast protetto e non
   si può simulare da adb. La logica è coperta da un test strumentato; il cablaggio
   receiver + manifest si verifica solo riavviando il telefono.
4. **0,3 gg di documentazione** (T-32) sui requisiti di rete: ha senso scriverla insieme alla sync.
5. **Il round-trip mDNS usa la rete reale della macchina.** Su un host senza interfacce non di
   loopback il test si dichiara saltato invece di fallire; su una rete con multicast filtrato
   fallirebbe, ed è l'informazione giusta da avere.

## Vincoli d'ambiente da ricordare

- Il telefono di test (Redmi Note 7, Android 10) chiede conferma **a ogni** installazione via ADB
  ("Installa tramite USB", si auto-nega dopo 8 secondi): le run strumentate automatiche vanno
  fatte su emulatore.
- Emulatore utilizzabile: **`Emulator_x86_64`**. `Medium_Phone_API_33` è ARM e non parte più.
- `sqlite3` non è eseguibile via `run-as` su quel telefono: per manipolare i dati di test si passa
  dalla UI.
- Senza `xdotool` non si possono simulare click sull'app desktop: le schermate si verificano
  passando una rotta iniziale all'avvio.
