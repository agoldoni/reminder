# Feature: Desktop Linux — Requisiti (Fase 1)

**Slug:** `desktop-linux`
**Data:** 2026-08-20
**Stato:** Bozza in attesa di approvazione
**Progetto:** Promemoria (`it.agoldoni.reminder`) — app Android single-module, Kotlin 2.0 / Compose / Room / Hilt
**Team:** 1 sviluppatore (Alberto Goldoni)

**Decisioni già prese dall'utente (input di questa fase):**

| Decisione | Scelta |
|---|---|
| Approccio tecnico | **Compose Multiplatform (KMP)** — una codebase condivisa, target Android + Desktop JVM |
| Sincronizzazione dati | **Peer-to-peer sulla rete locale**, con discovery automatico delle istanze; nessun server centrale |
| Allarmi su desktop | **Sempre attivi**: icona in system tray + avvio automatico al login |
| Ambiente desktop target | **Cinnamon / X11** — confermato il 2026-08-20: è la stessa macchina di sviluppo |
| Topologia sync | **Punto-punto**: una coppia di dispositivi associati (telefono ↔ PC) |
| Scadenze perse a app spenta | **Notifica di recupero all'avvio** |
| Retention eventi completati e tombstone | **Illimitata** (nessuna cancellazione automatica) |
| Formato di distribuzione | **AppImage** |

---

## 1. Obiettivo e motivazione

Oggi *Promemoria* esiste solo come app Android: i dati vivono nel database Room locale
`reminder.db` e gli allarmi passano da `AlarmManager`. Chi lavora davanti a un PC Linux non
ha modo di consultare o creare promemoria senza prendere in mano il telefono, e le notifiche
arrivano solo sul dispositivo mobile — proprio mentre l'attenzione è sullo schermo del PC.

**Problema che risolve:**

- Nessun accesso ai promemoria dalla postazione di lavoro Linux.
- Nessuna notifica sul dispositivo che l'utente sta effettivamente guardando.
- L'unico ponte esistente verso il desktop è l'export ODS (feature `condivisione`): utile per
  consultare/archiviare, ma è a senso unico e non consente di creare o modificare eventi.

**Perché KMP e non un secondo progetto:** l'app è piccola (~1.600 righe Kotlin in 25 file) e
la UI è già interamente Compose Material3. La quota di codice realmente Android-specifico è
concentrata in `alarm/` (AlarmManager, notifiche, BootReceiver) e in `export/ShareHelper`.
Condividere `data/`, `ui/` ed `export/` evita di scrivere e mantenere due volte ogni feature
futura.

**Metriche di successo (verifica manuale, nessuna analytics):**

- [ ] Un promemoria creato sul telefono compare sul desktop entro 30 s, se i due dispositivi
      sono sulla stessa rete e le rispettive app sono attive.
- [ ] La notifica desktop arriva all'orario previsto anche con la finestra dell'app chiusa.
- [ ] L'app Android mantiene il comportamento attuale: nessuna regressione funzionale dopo la
      ristrutturazione in moduli.
- [ ] Installazione su Linux con un solo comando/artefatto, senza dipendenze manuali.

---

## 2. Scope

### Incluso

**A. Ristrutturazione multipiattaforma**
- Passaggio da single-module a struttura KMP: `:shared` (dominio, dati, UI Compose, export),
  `:androidApp`, `:desktopApp`.
- Sostituzione di Hilt (non supportato su KMP) con una DI compatibile multipiattaforma.
- Migrazione di Room alla versione con supporto KMP e driver SQLite bundled.
- Astrazione `expect/actual` per: scheduling allarmi, notifiche, accesso al filesystem
  (percorso DB, cartella export), condivisione/apertura file.

**B. App desktop Linux**
- Parità funzionale con Android sulle schermate esistenti: lista attivi, editor evento,
  completati, export ODS (con salvataggio tramite dialog di sistema al posto dello share intent).
- Finestra ridimensionabile con layout adattato al desktop (mouse/tastiera, larghezze maggiori).
- Icona in system tray con menù rapido (apri, nuovo promemoria, esci) e chiusura finestra
  che riduce a tray invece di terminare il processo.
