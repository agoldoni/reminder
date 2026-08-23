# Promemoria modificabili dalla web app locale — Stato

**Aggiornato:** 2026-08-23
**Stato:** **implementata e verificata** su emulatore (API 33) e, per la parte di piattaforma, sul
telefono di prova (API 29). Resta la prova su rete reale, che in questo ambiente non è eseguibile.
**Piano:** [phase-3-implementation-plan.md](phase-3-implementation-plan.md)

---

## Task

| ID | Task | Stato |
|---|---|---|
| T-01 | **V-B bloccante:** conteggio righe di un `@Query("UPDATE …")` in Room KMP | ✅ 9 test — Room lo restituisce, l'`UPDATE` condizionato regge |
| T-02 | `HttpMessages`: corpo, limiti, `Transfer-Encoding`, doppio `Content-Length` | ✅ +17 test (da 20 a 37) |
| T-03 | `AccessToken` a due livelli | ✅ +7 test (da 10 a 17) |
| T-04 | `EventDao.updateIfUnchanged` + `FakeEventDao` | ✅ |
| T-05 | `WebPayload` v2: `updatedAt`, `permessi`, `scritturaDisponibile` | ✅ |
| T-06 | `ScrittureWeb`: validazione, `copy()`, ottimistico, allarmi | ✅ |
| T-07 | `ScrittureWebTest` | ✅ 24 test |
| T-08 | `Router`: rotte, id, `Allow`, `409`/`415`/`422`/`503` | ✅ |
| T-09 | `RouterTest` | ✅ 37 test (erano 15) |
| T-10 | `WebService`: allarmi, identità, coppia di token, bandiera | ✅ |
| T-11 | `WebStatus`: due token, due indirizzi | ✅ |
| T-12 | `ReminderApp` + **`MainActivity.onStop`** | ✅ |
| T-13 | `WebServiceTest` adeguato | ✅ 15 test |
| T-14 | `SezioneWebApp`: indirizzo a scomparsa (D-03) | ✅ |
| T-15 | `index.html` + `app.css` | ✅ |
| T-16 | `app.js`: modulo, comandi, `409`, stato spento | ✅ |
| T-17 | **Verifica sul campo** | ✅ e ha trovato **tre difetti** — vedi sotto |
| T-18 | `CLAUDE.md`, documenti, questo file | ✅ |

**Suite:** `./gradlew :shared:desktopTest :desktopApp:test` → **315 test, 0 falliti** (erano 237).
**Strumentati:** `CorpoPiattaformaTest` → 5 test, verdi su **API 33 e API 29**.

---

## T-17 ha trovato tre difetti, e nessuno dei tre poteva emergere dai test

### 1. `readNBytes` non esiste su Android al minSdk di questo progetto

Il difetto peggiore della feature, e sarebbe arrivato in produzione: la prima stesura leggeva il
corpo con `InputStream.readNBytes`, che sulla JVM c'è da Java 9 — quindi **tutti i 315 test erano
verdi** — ma su Android non è disponibile al `minSdk` 26.

Il guasto non era nemmeno rumoroso. Moriva dentro la coroutine che serve la connessione, il socket
si chiudeva senza rispondere, `logcat` non diceva niente e `curl` restituiva **`000`**, non un
codice di errore. Dall'esterno: la pagina si leggeva perfettamente e non salvava.

| Richiesta | Prima | Dopo |
|---|---|---|
| `GET /api/eventi` | 200 | 200 |
| `POST` con `Content-Length: 0` | 400 | 400 |
| `POST` con un corpo vero | **nessuna risposta** | 201 |

È la stessa trappola di `KeyStore.getDefaultType()` nella 003, e il presidio è nello stesso posto:
`CorpoPiattaformaTest` in `androidInstrumentedTest`, passato **5 su 5 anche su API 29**, dove il
metodo mancante mancava davvero.

### 2. L'annullamento stava dove nessuno lo guarda

