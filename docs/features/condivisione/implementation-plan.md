# Condivisione — Implementation Plan

**Stato:** Bozza — in attesa di approvazione
**Autore:** Alberto Goldoni (alberto.goldoni@euei.it)
**Data:** 2026-04-26
**Versione:** 1.0

---

## 1. Executive Summary

La feature **Condivisione** permette all'utente dell'app *Promemoria* di esportare i propri
eventi in un file in formato **ODS** (OpenDocument Spreadsheet) e di condividerlo verso
qualsiasi destinazione cloud o app installata sul dispositivo (Google Drive, Gmail, Dropbox,
ecc.) tramite il sistema di share intent nativo di Android. L'utente potrà scegliere se
esportare *tutti* i promemoria o *solo quelli aperti*. Stima complessiva: **~4 giorni/uomo**.

---

## 2. Obiettivo e motivazione

- **Problema che risolve:**
  Oggi i promemoria vivono esclusivamente nel database locale `reminder.db` (Room). Non
  esiste un meccanismo di backup, archiviazione o condivisione fuori dall'app. In caso di
  reinstallazione, cambio device o necessità di consultare la lista da desktop, i dati sono
  inaccessibili.

- **Metriche di successo (manuali, no analytics):**
  - [ ] L'azione "Esporta" è raggiungibile in ≤ 2 tap dalla schermata principale.
  - [ ] Il file generato si apre correttamente in LibreOffice Calc e Google Sheets.
  - [ ] Tempo di generazione su dataset di 100 promemoria < 1 s su device midrange.

- **Legame con obiettivi di prodotto:**
  Aumentare la fiducia dell'utente nel persistere i propri dati nell'app, abilitando
  scenari di backup e condivisione finora impossibili.

---

## 3. Scope

