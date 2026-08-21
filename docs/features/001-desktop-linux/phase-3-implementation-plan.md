# Desktop Linux — Implementation Plan

**Stato:** Bozza — in attesa di approvazione
**Autore:** Alberto Goldoni
**Data:** 2026-08-20
**Versione:** 1.0
**Codebase di riferimento:** commit `e18dffa` (branch `main`)

---

## 1. Executive Summary

*Promemoria* oggi esiste solo su Android. Questa feature porta la stessa applicazione su
**desktop Linux**, convertendo il progetto a **Compose Multiplatform**: una sola codebase
alimenta entrambe le versioni, invece di due progetti da mantenere in parallelo. Sul PC l'app
resta attiva nella system tray, parte al login e mostra le notifiche di scadenza anche a
finestra chiusa. Telefono e computer si **sincronizzano da soli quando sono sulla stessa rete**,
trovandosi via discovery automatico e senza alcun server: nessun account, nessun cloud, nessun
dato che esce dalla rete locale.

Stima complessiva **46,5 giorni/uomo**, divisa in due tranche: **28,0 gg** per l'app desktop
completa e installabile (senza sincronizzazione) e **18,5 gg** per la sincronizzazione.

---

## 2. Obiettivo e motivazione

- **Problema che risolve:**
  I promemoria vivono nel solo database Room del telefono. Chi lavora davanti a un PC Linux non
  può consultarli né crearli senza prendere in mano il dispositivo, e le notifiche arrivano
  sullo schermo sbagliato — non quello che sta guardando. L'unico ponte esistente verso il
  desktop è l'export ODS (feature `condivisione`): a senso unico e in sola lettura.

- **Metriche di successo** (verifica manuale, nessuna analytics nel progetto):
  - [ ] Un promemoria creato sul telefono compare sul desktop entro 30 s, con entrambe le app
        attive sulla stessa rete.
  - [ ] La notifica desktop arriva all'orario previsto con la finestra chiusa.
  - [ ] Zero regressioni funzionali sull'app Android dopo la ristrutturazione a moduli.
  - [ ] Installazione su Linux con un solo file, senza dipendenze da installare a mano.
  - [ ] Ogni nuova feature di prodotto si scrive una volta sola e appare su entrambe le
        piattaforme.

- **Legame con gli obiettivi del progetto:**
  *Promemoria* è un'app personale il cui valore sta nell'affidabilità del promemoria al momento
  giusto. Il desktop è il luogo dove l'utente passa la giornata lavorativa: senza di esso una
  quota rilevante delle scadenze arriva su un dispositivo non guardato.

---

## 3. Scope

### Incluso

**Ristrutturazione multipiattaforma**
- Moduli `:shared` (con source set `commonMain`, `jvmSharedMain`, `androidMain`, `desktopMain`),
  `:androidApp`, `:desktopApp`.
- Sostituzione di Hilt con una DI compatibile KMP; migrazione a Room KMP con driver SQLite
  bundled; astrazioni `expect/actual` per allarmi, notifiche, filesystem, informazioni di build,
  formattazione date e colori dinamici.

**App desktop Linux**
- Parità funzionale sulle schermate esistenti (lista attivi, editor, completati) ed export ODS
  con dialog di salvataggio nativo.
- Notifiche di sistema con azioni +5 min / +1 ora / completa; recupero all'avvio delle scadenze
  maturate ad app spenta.
- System tray con menù, chiusura-a-tray, istanza singola, avvio automatico al login via
  `~/.config/autostart`.
- Distribuzione come **AppImage** singolo file.

**Sincronizzazione punto-punto in LAN**
- Schema dati sincronizzabile (v3): identità globale, timestamp di modifica, tombstone
  conservati per sempre.
- Discovery automatico via mDNS, pairing con codice di conferma su entrambi i lati, canale
  cifrato, credenziali persistite.
- Replica bidirezionale con risoluzione conflitti "ultima scrittura vince" per evento, e
  riprogrammazione degli allarmi dopo ogni sincronizzazione.
- Schermata di stato: peer associato, ultimo sync, errori, sync manuale, dissociazione.

### Escluso (out of scope)

- **Windows e macOS** — il codice condiviso non li preclude, ma build, packaging e collaudo non
  rientrano: nessun hardware di test disponibile e nessun bisogno dichiarato.
- **Sync via Internet, server centrale, account utente** — la scelta esplicita è di non avere
  infrastruttura da gestire né dati fuori dalla rete di casa.
- **Sync di più di due dispositivi** — la topologia è punto-punto; il modello a mesh
  moltiplicherebbe i casi di conflitto senza un beneficio reale per un utente singolo.
- **Merge campo-per-campo e UI di risoluzione conflitti** — su un'app monoutente la modifica
  simultanea dello stesso evento da due dispositivi è un caso di bordo: LWW è sufficiente.
- **Epurazione automatica dello storico** — per decisione dell'utente completati e tombstone si
  conservano indefinitamente.
- **iOS, web, PWA; redesign della UI; integrazione con calendari esterni; cifratura del
  database a riposo.**

