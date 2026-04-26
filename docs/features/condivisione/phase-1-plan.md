# Feature: Condivisione — Piano (Fase 1)

**Slug:** `condivisione`
**Data:** 2026-04-26
**Stato:** Bozza in attesa di approvazione

---

## 1. Obiettivo e motivazione

Permettere all'utente di esportare i propri promemoria in un file di foglio di calcolo
(formato **ODS** — OpenDocument Spreadsheet) e condividerlo / caricarlo su un servizio cloud.

**Problema risolto:**
- Mancanza di un meccanismo di backup leggibile fuori dall'app (oggi i dati vivono solo
  nel database Room locale `reminder.db`).
- Impossibilità di condividere la lista di promemoria con altre persone o consultarla
  da desktop / altro dispositivo.
- Necessità di un archivio storico in formato aperto e interoperabile (ODS apribile in
  LibreOffice Calc, Google Sheets, Excel).

**Valore:**
- Sicurezza dei dati (backup off-device).
- Interoperabilità (formato standard ISO/IEC 26300).
- Auditabilità (consultazione tabellare di eventi passati e futuri).

---

## 2. Scope

### Incluso

- Generazione di un file `.ods` contenente i promemoria dell'utente.
- Filtro a scelta dell'utente:
  - **Tutti** i promemoria (aperti + completati)
  - **Solo aperti** (non ancora completati)
- Condivisione del file via meccanismo Android (Share Intent / SAF) verso destinazioni
  cloud (Google Drive, Dropbox, OneDrive, email, ecc.).
- Feedback in UI su successo / errore della generazione.
- Localizzazione italiana coerente con il resto dell'app.

### Escluso (out of scope)

- **Import** da ODS verso il database (solo export, una direzione).
- **Sincronizzazione bidirezionale** o continua con il cloud.
- Autenticazione OAuth diretta a un cloud provider specifico (deleghiamo ad Android).
- **Schedulazione automatica** dell'export (es. backup giornaliero).
- Esportazione in altri formati (CSV, XLSX, PDF) — possibili come feature future.
- Crittografia del file esportato.

---

## 3. User Stories

**US-1** — Come utente voglio **esportare tutti i miei promemoria** in un file ODS
per averne un backup leggibile da altri dispositivi.

**US-2** — Come utente voglio **esportare solo i promemoria aperti** per condividerli
rapidamente con un collega senza includere quelli già completati.

**US-3** — Come utente voglio **caricare il file esportato su un cloud a mia scelta**
(Google Drive, Dropbox, email, ecc.) direttamente dall'app, senza salvare prima
manualmente su disco.

**US-4** — Come utente voglio **vedere un messaggio chiaro in italiano** se la
generazione o la condivisione fallisce, così so che devo riprovare.

---

## 4. Criteri di accettazione

### US-1 / US-2 — Generazione file
- [ ] Esiste un'azione "Esporta" / "Condividi" raggiungibile dalla schermata principale
      lista eventi (es. menu overflow in TopAppBar).
- [ ] Toccando l'azione, appare un dialog/bottom-sheet con due opzioni:
      "Tutti i promemoria" e "Solo aperti".
- [ ] Il file generato ha estensione `.ods` e MIME type `application/vnd.oasis.opendocument.spreadsheet`.
- [ ] Il file è apribile senza errori in **LibreOffice Calc 7+** e in **Google Sheets**.
- [ ] Il file contiene almeno le colonne:
      `Titolo` | `Descrizione` | `Data e ora` | `Anticipo notifica (min)` | `Stato`.
- [ ] Le date sono formattate in formato italiano (es. `26/04/2026 14:30`).
- [ ] Lo stato è localizzato (`Aperto` / `Completato`).

### US-3 — Condivisione
- [ ] Dopo la generazione, parte un `ACTION_SEND` Intent con il file allegato.
- [ ] Il chooser Android mostra le destinazioni installate (Drive, Gmail, ecc.).
- [ ] Il file è esposto via `FileProvider` (no permessi storage runtime su API 24+).