La barra «Annulla» era nel flusso del documento, quindi finiva **sotto l'elenco e fuori schermo**.
Chi aveva appena toccato «Fatto» stava guardando il punto dove la riga era sparita, e per disfare
l'azione avrebbe dovuto scorrere fino in fondo per scoprire che l'opzione esisteva. Ora è ancorata
alla finestra, come lo snackbar dell'app.

### 3. `hidden` non nascondeva

L'attributo funziona solo perché la stylesheet del browser assegna `display: none`, e **qualunque**
regola d'autore la scavalca — anche `display: flex` su un contenitore. La barra compariva vuota su
ogni pagina, **compresa quella di sola lettura**, dove non ha alcun senso. Non si notava perché era
fuori schermo, ed è saltata fuori appena è diventata fissa.

---

## Un buco del piano chiuso in corso d'opera

**D-01 (annullare la completazione) non era implementabile come scritto.** Per annullare serve una
`PUT` con `attesoUpdatedAt`, ma il valore che il server ha appena scritto il browser non lo conosce:
la risposta è la lista dei **soli aperti**, e un evento appena completato non c'è più.

Metterlo nel corpo avrebbe rotto una proprietà che il piano difende e che un test presidia — che
l'impronta di una scrittura coincida con quella della `GET` successiva, così il polling riceve
`304`. Va quindi in un header, `X-Promemoria-Scritto: <id>:<updatedAt>`.

---

## Verifiche eseguite

| TC | Esito |
|---|---|
| TC-01…TC-17 (automatici) | ✅ 315 test verdi |
| TC-18 · **V-01, il criterio principale** | ✅ evento spostato **dal browser** da 12:14 a 11:15; l'allarme si è spostato (`dumpsys alarm`), quello vecchio è sparito, e **la notifica è arrivata alle 11:15:27** |
| TC-19 · V-02 | ✅ l'evento creato dal browser si apre nell'editor dell'app, «30 minuti prima» risolto, nessuna eccezione |
| TC-20 · V-03 | ✅ ad app chiusa: `GET` 200, `POST` **503** con la ragione, comandi spenti nella pagina e avviso comparso; riaprendo l'app tornano attivi da soli |
| TC-21 · V-04 | ✅ due indirizzi distinti dalla schermata; il token di lettura su una scrittura riceve `403` **e il database non cambia** |
| TC-22 · V-05 | ✅ in Chromium: modulo con i valori attuali, salvataggio, lista aggiornata **senza un giro di rete in più**, «Fatto» e annullamento |
| TC-23 · V-06 | ✅ nessuna riemissione del certificato |
| TC-24 | ✅ la pagina di sola lettura è di nuovo identica a quella di prima della feature |
| Ordine dei controlli | ✅ ad app chiusa, senza token o col token di lettura la risposta è **`403`, non `503`** |
| `409` | ✅ con la lista aggiornata nel corpo; la riga non è stata toccata |
| `415` / `422` | ✅ `Content-Type` sbagliato → 415; `advanceMinutes: 7` → 422 con «advanceMinutes: valore non fra 0, 5, 15, 30, 60» |

---

## Resta da fare

1. **Prova su rete reale** fra telefono e computer sullo stesso Wi-Fi. Stesso limite della 003: il
   telefono di prova è su `192.168.1.0/24` e il PC su `192.168.86.0/24`. Tutto ciò che si poteva
   provare passando da `adb forward` è stato provato.
2. **Prova con un browser di telefono**, per il modulo su schermo stretto: qui è stato provato in
   Chromium su desktop.
3. **La rotazione dello schermo durante una scrittura** (R-17): il caso è coperto per costruzione —
   un errore non chiude il modulo e non perde il testo — ma non è stato osservato dal vivo.
4. **Firefox** non è stato provato, come nella 003.
5. **Nulla di bloccante:** la feature è completa e coerente con il piano.

Idee rimandate: cancellazione dal browser, vista dei completati, sincronizzazione immediata dopo
una scrittura, segnale sul telefono di ciò che è cambiato dal browser (D-07, chiusa con un «no»).