### Decisioni chiuse

Tutte risolte dall'utente il **2026-08-20**: nessuna decisione bloccante residua.

| # | Decisione | Esito |
|---|---|---|
| 1 | Ambiente desktop target | **Cinnamon / X11** — la stessa macchina di sviluppo. La system tray funziona nativamente: R14 decade e US-003 resta come progettata |
| 2 | Approvazione della stima | **Piano completo approvato** (46,5 gg, entrambe le tranche). La consegna resta incrementale: tranche 1 utilizzabile da sola |
| 3 | Dependency injection | **Container manuale** — niente Koin: 12 punti di iniezione non giustificano una dipendenza in più |
| 4 | Terna Kotlin / AGP / Compose Multiplatform | Resta il deliverable di T-01: è una verifica tecnica, non una scelta di prodotto |

---

## 4. User Stories e criteri di accettazione

### US-001 · Gestire i promemoria dal PC
**Priorità:** Must Have

Come utente al PC voglio consultare, creare, modificare e completare i promemoria da
un'applicazione Linux per non dover prendere il telefono mentre lavoro.

- [ ] L'app si avvia su Linux X11 e mostra la lista degli attivi ordinati per data.
- [ ] Creazione, modifica, completamento, riattivazione ed eliminazione danno lo stesso
      risultato dell'app Android sugli stessi dati.
- [ ] Le tre schermate sono navigabili con mouse e tastiera.
- [ ] Il database risiede in `~/.local/share/promemoria/reminder.db` (standard XDG).

### US-002 · Ricevere le notifiche sul desktop
**Priorità:** Must Have

Come utente al PC voglio ricevere la notifica del promemoria sul desktop, anche a finestra
chiusa, per non perdere le scadenze mentre sono concentrato su altro.

- [ ] Alla scadenza (`dateTimeMillis - advanceMinutes * 60000`) compare una notifica di sistema
      con titolo e descrizione.
- [ ] Le azioni +5 min, +1 ora e completa hanno lo stesso effetto sui dati delle omologhe Android.
- [ ] La notifica arriva con la finestra chiusa e app in tray.
- [ ] Se l'app era spenta alla scadenza, al successivo avvio compare una notifica di recupero
      per ogni evento scaduto e non completato.

### US-003 · Avvio automatico e tray
**Priorità:** Must Have

Come utente voglio che l'app parta da sola al login e resti nella tray per non doverla
riaprire a ogni riavvio.

- [ ] La chiusura della finestra non termina il processo.
- [ ] Il menù della tray offre: apri, nuovo promemoria, esci.
- [ ] L'opzione "avvia al login" crea/rimuove `~/.config/autostart/promemoria.desktop` e lo
      stato sopravvive al riavvio.
- [ ] Un secondo avvio riporta in primo piano la finestra esistente invece di duplicare il processo.

### US-004 · Trovare l'altro dispositivo senza configurazione
**Priorità:** Must Have

Come utente voglio che telefono e PC si trovino da soli sulla stessa rete per non dover
configurare indirizzi IP o porte.

- [~] Con entrambe le app attive sulla stessa rete, ciascuna elenca l'altra entro 30 s. *(meccanismo pronto e verificato desktop↔desktop in ~4 s; telefono ↔ PC si collauda in T-30)*
- [x] Il nome mostrato identifica il dispositivo in modo leggibile (`device_name`/modello su Android, hostname su desktop).
- [~] Se il multicast è bloccato, l'app lo segnala e offre l'inserimento manuale di host e porta. *(`DiscoveryStatus.Unavailable` e `PeerDirectory.addManual` esistono e sono testati; la UI che li mostra è T-22)*

### US-005 · Associare i dispositivi in modo sicuro
**Priorità:** Must Have

Come utente voglio autorizzare esplicitamente l'associazione con un codice di conferma per
essere certo che nessun altro sulla rete legga o alteri i miei promemoria.

- [~] L'associazione richiede conferma su entrambi i lati tramite un codice **mostrato da
      entrambi e confrontato a vista** — vedi lo scostamento motivato in §5. *(meccanismo fatto e
      testato; la schermata è T-22)*
- [x] Un peer non associato che tenta di sincronizzare viene rifiutato, prima ancora di ricevere
      materiale crittografico su cui lavorare.
- [x] Il traffico è cifrato: un terzo dispositivo sulla rete non legge i promemoria intercettando
      (verificato ispezionando i byte sul filo).
- [x] La dissociazione elimina le credenziali e interrompe le sincronizzazioni successive.

### US-006 · Allineamento automatico delle modifiche
**Priorità:** Must Have

Come utente voglio che le modifiche fatte offline si allineino da sole al primo rientro in rete
per non dover ricordare cosa ho cambiato e dove.

- [ ] Un evento creato su un dispositivo compare sull'altro alla prima sincronizzazione utile.
- [ ] Un evento modificato aggiorna l'altro senza duplicarsi.
- [ ] Un evento eliminato viene eliminato anche sull'altro e non riappare in seguito.
- [ ] Modifiche concorrenti allo stesso evento convergono allo stesso risultato su entrambi i
      dispositivi, senza duplicati né perdita degli altri eventi.