- Avvio automatico al login tramite file `.desktop` in `~/.config/autostart`, attivabile e
  disattivabile dalle impostazioni dell'app.
- Notifiche desktop native con azioni posticipa (+5 min, +1 h) e completa, allineate a quelle
  Android.
- Istanza singola: un secondo avvio porta in primo piano la finestra esistente.
- Packaging per Linux come **AppImage** singolo file, prodotto dallo script di build del progetto.

**C. Sincronizzazione P2P in rete locale**
- Evoluzione dello schema dati per renderlo sincronizzabile: identificatore stabile per evento,
  timestamp di ultima modifica, tombstone per le cancellazioni (migrazione Room v2 → v3).
- Discovery automatico delle istanze sulla stessa rete (annuncio + scoperta via mDNS/DNS-SD).
- Pairing esplicito e una tantum tra due istanze con conferma su entrambi i lati (codice
  numerico), con canale cifrato e credenziali persistite.
- Replica bidirezionale **punto-punto** fra i due dispositivi associati, con risoluzione dei
  conflitti deterministica (ultima scrittura vince, a livello di singolo evento).
- Conservazione illimitata di eventi completati e tombstone: nessuna epurazione automatica.
- Schermata di stato della sincronizzazione: dispositivi associati, ultimo sync, esito, azione
  manuale "sincronizza ora" e possibilità di dissociare un dispositivo.
- Riprogrammazione degli allarmi locali dopo ogni sincronizzazione che modifica eventi futuri.

**D. Documentazione**
- README aggiornato con build ed esecuzione desktop, requisiti di rete della sync, procedura di
  pairing.
- `CLAUDE.md` aggiornato con la nuova struttura a moduli.

### Escluso (out of scope)

- **Windows e macOS**: il codice condiviso non li preclude, ma build, packaging e test non
  rientrano in questa feature.
- **Sync via Internet / server centrale / account utente / cloud**: la sincronizzazione
  funziona solo con i dispositivi sulla stessa rete locale.
- **Sync di più di due dispositivi**: la topologia è punto-punto, una coppia associata alla
  volta. Nessun modello a mesh né riconciliazione multi-peer.
- **Packaging `.deb`/`.rpm` e pubblicazione su repository**: si produce solo l'AppImage.
- **Epurazione automatica dello storico** (completati, tombstone): per scelta si conserva tutto.
- **Merge a livello di campo**: in caso di modifica concorrente dello stesso evento vince
  l'ultima scrittura, senza fusione dei singoli campi né UI di risoluzione manuale.
- **iOS, web, PWA.**
- **Redesign della UI** e nuove funzionalità di prodotto: si porta l'esperienza esistente.
- **Sincronizzazione con calendari esterni** (Google Calendar, CalDAV).
- **Cifratura del database a riposo** su entrambe le piattaforme.

---

## 3. User Stories

1. **Come utente al PC** voglio consultare, creare, modificare e completare i promemoria da
   un'applicazione Linux **per** non dover prendere il telefono mentre lavoro.

2. **Come utente al PC** voglio ricevere la notifica del promemoria sul desktop, anche a
   finestra chiusa, **per** non perdere le scadenze mentre sono concentrato su altro.

3. **Come utente** voglio che l'app desktop parta da sola al login e resti nella tray
   **per** non doverla riaprire ogni volta che riavvio il computer.

4. **Come utente** voglio che telefono e PC si trovino da soli quando sono sulla stessa rete
   **per** non dover configurare indirizzi IP o porte.

5. **Come utente** voglio autorizzare esplicitamente l'associazione tra due dispositivi con un
   codice di conferma **per** essere certo che nessun altro sulla rete legga o alteri i miei
   promemoria.

6. **Come utente** voglio che le modifiche fatte offline (in aereo, fuori rete) si allineino
   da sole al primo rientro in rete **per** non dover ricordare cosa ho cambiato e dove.

7. **Come utente** voglio vedere quando è avvenuta l'ultima sincronizzazione e con quale
   dispositivo **per** accorgermi se qualcosa non sta funzionando.