### US-4 — Errori
- [ ] Errori di scrittura file → snackbar/toast in italiano: "Errore nella generazione del file".
- [ ] Lista vuota dopo filtro → messaggio: "Nessun promemoria da esportare".

### Trasversali
- [ ] La generazione avviene **off main thread** (coroutine su `Dispatchers.IO`).
- [ ] L'UI mostra uno stato di caricamento mentre il file viene generato.
- [ ] Il file generato viene scritto nella cache app (`context.cacheDir`) e ripulito
      dal sistema senza intervento dell'utente.

---

## 5. Rischi e dipendenze

### Tecnici
| Rischio | Impatto | Mitigazione |
|---|---|---|
| Nessuna libreria ODS nativa Android — vanno valutate librerie Java come [SODS](https://github.com/miachm/SODS) o [FastODS](https://github.com/jferard/fastods) | Medio | POC iniziale per validare compatibilità con Android (no AWT/Swing dependencies) |
| Aumento dimensione APK per inclusione libreria ODS | Basso | Misurare delta; valutare `minifyEnabled` con regole ProGuard/R8 |
| Compatibilità con Android 7+ (API minima del progetto da verificare in Fase 2) | Medio | Verifica in Fase 2 + test su emulatori multipli |
| `FileProvider` mal configurato → `SecurityException` su share | Medio | Setup standard, già pattern noto in Android |

### Di progetto
- Necessità di chiarire se "cloud" = generic share intent (probabile) o integrazione
  diretta a Google Drive con SDK (più complesso, fuori scope nella formulazione attuale).
- Eventuale richiesta futura di formati aggiuntivi → progettare il layer di export
  per essere estendibile (interfaccia `Exporter` con implementazione `OdsExporter`).

---

## 6. Stima effort

> Stima preliminare, da raffinare dopo l'analisi codebase (Fase 2).

| Area | Effort | Dettaglio |
|---|---|---|
| **Backend / Data** | 1,5 gg | Use case `ExportEventsUseCase`, mapping `EventEntity` → riga ODS, integrazione libreria ODS |
| **UI / Compose** | 1,0 gg | Voce di menu, dialog scelta filtro, stato loading/error nel ViewModel |
| **Integrazione condivisione** | 0,5 gg | `FileProvider`, `ACTION_SEND` Intent, manifest |
| **Test manuali** | 0,5 gg | Apertura su LibreOffice, Google Sheets, share verso Drive/Gmail |
| **Documentazione** | 0,5 gg | Aggiornamento `CLAUDE.md`, eventuale `README` |
| **Totale** | **~4 gg/uomo** | |

---

## 7. Milestones

1. **M1 — POC libreria ODS** (0,5 gg)
   Selezione tra SODS / FastODS, generazione file di prova in app debug.

2. **M2 — Layer di export** (1 gg)
   Interfaccia `Exporter`, implementazione `OdsExporter`, use case con filtro.

3. **M3 — UI selezione filtro** (1 gg)
   Voce menu in `EventListScreen`, dialog con opzioni "Tutti / Solo aperti".

4. **M4 — Share Intent + FileProvider** (0,5 gg)
   Configurazione manifest, chooser Android.

5. **M5 — Stati di caricamento ed errore** (0,5 gg)
   Snackbar/toast localizzati, gestione lista vuota.

6. **M6 — Test e rifinitura** (0,5 gg)
   Test su almeno 2 device fisici / emulatori, apertura del file su 2 client diversi.

---

## Decisioni prese (2026-04-26)

1. **Cloud = share intent generico** (`ACTION_SEND` + `FileProvider`). No SDK Google Drive diretto.
2. **Nessuna colonna aggiuntiva** rispetto a `Titolo / Descrizione / Data e ora / Anticipo / Stato`.
3. **Nome file predefinito:** `promemoria_YYYYMMDD.ods` (data export, no input utente).
4. **Nessun foglio metadati** — singolo foglio con la sola tabella promemoria.