- [ ] Dopo una sincronizzazione che tocca eventi futuri, gli allarmi locali sono riprogrammati.
- [ ] Gli eventi presenti prima dell'aggiornamento sopravvivono alla migrazione v2 → v3 intatti.

### US-007 · Vedere lo stato della sincronizzazione
**Priorità:** Should Have

Come utente voglio vedere quando è avvenuta l'ultima sincronizzazione e con quale dispositivo
per accorgermi se qualcosa non funziona.

- [ ] Una schermata elenca il dispositivo associato con data/ora dell'ultimo sync riuscito.
- [ ] Gli errori (peer irraggiungibile, rifiutato, timeout) sono mostrati in italiano.
- [ ] È disponibile un comando "sincronizza ora".

### US-008 · Esportare in ODS dal desktop
**Priorità:** Should Have

Come utente al PC voglio esportare i promemoria in ODS e scegliere dove salvarli per
archiviarli o aprirli in LibreOffice come già faccio da telefono.

- [ ] L'export è disponibile con i filtri esistenti (tutti / solo aperti).
- [ ] Il file viene salvato dove sceglie l'utente e si apre correttamente in LibreOffice Calc.

### US-009 · Una sola codebase
**Priorità:** Must Have

Come sviluppatore voglio una sola codebase condivisa per implementare ogni nuova feature una
volta sola.

- [ ] Dominio, dati, ViewModel, schermate ed export vivono nel modulo condiviso.
- [ ] Il codice per piattaforma è limitato ad allarmi, notifiche, filesystem/condivisione,
      tray/autostart e trasporto di sincronizzazione.
- [ ] Build Android e build desktop completano entrambe da progetto pulito.

### US-010 · Nessuna regressione su Android
**Priorità:** Must Have

Come utente Android voglio che l'app sul telefono continui a funzionare come prima per non
pagare la versione desktop con regressioni sul mobile.

- [ ] `applicationId`, suffisso `.debug` e firma di release restano invariati.
- [ ] L'aggiornamento sopra l'installazione esistente conserva i promemoria.
- [ ] Allarmi, azioni da notifica, riprogrammazione al boot ed export/share continuano a funzionare.

---

## 5. Architettura tecnica

### Componenti coinvolti

```
┌──────────────── :shared ─────────────────────────────────────────┐
│ commonMain                                                       │
│   ui/{list,edit,completed,sync}  Compose Material3 + ViewModel    │
│   data/  EventEntity · EventDao · PeerEntity · AppDatabase (Room) │
│   export/ Exporter · ExportFilter · ExportEventsUseCase           │
│   sync/  SyncEngine · SyncProtocol · (expect) Discovery/Transport │
│   platform/ (expect) AlarmScheduler · Notifier · ExportTarget ·   │
│             AppInfo · DateFormat · DatabaseFactory                │
│                                                                  │
│ jvmSharedMain      OdsExporter (ZipOutputStream) · actual date    │
│   ├── androidMain  AlarmManager · NotificationHelper · Receivers  │
│   │                ShareHelper · NsdManager                       │
│   └── desktopMain  timer in-process · notifiche · tray ·          │
│                    autostart · file dialog · jmdns · TLS socket   │
└──────────────────────────────────────────────────────────────────┘
        ▲                                              ▲
   :androidApp                                    :desktopApp
   Application + MainActivity + manifest          main() + AppImage
```

Flusso di sincronizzazione (punto-punto, nessun server):

```
  Android (foreground)                       Desktop (sempre in ascolto)
        │  1. annuncio/scoperta mDNS  _promemoria-sync._tcp │
        │ ─────────────────────────────────────────────────►│
        │  2. HELLO + versione protocollo                   │
        │ ◄────────────────────────────────────────────────►│
        │  3. PAIR (solo la prima volta, codice sui due lati)│
        │ ◄────────────────────────────────────────────────►│
        │  4. PULL(since) / PUSH(events) su canale cifrato   │
        │ ◄────────────────────────────────────────────────►│
        │  5. merge LWW + tombstone → riprogrammazione allarmi
```

Il desktop è il lato sempre in ascolto perché le policy Android impediscono di mantenere un
socket server con l'app chiusa: il telefono sincronizza all'apertura e al rientro in foreground.