8. **Come utente al PC** voglio esportare i promemoria in ODS e scegliere dove salvarli
   **per** archiviarli o aprirli in LibreOffice come già faccio da telefono.

9. **Come sviluppatore** voglio una sola codebase condivisa fra Android e desktop **per**
   implementare ogni nuova feature una volta sola.

10. **Come utente Android** voglio che l'app sul telefono continui a funzionare esattamente
    come prima **per** non pagare la versione desktop con regressioni sul mobile.

---

## 4. Criteri di accettazione

### Storia 1 — App desktop funzionante
- [ ] L'app si avvia su Linux (X11 e Wayland) e mostra la lista degli eventi attivi ordinati per data.
- [ ] Creazione, modifica, completamento, riattivazione ed eliminazione producono lo stesso
      risultato dell'app Android sugli stessi dati.
- [ ] Le tre schermate esistenti (lista, editor, completati) sono raggiungibili e navigabili
      con mouse e tastiera.
- [ ] Il database desktop risiede in una directory utente conforme allo standard XDG.

### Storia 2 — Notifiche desktop
- [ ] Alla scadenza (`dateTimeMillis - advanceMinutes * 60000`) compare una notifica di sistema
      con titolo e descrizione dell'evento.
- [ ] La notifica offre le azioni posticipa +5 min, posticipa +1 h e completa, con lo stesso
      effetto sui dati delle omologhe Android.