### Incluso
- Esportazione in formato `.ods` (MIME `application/vnd.oasis.opendocument.spreadsheet`).
- Filtro selezionabile dall'utente: **Tutti** o **Solo aperti**.
- Condivisione tramite `Intent.ACTION_SEND` + `FileProvider` (chooser nativo Android).
- Naming file predefinito: `promemoria_YYYYMMDD.ods` (data dell'export, no input utente).
- Foglio singolo "Promemoria" con colonne `Titolo | Descrizione | Data e ora | Anticipo (min) | Stato`.
- Gestione stati di caricamento e messaggi di errore localizzati in italiano.

### Escluso (out of scope)
- **Import da ODS** — direzione opposta, fuori scope.
- **Sincronizzazione bidirezionale o continua** col cloud — feature distinta, molto più costosa.
- **SDK Google Drive / OAuth diretto** — deleghiamo al sistema Android via share intent, evita complessità auth.
- **Schedulazione automatica** dell'export (backup periodico) — non richiesto.
- **Altri formati** (CSV, XLSX, PDF) — possibili in iterazioni future grazie all'interfaccia `Exporter`.
- **Crittografia del file** — non richiesto.
- **Foglio metadati** (data export, totali, versione app) — esplicitamente escluso dall'utente.
- **Colonne aggiuntive** (ID, timestamp creazione, snooze count) — esplicitamente escluso.
- **Test automatizzati** — coerente con lo stato attuale del progetto (no test configurati).

### Decisioni aperte

Tutte le decisioni di scoping sono state risolte in Fase 1. Resta una sola decisione tecnica
da chiudere in fase di POC:

| # | Decisione | Responsabile | Scadenza |
|---|-----------|-------------|---------|
| 1 | Conferma libreria ODS (SODS vs alternativa) dopo POC su Android | Alberto Goldoni | Prima di T-02 |

---

## 4. User Stories e criteri di accettazione

### US-001 · Esporta tutti i promemoria
**Priorità:** Must Have

Come utente voglio **esportare tutti i miei promemoria** in un file ODS per averne un
backup leggibile da altri dispositivi.

**Criteri di accettazione:**
- [ ] Esiste un'icona "Esporta" nella `TopAppBar` della schermata principale (lista eventi).
- [ ] Toccando l'icona si apre un dialog con due opzioni: "Tutti i promemoria" e "Solo aperti".
- [ ] Selezionando "Tutti", il file generato contiene **sia** gli eventi aperti **sia** quelli completati.
- [ ] Le righe sono ordinate per `dateTimeMillis` ascendente.

### US-002 · Esporta solo i promemoria aperti
**Priorità:** Must Have

Come utente voglio **esportare solo i promemoria aperti** per condividerli rapidamente con
un collega senza includere quelli già completati.

**Criteri di accettazione:**
- [ ] Selezionando "Solo aperti", il file generato contiene esclusivamente eventi con `completed = false`.
- [ ] Se non esistono eventi aperti, l'utente vede il messaggio "Nessun promemoria da esportare" e nessun file viene generato/condiviso.

### US-003 · Condivisione su cloud o app esterna
**Priorità:** Must Have

Come utente voglio **caricare il file esportato su un cloud a mia scelta** (Google Drive,
Dropbox, email, ecc.) direttamente dall'app, senza salvare prima manualmente su disco.

**Criteri di accettazione:**
- [ ] Dopo la generazione, parte automaticamente un `Intent.ACTION_SEND` con il file allegato.
- [ ] Il chooser Android mostra le destinazioni installate sul device.
- [ ] Il file è esposto via `FileProvider` con authority `${applicationId}.fileprovider`.
- [ ] Le destinazioni cloud principali (Google Drive, Gmail) ricevono correttamente l'allegato.

### US-004 · Feedback su errori e stati
**Priorità:** Should Have

Come utente voglio **vedere un messaggio chiaro in italiano** se la generazione o la
condivisione fallisce, così so che devo riprovare.

**Criteri di accettazione:**
- [ ] Durante la generazione, l'UI mostra uno stato di caricamento (spinner o disabilitazione bottone).
- [ ] In caso di errore di scrittura/I/O, snackbar o dialog con messaggio: "Errore nella generazione del file".
- [ ] In caso di lista vuota dopo filtro, messaggio dedicato (US-002).
- [ ] La generazione è completamente off main thread (`Dispatchers.IO`).

---

## 5. Architettura tecnica

### Componenti coinvolti

```
┌──────────────────────────────────────────────────────────────┐
│  EventListScreen (Compose)                                   │
│  - IconButton "Esporta" → AlertDialog scelta filtro          │
└──────────────┬───────────────────────────────────────────────┘
               │ viewModel.export(filter)
               ▼
┌──────────────────────────────────────────────────────────────┐
│  EventListViewModel                                          │
│  - StateFlow<ExportUiState>                                  │
│  - viewModelScope.launch { useCase.execute(filter) }         │
└──────────────┬───────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│  ExportEventsUseCase                                         │
│  - dao.getAll() / dao.getAllOpen()  (Dispatchers.IO)         │
│  - exporter.export(events, outputStream)                     │
│  - scrive file in cacheDir/exports/                          │
│  - ritorna Uri via FileProvider.getUriForFile(...)           │
└──────┬─────────────────────────────────┬─────────────────────┘
       │                                 │
       ▼                                 ▼
┌──────────────────┐              ┌──────────────────────────┐
│  EventDao        │              │  Exporter (interfaccia)  │
│  + getAll()      │              │   ↓                      │
│  + getAllOpen()  │              │  OdsExporter             │
└──────────────────┘              │  (libreria SODS)         │
                                  └──────────────────────────┘
                                              │
                                              ▼
                                  ┌──────────────────────────┐
                                  │  ShareHelper             │
                                  │  Intent.ACTION_SEND      │
                                  │  → Android chooser       │
                                  └──────────────────────────┘
```

### Modifiche al data model

Nessuna modifica allo schema Room. Solo aggiunta di query DAO:

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| `EventDao` | Modifica (additivo) | `suspend fun getAll(): List<EventEntity>` ordinato per `dateTimeMillis ASC` |
| `EventDao` | Modifica (additivo) | `suspend fun getAllOpen(): List<EventEntity>` (filtro `completed = 0`) |
| `EventEntity` | Nessuna | Le colonne esistenti coprono tutto il fabbisogno export |
| `AppDatabase` | Nessuna | Schema invariato — non serve migrazione |

### Nuove API o endpoint

Non applicabile (app mobile, no backend).

### Breaking changes

Nessuno. Tutte le modifiche sono additive (nuove query DAO, nuovi file, nuovi entry in manifest).

---

## 6. Piano di implementazione

| ID | Task | Area | Stima (gg) | Dipende da | Responsabile |
|---|---|---|---|---|---|
| T-01 | POC libreria ODS (SODS) su build debug Android — generazione file di prova, apertura su LibreOffice + Google Sheets | Spike | 0,5 | — | Alberto |
| T-02 | Aggiungere dipendenza ODS al version catalog `libs.versions.toml` e `app/build.gradle.kts` | Build | 0,1 | T-01 | Alberto |
| T-03 | Aggiungere query `getAll()` e `getAllOpen()` in `EventDao.kt` | Data | 0,2 | — | Alberto |
| T-04 | Creare package `export/` con `Exporter`, `ExportFilter`, `OdsExporter` | Core | 1,0 | T-02 | Alberto |
| T-05 | Implementare `ExportEventsUseCase` (filtro → DAO → exporter → Uri) | Core | 0,5 | T-03, T-04 | Alberto |
| T-06 | Configurare `FileProvider` (manifest + `res/xml/file_paths.xml`) | Infra | 0,3 | — | Alberto |
| T-07 | Implementare `ShareHelper` (`Intent.ACTION_SEND` + chooser) | UI | 0,2 | T-06 | Alberto |
| T-08 | Aggiornare `AppModule` Hilt con i provide per `Exporter`, `ExportEventsUseCase`, `ShareHelper` | DI | 0,2 | T-04, T-05, T-07 | Alberto |
| T-09 | Aggiornare `EventListViewModel`: `ExportUiState` + funzione `export(filter)` | UI | 0,3 | T-08 | Alberto |
| T-10 | Aggiungere icona "Esporta" in `TopAppBar` di `EventListScreen` + `AlertDialog` scelta filtro | UI | 0,5 | T-09 | Alberto |
| T-11 | Stati loading/error/empty in UI (snackbar + spinner) | UI | 0,3 | T-09 | Alberto |
| T-12 | Aggiungere stringhe italiane in `strings.xml` | UI | 0,1 | — | Alberto |
| T-13 | Test manuali su almeno 2 device (debug + release), apertura ODS su 2 client | QA | 0,5 | T-11 | Alberto |
| T-14 | Aggiornamento `CLAUDE.md` con nota sulla nuova feature e il nuovo package `export/` | Doc | 0,3 | T-13 | Alberto |

**Stima totale:** ~5 giorni/uomo (di cui 0,5 di POC, può ridursi a ~4 se il POC è immediato)
**Breakdown:** Core/Data 1,8gg · UI 1,4gg · Infra/Build 0,6gg · POC 0,5gg · QA 0,5gg · Doc 0,3gg

---

## 7. Piano di test

**Strategia generale:** **Solo test manuali**, in linea con lo stato attuale del progetto
(`CLAUDE.md` documenta esplicitamente "There are no tests configured in this project" e
l'utente ha confermato che non si vuole introdurre testing in questa feature).

### Test cases critici (manuali)

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Manuale | Export "Tutti" con dataset misto (5 aperti + 3 completati) → file contiene 8 righe | Alta |
| TC-02 | Manuale | Export "Solo aperti" con stesso dataset → file contiene 5 righe | Alta |
| TC-03 | Manuale | Export "Solo aperti" con 0 eventi aperti → messaggio "Nessun promemoria da esportare", nessun file | Alta |
| TC-04 | Manuale | File generato apribile in LibreOffice Calc 7+ con header e tipi corretti | Alta |
| TC-05 | Manuale | File generato apribile in Google Sheets (upload manuale o via chooser) | Alta |
| TC-06 | Manuale | Share verso Gmail come allegato → file ricevuto correttamente | Alta |
| TC-07 | Manuale | Share verso Google Drive → file caricato | Alta |
| TC-08 | Manuale | Date nel file in formato `dd/MM/yyyy HH:mm` | Media |
| TC-09 | Manuale | Stato `Aperto` / `Completato` localizzato in italiano | Media |
| TC-10 | Manuale | Convivenza app debug + release sullo stesso device → entrambe possono esportare senza conflitti FileProvider | Media |
| TC-11 | Manuale | Generazione su dataset di ~100 eventi < 1 s su device midrange | Bassa |

### Definition of Done

- [ ] Tutti i criteri di accettazione delle 4 user stories soddisfatti.
- [ ] Tutti i TC `Alta` superati su almeno 1 device fisico.
- [ ] TC-04 e TC-05 verificati personalmente (apertura del file su due client diversi).
- [ ] Build release firmata generata senza warning critici.
- [ ] Nessun crash durante un giro di smoke test su tutte le schermate (regressione).
- [ ] `CLAUDE.md` aggiornato.

---

## 8. Rischi e mitigazioni

| Rischio | Probabilità | Impatto | Mitigazione |
|---|---|---|---|
| Libreria SODS incompatibile con Android (dipendenze JVM-only) | Media | Alto — bloccante | T-01 POC dedicato. Fallback: generare manualmente XML interno ODS (~1 gg extra) |
| Aumento APK size oltre soglia accettabile | Bassa | Basso | Nessun minify attivo (`isMinifyEnabled = false`), libreria ~200-400KB attesi. Se diventa problema, attivare R8 separatamente |
| Conflitto authority `FileProvider` tra build debug (`.debug` suffix) e release | Bassa | Medio | Usare `${applicationId}.fileprovider` (placeholder manifest) — risolve automaticamente |
| Compatibilità file ODS tra LibreOffice vecchie versioni e Google Sheets | Bassa | Medio | Test esplicito (TC-04, TC-05). SODS produce ODS standard ISO/IEC 26300 |
| Dataset molto grande (>1000 eventi) → OOM o lag | Molto bassa | Basso | Use case già su `Dispatchers.IO`. Non ottimizziamo finché non emerge |
| Mancanza di test automatizzati nasconde regressioni future su questa feature | Media | Medio | Accettato — coerente con scelta progetto. Mitigato da TC manuali documentati |

---

## 9. Rollout e feature flag

**Strategia di rilascio:** Deploy diretto.

L'app è single-developer, single-module, senza CI/CD complesso e senza utenti esterni che
richiedano un rollout graduale. Una volta firmata la release, l'APK viene installato sui
device target via `install-all.sh`.

**Feature flag:** Nessuno. La feature è additiva, non modifica flussi esistenti, e disattivarla
selettivamente non porterebbe valore proporzionato alla complessità.

**Piano di rollback:**
1. Identificare il bug introdotto.
2. Hotfix mirato sul codice di export (file isolati nel package `export/`).
3. In caso di problema grave non risolvibile rapidamente: ripristinare il commit precedente
   alla feature (`git revert`) e rifare la build release.
   Nessuna migrazione DB da rollback (schema invariato).

---

## 10. Checklist di approvazione

| Revisione | Responsabile | Stato | Data |
|---|---|---|---|
| Revisione tecnica | Alberto Goldoni (self) | ⏳ In attesa | — |
| Approvazione scope | Alberto Goldoni | ✅ Approvato | 2026-04-26 |
| Stima approvata | Alberto Goldoni | ⏳ In attesa | — |
| Rischi accettati | Alberto Goldoni | ⏳ In attesa | — |
| Data di inizio confermata | Alberto Goldoni | ⏳ In attesa | — |

---

## Domande aperte

> Tutte le domande di scoping sono state risolte. Resta solo il punto tecnico legato al POC.

1. **Quale libreria ODS verrà adottata definitivamente?**
   Ipotesi corrente: `com.github.miachm.sods:SODS` (pure-Java, no AWT). Da confermare al
   termine di T-01 (POC). In caso di esito negativo: generazione manuale XML ODS.
   *Responsabile:* Alberto Goldoni — *Scadenza:* prima di T-02.

---

## Riferimenti

- Piano iniziale: [docs/features/condivisione/phase-1-plan.md](phase-1-plan.md)
- Analisi tecnica codebase: [docs/features/condivisione/phase-2-analysis.md](phase-2-analysis.md)
- File principali coinvolti:
  - [app/src/main/java/it/agoldoni/reminder/data/EventDao.kt](../../../app/src/main/java/it/agoldoni/reminder/data/EventDao.kt)
  - [app/src/main/java/it/agoldoni/reminder/ui/list/EventListScreen.kt](../../../app/src/main/java/it/agoldoni/reminder/ui/list/EventListScreen.kt)
  - [app/src/main/java/it/agoldoni/reminder/ui/list/EventListViewModel.kt](../../../app/src/main/java/it/agoldoni/reminder/ui/list/EventListViewModel.kt)
  - [app/src/main/java/it/agoldoni/reminder/di/AppModule.kt](../../../app/src/main/java/it/agoldoni/reminder/di/AppModule.kt)
  - [app/src/main/AndroidManifest.xml](../../../app/src/main/AndroidManifest.xml)

---

*Documento generato con la skill `claude-code-feature`.*