### Modifiche al data model

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| `events` | Modifica (schema v2 → v3) | `+uuid TEXT` (identità globale, indice unico), `+updatedAt INTEGER`, `+deleted INTEGER`, `+deletedAt INTEGER NULL`, `+origin TEXT` (dispositivo di nascita, immutabile). `id` autoincrementale **resta**: è il requestCode dei `PendingIntent` e l'id delle notifiche |
| `events` | Modifica semantica | `delete` diventa soft-delete; tutte le letture filtrano `deleted = 0`; ogni scrittura aggiorna `updatedAt` |
| `peers` | Nuova (schema v3 → v4) | `deviceId` (chiave primaria: l'identità dichiarata dall'altro, stabile mentre l'IP cambia), `displayName`, `sharedSecret` (esadecimale, **in chiaro**: protetto dai permessi del file, non cifrato a riposo), `lastHost`, `lastPort`, `pairedAt`, `lastSyncAt` |
| `EventDao` | Modifica | nuove query `changedSince(millis)`, `getByUuid(uuid)`; `upsertFromRemote(...)` arriva con T-20 insieme alla regola di merge |
| Migrazione `MIGRATION_2_3` | Nuova | `ALTER TABLE` per le cinque colonne, popolamento `uuid` con `lower(hex(randomblob(...)))`, `updatedAt = dateTimeMillis` come valore iniziale |

### Protocollo di sincronizzazione (al posto delle API REST)

| Messaggio | Direzione | Descrizione | Richiede pairing |
|---|---|---|---|
| `HELLO` | ↔ | deviceId, nome, versione di protocollo | No |
| `PAIR_BEGIN` / `PAIR_KEY` | ↔ | scambio delle chiavi pubbliche effimere (ECDH P-256) | No (è l'atto di associarsi) |
| `PAIR_CONFIRM` / `PAIR_DONE` | ↔ | prova incrociata di aver ricavato lo stesso segreto, dopo la conferma dell'utente | No |
| `SESSION_BEGIN` / `SESSION_ACCEPT` / `SESSION_CONFIRM` | ↔ | nonce e autenticazione reciproca col segreto dell'associazione; da qui il canale è cifrato | Sì |
| `PULL(since)` | → | richiesta degli eventi modificati dopo `since` | Sì |
| `PUSH(events)` | → | invio degli eventi modificati localmente | Sì |
| `ACK(highWatermark)` | ← | conferma e nuovo watermark | Sì |

Trasporto: socket TCP su canale cifrato con il segreto stabilito in fase di pairing,
serializzazione `kotlinx.serialization`. Versione di protocollo esplicita nel primo messaggio:
peer con versione incompatibile rifiutano invece di corrompere i dati.

> **Scostamento sul modello di conferma (T-19), da rivedere se non convince.**
> Il piano diceva «un codice mostrato da uno e confermato dall'altro», cioè un codice digitato.
> Quel modello, sopra uno scambio ECDH, **non è sicuro**: chi si mette in mezzo negozia due
> scambi, cattura la prova che dipende dal codice e ne prova offline tutti i milione di valori in
> millisecondi. Renderlo sicuro richiede un PAKE vero (SPAKE2, J-PAKE), cioè molto più codice
> crittografico — e una libreria in più nell'APK — di quanto ne meriti un'app personale.
> L'implementazione usa invece il **confronto a vista**, lo stesso modello del pairing Bluetooth:
> i due lati derivano dallo scambio lo **stesso** codice a sei cifre, lo mostrano entrambi, e
> l'utente conferma su ciascuno di aver visto lo stesso numero. Chi è in mezzo produce due codici
> diversi e ha una probabilità su un milione di indovinare. Il criterio di US-005 «conferma su
> entrambi i lati» è soddisfatto — anzi la conferma è esplicita su entrambi invece che su uno.
> **Conseguenza per T-22:** la schermata mostra un codice e chiede «vedi questo stesso numero
> sull'altro dispositivo?», non un campo in cui digitarlo.

### Breaking changes

| Componente | Tipo di breaking change | Piano di migrazione |
|---|---|---|
| Database `reminder.db` | Schema v2 → v4, **non reversibile**: una release precedente non apre un DB v3 | Migrazione automatica all'avvio; backup del file DB prima del primo avvio della versione nuova (vedi §9) |
| `EventDao.delete()` | Da cancellazione fisica a soft-delete | 2 soli chiamanti: `EventListViewModel.delete`, `CompletedViewModel.delete` |
| `ExportEventsUseCase.execute()` | Ritorna `Result<ExportedFile>` invece di `Result<Uri>` | Il consumo passa a `ExportTarget`; `ExportUiState` ed `EmptyExportException` invariati |
| `AlarmScheduler` | Da `object` statico a interfaccia iniettata | 3 ViewModel + 2 receiver aggiornati contestualmente |
| Struttura dei moduli | I sorgenti cambiano modulo | Nessun impatto per l'utente finale; `applicationId` invariato |

---

## 6. Piano di implementazione

Responsabile unico: Alberto Goldoni. Area: **Infra** (build/toolchain), **Core** (logica e
piattaforma), **UI**, **Test**, **Doc**.

| ID | Task | Area | Stima (gg) | Dipende da |
|---|---|---|---:|---|
| T-01 | ✅ **fatto** — terna validata: Gradle 8.14.5 · AGP 8.13.2 · Kotlin 2.3.21 · KSP 2.3.11 · CMP 1.11.1 · Room 2.8.4. Esito in [t01-toolchain-validation.md](t01-toolchain-validation.md) | Infra | 2,0 | — |
| T-02 | ✅ **fatto** — toolchain aggiornata in place e moduli `:shared`/`:androidApp`/`:desktopApp` creati; i 25 sorgenti spostati con `git mv` | Infra | 3,0 | T-01 |
| ~~T-03~~ | ❌ **rimosso** — T-01 ha validato AGP 8.13.2: `applicationVariants` resta valida e il rename dell'APK non va riscritto | Infra | ~~0,5~~ | — |
| T-04 | ✅ **fatto** — `exportSchema = true` + `room.schemaLocation`, schema v2 esportato in `app/schemas/` | Core | 0,5 | — |
| T-05 | ✅ **fatto** — `BootReceiver` legge il DAO dal container via `EntryPointAccessors`: niente secondo database senza migrazioni (R15) | Core | 0,5 | — |
| T-06 | ✅ **fatto** — Hilt rimosso, `AppContainer` manuale, ViewModel costruiti da `viewModelFactory`, `AndroidViewModel`/`SavedStateHandle` eliminati | Core | 2,0 | — |
| T-07 | ✅ **fatto** — permesso notifiche chiesto in `MainActivity` all'avvio (R17); `RequestCodes` con blocchi da 8 slot per evento (R16). `cancel()` annulla ora anche gli snooze pendenti e i codici legacy: prima un evento rinviato e poi completato o eliminato faceva comunque scattare la notifica | Core | 0,5 | — |
| T-08 | ✅ **fatto** — Room KMP con `@ConstructedBy`, migrazione riscritta su `SQLiteConnection`, driver per piattaforma, DB desktop in `~/.local/share/promemoria`; `identityHash` dello schema invariato | Core | 2,0 | T-02 |
| T-09 | ✅ **fatto** (Notifier rimandato a T-12) — `AppInfo`, date in `jvmSharedMain`, colori dinamici `expect/actual`, `AlarmScheduler` come interfaccia, `LocalAppContainer` | Core | 1,0 | T-06 |
| T-10 | ✅ **fatto** — finestra 900×700, navigazione multipiattaforma e le tre schermate verificate su desktop con dati reali (lista, editor di un evento esistente, Fatti) | UI | 3,0 | T-08, T-09 |
| T-11 | ✅ **fatto** — `DesktopAlarmScheduler`: una coroutine in attesa per evento, `bootstrap()` riprogramma i futuri e recupera gli scaduti all'avvio (nuova query DAO `getOverdueEvents`) | Core | 1,5 | T-10 |
| T-12 | ✅ **fatto** — `DesktopNotifier` via `notify-send` (libnotify ≥ 0.8, azioni con `-A`); +5 min, +1 ora e Completa; verificate a runtime su Cinnamon | Core | 1,0 | T-11 |
| T-13 | ✅ **fatto** — tray con icona propria e menù (Apri · Nuovo promemoria · Avvia al login · Esci), chiusura-a-tray, istanza singola via socket sul loopback che riporta in primo piano la finestra esistente | UI | 1,5 | T-10 |
| T-14 | ✅ **fatto** — `Autostart` scrive/rimuove il `.desktop` XDG; il comando di avvio viene da `APPIMAGE`, e senza di esso la voce di menù non compare | Core | 0,5 | T-13 |
| T-15 | ✅ **fatto** — `ExportTarget` per piattaforma e use case comune; `Exporter` restituisce `ByteArray` invece di scrivere su `OutputStream` | Core | 1,0 | T-09 |
| T-16 | ✅ **fatto** — `FileDialog` nativo in modalità salvataggio; annullare non scrive nulla | UI | 0,5 | T-15 |
| T-17 | ✅ **fatto** — schema v3 (`uuid` con indice unico, `updatedAt`, `deleted`/`deletedAt`, `origin`), `migration2to3(deviceId)`, DAO con soft-delete e letture filtrate, `changedSince()`, `getByUuid()`, identità del dispositivo persistita per piattaforma. `upsertFromRemote()` è rinviata a T-20, dove la regola LWW che la definisce viene scritta e testata | Core | 1,5 | T-08, T-04 |
| T-18 | ✅ **fatto** — contratto `Discovery` in `commonMain` su `_promemoria-sync._tcp`, `NsdDiscovery` (multicast lock + risoluzioni serializzate), `JmdnsDiscovery` (indirizzo di sito esplicito), `PeerDirectory` che unisce trovati e digitati. 9 unit test + 1 round-trip mDNS reale su desktop + 1 strumentato sul cablaggio Android | Core | 1,5 | T-17 |
| T-19 | ✅ **fatto** — tabella `peers` (schema v4, `MIGRATION_3_4`), ECDH P-256 effimero, HKDF-SHA256 verificato su RFC 5869, associazione con codice **confrontato a vista** (vedi nota sotto), canale AES-256-GCM con chiavi direzionali e sequenza autenticata, rifiuto dei non associati e delle versioni incompatibili. 19 test | Core | 2,5 | T-18 |
| T-20 | `SyncProtocol` + `SyncEngine`: merge LWW, tombstone, watermark, idempotenza | Core | 3,0 | T-17 |
| T-21 | Integrazione trasporto ↔ engine: riprogrammazione allarmi, gestione errori, sync in foreground su Android | Core | 1,5 | T-19, T-20 |
| T-22 | Schermata stato sincronizzazione: peer, ultimo sync, errori, sync manuale, dissociazione | UI | 2,0 | T-21 |
| T-23 | ✅ **fatto** — `commonTest`, `jvmSharedTest`, `desktopTest`, `desktopApp/src/test` e `androidInstrumentedTest`, quest'ultimo eseguito su emulatore | Test | 0,5 | T-02 |
| T-24 | ✅ **fatto** — `./build.sh desktop` produce un AppImage da 69,6 MB: `createDistributable` + AppDir + `appimagetool`. Icona, `.desktop` e `AppRun` inclusi; `APPIMAGE` risulta valorizzato a runtime, quindi l'autostart funziona dall'AppImage | Infra | 2,5 | T-13 |
| T-25 | Unit test: merge LWW, tombstone che non risorge, idempotenza, protocollo, pairing | Test | 2,5 | T-20, T-23 |
| T-26 | ✅ **fatto** — 4 test: eventi v2 conservati, colonne di sync popolate (uuid canonici e distinti, `updatedAt`, `origin`), indice unico attivo, tombstone invisibile all'app ma leggibile da `getByUuid`/`changedSince`. Girano in `desktopTest` su SQLite reale invece che su emulatore: aprire il database con `createAppDatabase` fa validare lo schema a Room, che è la garanzia che dava `MigrationTestHelper` | Test | 0,5 | T-17, T-23 |
| T-27 | ✅ **fatto** — 10 test: struttura ODS (mimetype STORED per primo, manifest, contenuto), escape XML e le maschere di data | Test | 1,0 | T-15, T-23 |
| T-28 | ✅ **fatto** — 13 test: autostart, istanza singola e scheduler desktop (scadenza, annullamento, riprogrammazione, bootstrap, azioni Completa e Posticipa) con tempo virtuale | Test | 1,0 | T-14, T-23 |
| T-29 | Test di integrazione: due istanze desktop che si scoprono, si associano e convergono | Test | 1,5 | T-21 |
| T-30 | Collaudo manuale telefono ↔ desktop su rete reale (inclusi casi offline e conflitto) | Test | 1,0 | T-22 |
| T-31 | ✅ **fatto** — su device: avvio, creazione, allarme programmato e annullato, Fatti, eliminazione, dialog Info, aggiornamento in place dalla versione pre-KMP con dati conservati, export/share (ODS aperto in LibreOffice) e **snooze da notifica** (notifica puntuale, azione +5 min che riprogramma e chiude). La riprogrammazione al boot è coperta da un test strumentato: `BOOT_COMPLETED` è un broadcast protetto e resta verificabile solo con un riavvio vero | Test | 1,0 | T-21, T-24 |
| T-32 | ⏳ **in corso (0,7 di 1,0)** — `README.md` e `CLAUDE.md` aggiornati a moduli, build desktop e AppImage; i requisiti di rete della sync restano da scrivere quando la tranche 2 esisterà | Doc | 1,0 | T-24 |
| T-33 | Guida a pairing e rete + note di distribuzione AppImage | Doc | 1,0 | T-30 |

**Stima totale: 46,0 giorni/uomo** (46,5 iniziali − 0,5 di T-03, rimosso)
**Breakdown:** Infra 7,5 gg · Core 20,5 gg · UI 7,0 gg · Test 9,0 gg · Doc 2,0 gg
**Già completati:** 33,2 gg — **tranche 1 completa** salvo 0,3 gg di documentazione che dipende dalla sincronizzazione; della tranche 2 sono chiusi lo schema v3 (T-17), i test di migrazione (T-26), la scoperta dei dispositivi (T-18) e l'associazione con canale cifrato (T-19). **Restano 12,8 gg.**

**Due tranche:**

| Tranche | Contenuto | Task | Stima |
|---|---|---|---:|
| **1 — App desktop** | Tutto tranne la sincronizzazione: desktop completo, installabile, con notifiche, tray, autostart, export | T-01…T-16 (T-03 escluso), T-23, T-24, T-27, T-28, T-31, T-32 | **27,5 gg** (24,0 residui) |
| **2 — Sincronizzazione** | Schema v3, discovery, pairing, replica, UI di stato, collaudo | T-17…T-22, T-25, T-26, T-29, T-30, T-33 | **18,5 gg** (12,5 residui) |

> **Stato al 2026-08-20:** completati T-01, T-02, T-04…T-09, T-11…T-14, la parte centrale di
> T-10 e metà di T-28 (**18,5 gg**). L'app desktop si avvia, apre il database, mostra gli eventi,
> programma gli allarmi, notifica (recupero delle scadenze perse incluso), resta nella tray a
> finestra chiusa e rifiuta le istanze doppie. **Restano 27,5 gg.**
>
> Non ancora verificate a runtime: le azioni della notifica (posticipa/completa), che richiedono
> un clic dell'utente; il codice c'è, la prova arriverà con i test desktop di T-28.
>
> **Riordino rispetto al piano:** T-06 è stato anticipato prima di T-02 per un vincolo tecnico —
> Hilt non funziona in un modulo KMP, quindi finché c'era non era possibile spostare i sorgenti
> in `commonMain`. Toglierlo mentre la toolchain era ancora quella collaudata ha permesso di
> verificare le due modifiche separatamente, invece di debuggarle insieme.
>
> **Scostamento dalle stime precedenti — da leggere prima di approvare.**
> La Fase 1 conteneva due totali fra loro incoerenti (36,0 gg nella tabella per aree, 39,0 gg
> nella somma delle milestone) e in Fase 2 avevo scritto "stima invariata" prima di avere una
> scomposizione a livello di task. La scomposizione qui sopra, che è quella su cui conviene
> decidere, porta a **46,5 gg**. La differenza (+7,5 gg sul totale più alto) viene da:
> prerequisiti emersi in Fase 2 (T-03, T-04, T-05: +1,5), il livello `platform` che nessuna
> delle due stime precedenti conteggiava (T-09: +1,0), la sincronizzazione scomposta in quattro
> task reali invece di due voci aggregate (+3,0), e il collaudo separato dalle implementazioni
> (+2,0). Se il totale è eccessivo, la leva naturale è fermarsi alla tranche 1.

---

## 7. Piano di test

**Strategia generale.** Il progetto **non ha oggi alcun test** (`app/src` contiene solo
`debug/`, `main/`, `release/`). Non si introduce una suite completa: si copre ciò che non è
verificabile a occhio, cioè il motore di sincronizzazione e le migrazioni di schema, più un
presidio minimo su export e scheduler. Tutto il resto resta collaudo manuale, come oggi.
I test unitari girano su JVM (nessun emulatore) tranne quelli di migrazione, che richiedono un
device o emulatore Android.

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Unit | Modifiche concorrenti allo stesso evento: entrambi i lati convergono sullo stesso stato (LWW) | Alta |
| TC-02 | Unit | Evento cancellato su un lato non riappare dopo due cicli di sync | Alta |
| TC-03 | Unit | Due `PUSH` identici consecutivi non duplicano eventi (idempotenza) | Alta |
| TC-04 | ✅ Unit | Peer con versione di protocollo diversa viene rifiutato con errore esplicito | Alta |
| TC-05 | ✅ Unit | Peer non associato rifiutato; codice non confermato e segreto diverso rifiutati | Alta |
| TC-06 | ✅ Unit (JVM) | Migrazione 2→3: eventi v2 conservati, `uuid` popolati e univoci, `updatedAt` valorizzato | Alta |
| TC-07 | Unit | Golden test ODS: `mimetype` STORED come primo entry, righe attese, filtro `OPEN_ONLY` | Media |
| TC-08 | Unit | Scheduler desktop: scadenza futura programmata, scadenza passata → recupero all'avvio, snooze +5/+60 | Alta |
| TC-09 | Unit | Autostart: creazione e rimozione del `.desktop`; secondo avvio che non duplica il processo | Media |
| TC-10 | Integrazione | Due istanze desktop sulla stessa macchina: discovery → pairing → convergenza degli eventi | Alta |
| TC-11 | Manuale | Telefono ↔ desktop su rete reale: creazione, modifica, cancellazione in entrambe le direzioni | Alta |
| TC-12 | Manuale | Modifiche offline su entrambi i lati, poi rientro in rete: convergenza senza perdite | Alta |
| TC-13 | Manuale | Notifica desktop a finestra chiusa e ad app riavviata dopo la scadenza | Alta |
| TC-14 | Manuale | Android: allarme, snooze da notifica, riavvio device, export/share, aggiornamento sopra l'installazione esistente con dati conservati | Alta |
| TC-15 | Manuale | Rete con multicast bloccato: messaggio chiaro e fallback manuale funzionante | Media |

### Definition of Done

- [ ] Tutti i test unitari passano in locale (`commonTest`, `jvmSharedTest`, `desktopTest`).
- [x] TC-06 eseguito con esito positivo (su JVM desktop, non su emulatore: vedi T-26).
- [ ] TC-11 → TC-14 eseguiti manualmente e annotati nel documento di collaudo.
- [ ] Nessuna eccezione non gestita nei log durante una sessione di sync completa.
- [ ] L'AppImage si avvia su una macchina pulita senza dipendenze aggiuntive.
- [ ] `README.md` e `CLAUDE.md` aggiornati.
- [ ] Build Android release firmata e installabile sopra la versione precedente.

---

## 8. Rischi e mitigazioni

| Rischio | Probabilità | Impatto | Mitigazione |
|---|---|---|---|
| ~~La terna Kotlin/AGP/CMP non si allinea~~ **risolto**: T-01 ha validato lo stack su entrambi i target il 2026-08-20 | — | — | — |
| Le policy Android impediscono la sync ad app chiusa | Alta | Medio | Modello asimmetrico per progetto: desktop sempre in ascolto, Android sincronizza in foreground |
| mDNS non passa (AP isolation, rete ospiti, VLAN) | Media | Medio | Fallback con host/porta manuali + messaggio diagnostico (TC-15) |
| ~~`BootReceiver` apre il DB senza migrazioni~~ **risolto** con T-05 il 2026-08-20 | — | — | — |
| ~~Collisioni di requestCode con id vicini~~ **risolto** con T-07 il 2026-08-20 | — | — | — |
| Regressioni sull'app Android durante lo spostamento dei moduli | Media | Alto | T-02 senza modifiche funzionali + TC-14 come cancello prima di proseguire |
| ~~Tray assente se il desktop target è GNOME Shell~~ **decaduto**: target confermato Cinnamon/X11, tray nativa | — | — | — |
| `appimagetool` non installato sulla macchina di build | Alta (certa oggi) | Basso | Procurarlo in T-24; è un AppImage a sua volta, nessuna installazione di sistema |
| Clock skew fra dispositivi falsa la regola LWW | Bassa | Medio | Timestamp UTC + tolleranza; in T-20 valutare un contatore di versione per evento |
| Il totale di 46,5 gg risulta insostenibile | Media | Medio | Consegna a tranche: la tranche 1 (28,0 gg) è autonoma e già utile |

---

## 9. Rollout e feature flag

**Strategia di rilascio:** graduale, per tranche. Non esistono ambienti di staging né canali di
distribuzione: il rollout coincide con l'installazione sui due dispositivi dell'utente.

- [x] **Tranche 1** — rilascio dell'AppImage desktop e di una build Android allineata alla nuova
      struttura a moduli, **senza** funzioni di sincronizzazione. Il DB resta allo schema v2:
      nessuna migrazione, nessun rischio sui dati.
- [x] **Tranche 2** — migrazione allo schema v3 e attivazione della sincronizzazione.

**Feature flag:** `sync_enabled`, impostazione applicativa persistita, **disattivata di default**.
Finché è spenta non vengono aperti socket né annunci mDNS, e l'app si comporta come nella
tranche 1. Si accende quando l'utente avvia il primo pairing. È un interruttore runtime, non di
build: permette di spegnere la sync in caso di problemi senza reinstallare nulla.

**Piano di rollback:**

1. Disattivare `sync_enabled`: interrompe immediatamente annunci, ascolto e repliche.
2. Se il problema è nell'app desktop, è sufficiente non avviare l'AppImage (nessuna
   installazione di sistema da rimuovere) o rimuovere il `.desktop` di autostart.
3. Se il problema riguarda i dati, ripristinare il backup del database:
   - **Prima** del primo avvio della versione con schema v3 va copiato il DB di entrambi i
     dispositivi (desktop: `~/.local/share/promemoria/reminder.db`; Android: export ODS come
     copia leggibile, più backup del file DB se il device lo consente).
   - La migrazione v2 → v3 **non è reversibile**: una build precedente non apre un DB v3. Il
     rollback dell'app richiede il ripristino del file di backup.
4. In ultima istanza, l'export ODS esistente resta la via di recupero leggibile dei dati.

---

## 10. Checklist di approvazione

Progetto a sviluppatore singolo: i ruoli coincidono nella stessa persona, ma le voci restano
distinte come cancelli espliciti prima di iniziare.

| Revisione | Responsabile | Stato | Data |
|---|---|---|---|
| Revisione tecnica (architettura, protocollo, schema v3) | Alberto Goldoni | ⏳ In attesa | — |
| Revisione di prodotto (scope, storie, esclusioni) | Alberto Goldoni | ⏳ In attesa | — |
| Stima approvata (46,5 gg, piano completo) | Alberto Goldoni | ✅ Approvata | 2026-08-20 |
| Rischi accettati (in particolare irreversibilità della migrazione v3) | Alberto Goldoni | ⏳ In attesa | — |
| Decisioni #1, #2, #3 chiuse | Alberto Goldoni | ✅ Chiuse | 2026-08-20 |
| Data di inizio confermata | Alberto Goldoni | ⏳ In attesa | — |

---

## Domande chiuse

Risposte dell'utente del **2026-08-20**:

1. **Ambiente desktop:** questa macchina — **Cinnamon / X11**. La tray è nativa, nessuna
   estensione richiesta, US-003 resta come progettata.
2. **Stima:** approvato il **piano completo** (46,5 gg), con consegna a tranche.
3. **DI:** **container manuale**, niente Koin.
4. **Fix indipendenti:** **anticipati ed eseguiti** (T-04, T-05, T-07) sull'app Android attuale.

Nessuna domanda aperta residua: il documento è approvabile.

---

## Riferimenti

- [phase-1-requirements.md](phase-1-requirements.md) — obiettivi, scope, stima iniziale, milestone
- [phase-2-analysis.md](phase-2-analysis.md) — analisi della codebase, versioni verificate, rischi R15-R17, prerequisiti P1-P9
- [docs/features/condivisione/](../condivisione/) — feature precedente (export ODS), riusata dal desktop

---

*Documento generato con la skill `claude-code-feature`.*