- [ ] La notifica arriva anche con la finestra chiusa (app in tray).
- [ ] Se l'app era spenta all'orario di scadenza, al successivo avvio viene mostrata una notifica
      di recupero per ogni evento scaduto e non completato (comportamento deciso: recupero all'avvio).

### Storia 3 — Tray e autostart
- [ ] La chiusura della finestra non termina il processo: l'app resta in tray.
- [ ] Dal menù della tray si aprono la finestra, la creazione rapida di un promemoria e l'uscita.
- [ ] L'opzione "avvia al login" crea/rimuove il file `.desktop` in `~/.config/autostart` e lo
      stato sopravvive al riavvio della macchina.
- [ ] Un secondo avvio dell'app non crea un secondo processo: riporta in primo piano il primo.

### Storia 4 — Discovery
- [ ] Con entrambe le app attive sulla stessa rete, ciascuna elenca l'altra entro 30 s senza
      configurazione manuale.
- [ ] Il nome mostrato identifica il dispositivo in modo leggibile (es. hostname / modello).
- [ ] Se la rete non consente il multicast, l'app lo segnala con un messaggio comprensibile e
      offre l'inserimento manuale di indirizzo e porta.

### Storia 5 — Pairing sicuro
- [ ] L'associazione richiede conferma su entrambi i dispositivi tramite un codice mostrato
      da un lato e digitato/confermato dall'altro.
- [ ] Un peer non associato che tenta di sincronizzare viene rifiutato.
- [ ] Il traffico di sincronizzazione è cifrato; un dispositivo terzo sulla stessa rete non
      può leggere i promemoria intercettando il traffico.
- [ ] La dissociazione elimina le credenziali del peer e interrompe le sincronizzazioni successive.

### Storia 6 — Replica e conflitti
- [ ] Un evento creato su un dispositivo compare sull'altro alla prima sincronizzazione utile.
- [ ] Un evento modificato su un dispositivo aggiorna l'altro senza duplicarsi.
- [ ] Un evento eliminato su un dispositivo viene eliminato anche sull'altro e non "risorge"
      alla sincronizzazione successiva.
- [ ] Modifiche concorrenti allo stesso evento convergono allo stesso risultato su entrambi i
      dispositivi (ultima scrittura vince), senza duplicati né perdita degli altri eventi.
- [ ] Dopo una sincronizzazione che tocca eventi futuri, gli allarmi locali risultano
      riprogrammati di conseguenza.
- [ ] Gli eventi già presenti prima dell'aggiornamento (schema v2) sopravvivono alla migrazione
      con i loro dati intatti.

### Storia 7 — Stato sincronizzazione
- [ ] Una schermata elenca i dispositivi associati con data/ora dell'ultima sincronizzazione riuscita.
- [ ] Gli errori (peer irraggiungibile, rifiutato, timeout) sono mostrati con un messaggio in italiano.
- [ ] È disponibile un comando manuale "sincronizza ora".

### Storia 8 — Export desktop
- [ ] L'export ODS è disponibile su desktop con i filtri esistenti (tutti / solo aperti).
- [ ] Il file viene salvato nella posizione scelta dall'utente tramite dialog di sistema e si
      apre correttamente in LibreOffice Calc.

### Storia 9 — Codebase condivisa
- [ ] Dominio, accesso ai dati, ViewModel, schermate Compose ed export vivono nel modulo condiviso.
- [ ] Il codice specifico per piattaforma è limitato ad allarmi, notifiche, filesystem/condivisione,
      tray/autostart e sync-transport.
- [ ] `./gradlew assembleDebug` (Android) e il task di build desktop completano entrambi da progetto pulito.

### Storia 10 — Nessuna regressione Android
- [ ] Il nome del package applicativo, il suffisso `.debug` e la firma di release restano invariati.
- [ ] Un aggiornamento sopra l'installazione esistente conserva i promemoria già presenti.
- [ ] Allarmi, notifiche con azioni, riprogrammazione al boot ed export/share continuano a funzionare.

---

## 5. Rischi e dipendenze

> Le versioni esatte delle librerie e la fattibilità puntuale vanno verificate in Fase 2
> sull'attuale `gradle/libs.versions.toml` (AGP 8.7.3, Kotlin 2.0.21, Room 2.6.1, Hilt 2.53.1).

| # | Rischio | Impatto | Mitigazione |
|---|---|---|---|
| R1 | **Hilt non supporta KMP**: va sostituito in tutta l'app (6 punti di iniezione + `ReminderApp` + ViewModel) | Alto | Migrare a una DI multipiattaforma (Koin) o a un container manuale; l'app è piccola, la seconda opzione è realistica |
| R2 | **Room 2.6.1 non è multipiattaforma**: serve l'aggiornamento alla linea con supporto KMP e driver SQLite bundled, con possibile catena di aggiornamenti (KSP, AGP, Kotlin) | Alto | Verificare in Fase 2 la matrice di compatibilità; isolare l'aggiornamento in una milestone a sé, prima del resto |
| R3 | **ViewModel/Navigation Compose su desktop**: le dipendenze attuali sono `androidx.*` orientate ad Android | Medio | Valutare le versioni multipiattaforma di lifecycle/navigation; in alternativa astrarre con un `StateHolder` proprio |
| R4 | **Restrizioni Android sul background**: mantenere in ascolto un socket per la sync a app chiusa è di fatto impedito dalle policy recenti | Alto (funzionale) | Modello asimmetrico: il desktop è sempre in ascolto, Android sincronizza all'apertura, al rientro in foreground e, se serve, con un servizio in foreground temporaneo |
| R5 | **mDNS su Android** richiede l'acquisizione del multicast lock e su alcune reti (AP isolation, WiFi ospiti, VLAN separate) il discovery non passa proprio | Medio | Fallback manuale con IP:porta esplicito, più messaggio diagnostico chiaro |
| R6 | **Firewall Linux** (ufw/firewalld) può bloccare la porta di ascolto | Medio | Documentare la porta usata; rilevare il fallimento del bind e segnalarlo in UI |
| R7 | **Chiavi primarie non sincronizzabili**: `EventEntity.id` è `autoGenerate` e collide fra istanze | Alto | Migrazione v3 con identificatore stabile generato lato client + `updatedAt` + tombstone; conservare l'`id` locale per compatibilità |
| R8 | **Clock skew** fra dispositivi: la strategia "ultima scrittura vince" dipende dagli orologi | Medio | Timestamp UTC + tolleranza; in Fase 2 valutare un contatore di versione per evento |
| R9 | **Ricomparsa di eventi cancellati** se le eliminazioni non lasciano traccia | Medio | Tombstone conservati per sempre (retention illimitata, da decisione utente): la tabella cresce in modo monotono ma resta trascurabile per volumi personali |
| R10 | **Regressioni sull'app Android** durante lo spostamento dei sorgenti nei nuovi moduli | Alto | Milestone dedicata, spostamento senza modifiche funzionali, verifica manuale su device prima di procedere |
| R11 | **Packaging AppImage**: `jpackage` produce app-image/`.deb`/`.rpm` ma **non** AppImage; serve `appimagetool`, che sulla macchina di sviluppo **non è installato**. L'artefatto include un runtime Java (~50-80 MB) | Medio | Pipeline in due passi: `jpackage --type app-image` + wrapping con `appimagetool` (scaricabile come AppImage a sua volta), incapsulata in `build.sh` |
| R12 | **Nessun test automatico nel progetto** (dichiarato in `CLAUDE.md`): la sincronizzazione è la prima parte non verificabile a occhio | Alto | Introdurre test unitari sul motore di merge e sui migration Room: prerequisito, non optional |
| ~~R14~~ **decaduto (2026-08-20)** — **System tray non garantita su tutti i desktop**: GNOME Shell ha rimosso la tray XEmbed usata da AWT/Compose Desktop e richiede l'estensione AppIndicator; Cinnamon/XFCE/KDE la supportano nativamente | Medio | Verificare l'ambiente reale del PC target (vedi nota sotto); in assenza di tray, degradare a processo in background senza icona, con la finestra riapribile dalla voce di menù |
| R13 | `applicationVariants` è deprecata e verrà rimossa in AGP 9: il rename dell'APK di release andrà riscritto se la migrazione KMP alza AGP | Basso | Riscrivere con la Variant API contestualmente all'eventuale aggiornamento |

**Dipendenze esterne:** JDK 17+ con `jpackage`, `appimagetool`, libreria mDNS lato JVM, backend di
notifica desktop (D-Bus / libnotify), supporto system tray nell'ambiente desktop in uso.

**Ambiente rilevato sulla macchina di sviluppo** (`2026-08-20`):

| Elemento | Esito |
|---|---|
| `XDG_CURRENT_DESKTOP` / `XDG_SESSION_TYPE` | `X-Cinnamon` / `x11` |
| `notify-send`, `gdbus` | presenti (`/usr/bin`) |
| `jpackage` | presente (JDK 21.0.11-tem via SDKMAN) |
| `appimagetool` | **mancante** — da procurare per M7 |
| Tray in uso | sì: `~/.config/autostart` contiene già applet in tray (`anydesk_global_tray`, `parcellite-startup`) |

> ✅ **Confermato il 2026-08-20:** l'app desktop girerà su **questa** macchina, Cinnamon su X11
> (fork GTK di GNOME, con tray XApp funzionante). R14 decade: nessuna estensione necessaria.

---

## 6. Stima effort

Unità: giorni/uomo di uno sviluppatore singolo. "Core" = modulo condiviso e logica; "UI" = Compose
e integrazione desktop.

| Area | Attività | Core | UI | Test | Doc |
|---|---|---:|---:|---:|---:|
| Ristrutturazione KMP | moduli, DI senza Hilt, Room KMP, expect/actual | 4,0 | 0,5 | 1,0 | 0,5 |
| App desktop base | avvio, finestra, navigazione, parità schermate, storage XDG | 1,0 | 2,0 | 0,5 | — |
| Allarmi e notifiche desktop | scheduler in-process, notifiche con azioni, recupero scadenze perse | 2,0 | 0,5 | 0,5 | — |
| Tray, autostart, istanza singola | menù tray, `.desktop`, lock | 1,0 | 1,0 | 0,5 | 0,5 |
| Export desktop | dialog di salvataggio, riuso di `OdsExporter` | 0,5 | 0,5 | 0,5 | — |
| Schema sincronizzabile | migrazione v3, uuid/updatedAt/tombstone, adeguamento DAO | 1,5 | — | 1,0 | — |
| Discovery + pairing | annuncio/scoperta, handshake, credenziali, canale cifrato | 3,0 | 1,0 | 1,0 | 0,5 |
| Motore di replica | protocollo, merge, riprogrammazione allarmi | 3,0 | 0,5 | 1,5 | — |
| UI stato sync | elenco peer, ultimo sync, errori, sync manuale | 0,5 | 1,0 | 0,5 | — |
| Packaging Linux | `.deb`/AppImage, icona, integrazione con `build.sh` | 1,5 | — | 0,5 | 0,5 |
| Verifica non-regressione Android | prove manuali su device | — | — | 1,5 | — |
| **Totale parziale** | | **18,0** | **7,0** | **9,0** | **2,0** |

**Totale: ~36 giorni/uomo**, con un intervallo realistico di **30-42 gg** a seconda di quanto
costa la catena di aggiornamenti Room/AGP/Kotlin (R2) e di quante iterazioni richiede la
sincronizzazione sul campo.

Sottoinsieme minimo utilizzabile (app desktop funzionante senza sync, milestone M1-M5):
**~16 giorni/uomo** — è il punto di consegna intermedio consigliato.

---

## 7. Milestones

| # | Milestone | Contenuto | Dipende da | Stima |
|---|---|---|---|---|
| **M0** | Prerequisiti tecnici | Verifica matrice versioni (Kotlin/AGP/KSP/Room KMP/Compose Multiplatform); prototipo throw-away che compila una schermata Compose su desktop con Room KMP | — | 2,0 |
| **M1** | Struttura a moduli | Creazione `:shared`, `:androidApp`, `:desktopApp`; spostamento sorgenti senza modifiche funzionali; l'app Android continua a compilare e girare | M0 | 3,0 |
| **M2** | DI senza Hilt | Sostituzione di Hilt nel codice condiviso e in `androidApp`; ViewModel istanziati in modo multipiattaforma | M1 | 2,5 |
| **M3** | Dati multipiattaforma | Room KMP, driver SQLite per piattaforma, percorsi DB `expect/actual`, migrazioni preservate | M1 | 2,5 |
| **M4** | UI desktop | Finestra, navigazione, tre schermate operative sul DB desktop | M2, M3 | 3,0 |
| **M5** | Allarmi, notifiche, tray, autostart | Parità di comportamento con Android a finestra chiusa; istanza singola | M4 | 4,5 |
| **M6** | Export su desktop | Riuso di `OdsExporter` con dialog di salvataggio nativo | M4 | 1,5 |
| **M7** | Packaging e distribuzione | AppImage (`jpackage --type app-image` + `appimagetool`), icona, `.desktop` interno, integrazione con `build.sh` | M5 | 2,5 |
| **M8** | Schema sincronizzabile | Migrazione v3 (identificatore stabile, `updatedAt`, tombstone) su entrambe le piattaforme + test di migrazione | M3 | 2,5 |
| **M9** | Discovery e pairing | Annuncio/scoperta in LAN, handshake con codice di conferma, credenziali persistite, canale cifrato, fallback manuale | M8 | 5,0 |
| **M10** | Motore di replica | Scambio delle modifiche, merge deterministico, riprogrammazione allarmi, gestione errori | M9 | 5,0 |
| **M11** | UI stato sincronizzazione | Elenco peer, ultimo sync, dissociazione, "sincronizza ora" | M10 | 2,0 |
| **M12** | Collaudo e documentazione | Prove telefono ↔ desktop su rete reale, non-regressione Android, README e `CLAUDE.md` | M11, M7 | 3,0 |

**Consegna intermedia consigliata:** al termine di **M7** l'app desktop è completa e installabile,
ancora senza sincronizzazione. Le milestone M8-M12 costituiscono la seconda tranche.

---

## Decisioni chiuse (dopo la revisione dell'utente)

| # | Domanda | Risposta |
|---|---|---|
| 1 | Ambiente desktop e sessione | **Cinnamon / X11** (confermato il 2026-08-20): tray nativa, R14 decade |
| 2 | Numero di dispositivi | **Punto-punto**: una sola coppia associata (telefono ↔ PC) |
| 3 | Scadenze perse a app spenta | **Notifica di recupero all'avvio** |
| 4 | Retention di completati e tombstone | **Per sempre**, nessuna epurazione automatica |
| 5 | Formato di distribuzione | **AppImage** (niente `.deb`) |

Nessuna domanda aperta residua: il documento è completo per l'approvazione.
