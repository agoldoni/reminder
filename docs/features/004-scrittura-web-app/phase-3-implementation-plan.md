# Promemoria modificabili dalla web app locale — Implementation Plan

**Stato:** Bozza — in attesa di approvazione
**Autore:** Alberto Goldoni
**Data:** 2026-08-23
**Versione:** 1.0
**Feature:** 004 · `scrittura-web-app`
**Base:** `main` @ `73d1097` (2.3.0) — suite verde, **237 test**
**Estende:** [002-web-app-android](../002-web-app-android/status.md) · [003-https-web-app](../003-https-web-app/status.md)

---

## 1. Executive Summary

La pagina che il telefono serve ai browser della rete di casa oggi sa solo **mostrare** i promemoria.
Questa feature le dà tre comandi — modificare, creare, segnare fatto — così che una correzione da
dieci secondi non richieda di sbloccare il telefono. Il potere di usarli non sta in un'opzione
dell'app ma nell'**indirizzo**: se ne pubblicano due, uno di sola lettura da poter dare a qualcun
altro e uno completo da tenere per sé. La cancellazione resta esclusa: è l'unica operazione che dal
browser non si potrebbe disfare. La scrittura funziona **solo mentre l'app è aperta sul telefono**: ad app chiusa la porta torna
a essere esattamente quella della 003, in sola lettura. Stima **7,60 giorni/uomo**, nessuna
dipendenza nuova, nessuna migrazione del database.

---

## 2. Obiettivo e motivazione

**Problema che risolve.** La 002 ha messo la lista in un browser, la 003 ne ha cifrato il traffico.
Quel che resta è un'asimmetria che si sente ogni volta: la lista è sullo schermo grande, con la
tastiera sotto le mani, e per spostare un appuntamento di mezz'ora bisogna prendere in mano il
telefono, sbloccarlo, trovare l'app, trovare la riga.

**Perché adesso e non prima.** Non è ordine dei lavori: prima sarebbe stato irresponsabile. Su una
porta in chiaro, aggiungere la scrittura non avrebbe aggiunto un'API — avrebbe messo in rete un
**telecomando**. Chi ascoltava il filo prima della 003 leggeva il token e vedeva una lista; con la
scrittura avrebbe potuto spostare un appuntamento di tre ore e far suonare la notifica a cose fatte.

**Che cosa cambia nel modello di rischio.** L'attaccante resta quello della 003 — il Wi-Fi con
password condivisa. Cambia il **danno**: da un guasto che *espone* a un guasto che *sabota*.

| | Fino alla 003 | Con questa feature |
|---|---|---|
| Token compromesso | l'attaccante **legge** | l'attaccante **modifica** |
| Guasto peggiore | perdita di riservatezza | un appuntamento spostato, **una notifica che non suona** |
| Si nota? | mai | sì, ma **dopo** |

Da questa riga discendono due scelte di scope che non sono prudenza generica: la cancellazione fuori,
e i due indirizzi separati.

**Metriche di successo** (verifica manuale, nessuna analytics):

- [ ] Dal computer si sposta l'orario di un appuntamento e **la notifica sul telefono arriva all'ora
      nuova**, senza aver toccato la lista sul telefono. *È il criterio che vale più di tutti gli
      altri messi insieme.* (Con D-08 l'app dev'essere aperta: quel che si evita non è più
      **prendere** il telefono, è **navigarci dentro**.)
- [ ] Aprendo l'indirizzo di lettura i comandi non ci sono, e una scrittura costruita a mano contro
      quell'indirizzo riceve `403` **senza aver cambiato nulla nel database**.
- [ ] Un promemoria modificato sul telefono mentre la pagina era aperta non viene sovrascritto: la
      pagina lo dice e mostra il valore vero.
- [ ] Una modifica fatta dal browser arriva sull'altro dispositivo associato al giro di
      sincronizzazione successivo, senza trattamenti speciali.
- [ ] Un evento modificato dal web si apre senza errori nell'editor dell'app.
- [ ] L'impronta del certificato **non cambia**: l'avviso del browser non ritorna.

**Legame con gli obiettivi del progetto.** Promemoria è un'app personale a due dispositivi. Il filo
conduttore delle ultime tre feature è lo stesso: rendere i promemoria raggiungibili da dove ci si
trova (001 desktop, 002 browser, 003 in sicurezza). Questa chiude il cerchio rendendoli anche
**azionabili** da lì.

---

## 3. Scope

### Incluso

**Operazioni**

1. **Modifica** di un promemoria esistente: titolo, descrizione, data e ora, minuti di anticipo — gli
   stessi quattro campi dell'editor dell'app, né uno di più.
2. **Creazione** di un promemoria nuovo dalla pagina.
3. **Completamento** («fatto»), con **annullamento della completazione appena fatta** finché la
   pagina non viene ricaricata.

**Meccanica**

4. **Due token** per accensione, con due livelli d'accesso; entrambi si rigenerano a ogni accensione
   e si invalidano allo spegnimento.
5. **Due indirizzi** nella schermata dell'app: quello di sola lettura visibile, quello completo
   dietro una riga a scomparsa (D-03).
6. **Controllo ottimistico** su ogni scrittura, con `409` e ricarica.
7. **Validazione lato server** di tutto ciò che arriva.
8. **Riprogrammazione degli allarmi** a ogni scrittura, con la regola già scritta in `SyncEngine`.
9. **Lettura del corpo delle richieste** in `HttpMessages`, con limiti espliciti.
10. **Versione 2 del formato di scambio**.
11. **La scrittura richiede l'app aperta** sul telefono (D-08). Ad app chiusa i comandi della pagina
    sono spenti e una riga dice perché (D-09); si riaccendono da soli entro trenta secondi da quando
    si apre l'app, senza ricaricare niente.

### Escluso (out of scope)

| Fuori | Perché |
|---|---|
| **Cancellazione** | È l'unica operazione irreversibile, e dal browser mancherebbe la rete di sicurezza che sul telefono c'è. **Conseguenza accettata:** un promemoria creato per sbaglio dal browser si toglie solo dal telefono |
| **Vista dei completati** | La pagina mostra gli aperti. Riaprire un promemoria completato ieri resta un lavoro da app |
| **Modifica di `uuid`, `origin`, `deleted`** | Sono l'identità dell'evento fra dispositivi: non entrano da un browser |
| **Sincronizzazione immediata dopo una scrittura** | Una modifica dal web tocca `updatedAt` e viene raccolta dal giro successivo. Farla partire subito è un'altra feature |
| **Scrittura dal desktop** | `WebService` su desktop non è cablato (`WebServerNonDisponibile`). Non cambia |
| **Più utenti, permessi per evento, registro delle modifiche** | Il modello resta «un proprietario, due indirizzi» |
| **PWA installabile / uso offline** | Bloccata dal certificato non fidato (accertato dalla 003). Un modulo offline vorrebbe una coda di scritture da riconciliare: altra feature |
| **Autenticazione diversa dal token nella query** | Già annotata fra le idee rimandate della 003 |

### Decisioni chiuse

| # | Decisione | Esito | Dove |
|---|---|---|---|
| **D-01** | Che cosa vuol dire «riapri» | **Annullare la completazione appena fatta**, finché la pagina non viene ricaricata. Nessuna vista dei completati | Fase 1 §8 |
| **D-02** | Forma delle rotte di scrittura | **`POST /api/eventi`** e **`PUT /api/eventi/{id}`**, con `completato` fra i campi: anche il «fatto» è una modifica e passa dal controllo ottimistico | Fase 1 §8 · Fase 2 §B.4 |
| **D-03** | Come si mostrano i due indirizzi | **Sola lettura visibile, completo a scomparsa** — al contrario della proposta iniziale. Decisa sull'**asimmetria degli errori**, non sulla frequenza d'uso: copiare per sbaglio quello completo regala il telecomando e non dà segnali | Fase 2 §C.8 |
| **D-04a** | Insieme ammesso per `advanceMinutes` | **{0, 5, 15, 30, 60} e nient'altro.** Non è una scelta: `EventEditScreen.kt:153` fa `first { … }` senza `orNull`, quindi un sesto valore farebbe **lanciare l'editor dell'app** | Fase 2 §B.5 |
| **D-04b** | Normalizzazione di titolo e descrizione | **`trim()`, descrizione vuota ⇒ `null`** — identica a `EventEditViewModel.save()`, o le due strade divergono | Fase 2 §B.5 |
| **D-05** | Che cosa restituisce una scrittura riuscita | **La lista intera con il suo `ETag`**, cioè l'uscita di `corpoEventi`. Una sola funzione costruisce la rappresentazione, quindi lettura e scrittura non possono divergere | Fase 2 §B.4 |
| **D-06** | Atomicità del controllo ottimistico | **`UPDATE … WHERE id = :id AND updatedAt = :atteso`**, con il numero di righe toccate come esito. Un `Mutex` nel livello web non coprirebbe la corsa con il telefono, che è proprio il caso da coprire | Fase 2 §B.7 |
| **D-04c/d/e** | I tre limiti numerici | **Titolo 200 caratteri, descrizione 2000, date 1970–2100.** Approvati come proposti | Fase 3 |
| **D-08** ⛔ **revocata 23/08** — vedi §11 | Quando è possibile scrivere | ~~**Solo con l'app aperta** — Activity visibile, `onStart`…`onStop`. È l'unico stato che Android riporta in modo affidabile ed è simmetrico all'aggancio già esistente di `resume()`. Ad app chiusa la porta serve **solo letture**~~ · **V-D è passata: si scrive sempre** | Fase 3 · §11 |
| **D-09** ⛔ **decaduta con D-08** | Cosa mostra la pagina quando la scrittura non è disponibile | ~~**Comandi visibili ma spenti, con la ragione scritta.** Nasconderli farebbe sembrare l'indirizzo completo quello sbagliato; lasciarli attivi farebbe scoprire il rifiuto dopo aver scritto~~ · non c'è più nessuno stato da mostrare | Fase 3 · §11 |
| **D-07** | Il telefono segnala che qualcosa è cambiato dal browser? | **No.** Nessuna notifica, nessun indicatore: **la lista si aggiorna e basta**, cosa che già fa da sé perché `getActiveSortedAsc()` è un `Flow` di Room. Costo di implementazione: zero. L'unico utente è il proprietario, che sa di averlo fatto | Fase 3 |

### Decisioni aperte

Nessuna. Le tre residue sono state chiuse il 23/08/2026 e sono qui sopra (D-04c/d/e, D-07, D-08).

---

## 4. User Stories e criteri di accettazione

### US-001 · Correggere un orario senza prendere il telefono
**Priorità:** Must Have

Come utente seduto al computer, voglio cambiare data e ora di un promemoria dalla pagina web, per
correggerlo con la tastiera e lo schermo che ho davanti, invece di navigare lista ed editor su uno
schermo piccolo.

> **Nota su D-08.** La stesura originale di questa storia diceva «per non dover sbloccare il
> telefono». Con la scrittura limitata all'app aperta non è più vero, e lasciarlo scritto
> significherebbe misurare la feature su una promessa che non mantiene. Il beneficio che resta è
> reale ma più stretto, ed è quello scritto sopra.

**Criteri di accettazione:**
- [ ] Ogni riga della lista, sull'indirizzo completo, offre un modo evidente di aprire la modifica.
- [ ] Il modulo si apre con i valori attuali: titolo, descrizione, data e ora, minuti di anticipo.
- [ ] Salvando, la riga mostra i valori nuovi **senza attendere il giro di polling**.
- [ ] Sul telefono la riga corrispondente mostra gli stessi valori (l'elenco è un `Flow` di Room).
- [ ] **L'allarme è riprogrammato**: spostando l'orario avanti o indietro, la notifica arriva
      all'ora nuova.
- [ ] `uuid` e `origin` della riga sono identici a prima della modifica.
- [ ] Un titolo vuoto o di soli spazi non viene accettato, e la pagina lo dice.
- [ ] Annullando il modulo, niente è stato scritto.
- [ ] **Con l'app chiusa il comando di modifica è spento** e la pagina dice perché; una `PUT`
      costruita a mano riceve `503` e non cambia niente.

### US-002 · Scrivere con una tastiera vera
**Priorità:** Must Have

Come utente che sta creando un promemoria con una descrizione lunga, voglio poterlo fare dalla pagina
web, per scrivere con la tastiera del computer invece che con quella a schermo.

**Criteri di accettazione:**
- [ ] Dall'indirizzo completo si apre un modulo vuoto, con valori iniziali uguali a quelli dell'app
      (fra un'ora, quindici minuti di anticipo).
- [ ] L'evento creato compare nella lista dell'app e sulla pagina.
- [ ] Nasce con `origin` uguale all'identità del **telefono** e un `uuid` nuovo generato dal server.
- [ ] L'allarme del nuovo evento è programmato.
- [ ] Un evento con data nel passato è accettato e compare come scaduto (come nell'app).
- [ ] Con l'app chiusa il comando «Nuovo» è spento.

### US-003 · Tenere pulita la lista mentre si lavora
**Priorità:** Should Have

Come utente con la pagina aperta in una scheda, voglio segnare fatto un promemoria con un tocco, per
non accumulare cose già fatte fino a sera.

**Criteri di accettazione:**
- [ ] Un comando esplicito segna fatto il promemoria; la riga esce dalla lista degli aperti su
      entrambe le viste.
- [ ] L'allarme è **annullato**: la notifica non arriva più.
- [ ] Finché la pagina non è ricaricata, l'ultima completazione si può annullare, e l'evento torna
      aperto **con l'allarme riprogrammato**.
- [ ] Il completamento non altera titolo, data o descrizione.
- [ ] Con l'app chiusa il comando «Fatto» è spento.

### US-004 · Far guardare senza far toccare
**Priorità:** Must Have

Come utente, voglio poter dare a un'altra persona un indirizzo che mostra i promemoria ma non
permette di cambiarli, per condividere l'informazione senza condividere il comando.

**Criteri di accettazione:**
- [ ] La schermata mostra l'indirizzo di **sola lettura**; quello completo sta dietro una riga da
      toccare, con la frase che dice a chi non va dato (D-03).
- [ ] Toccando ciascuna riga si copia l'indirizzo corrispondente, con la conferma già in uso.
- [ ] Aprendo l'indirizzo di lettura, nessun comando di scrittura è visibile.
- [ ] Una scrittura costruita a mano con il token di lettura riceve `403` e **non modifica niente**.
      *È questo il criterio, non il precedente: nascondere non è proteggere.*
- [ ] Il token completo permette anche la lettura.
- [ ] Spegnendo e riaccendendo, **entrambi** gli indirizzi cambiano e i vecchi ricevono `403`.
- [ ] Dieci tentativi falliti da uno stesso IP fanno smettere di rispondere per un minuto, contando
      insieme i tentativi contro l'uno e contro l'altro token.
- [ ] Un token **valido ma insufficiente** non consuma il budget dei tentativi.

### US-005 · Non perdere una modifica fatta sul telefono
**Priorità:** Must Have

Come utente che ha modificato un promemoria dal telefono, voglio che una scheda del browser aperta da
un'ora non me la sovrascriva in silenzio, per potermi fidare di entrambe le viste.

**Criteri di accettazione:**
- [ ] Modificando lo stesso evento prima dal telefono e poi dalla scheda ferma, il salvataggio dal
      browser **fallisce**.
- [ ] La pagina lo spiega con parole comprensibili — non «409» — e mostra il valore attuale.
- [ ] Dopo la ricarica, rifare la stessa modifica funziona.
- [ ] Il caso simmetrico (due schede del browser) si comporta allo stesso modo.
- [ ] Due scritture simultanee sullo stesso evento non possono passare entrambe.

### US-006 · Sapere se una modifica è andata a segno
**Priorità:** Should Have

Come utente che ha appena salvato dal browser, voglio vedere subito il promemoria aggiornato nella
lista, per non restare nel dubbio di aver premuto a vuoto.

**Criteri di accettazione:**
- [ ] Al salvataggio riuscito la lista mostra i dati nuovi **senza un giro di rete in più**.
- [ ] Se il telefono non risponde durante un salvataggio, il testo digitato non va perso.
- [ ] Mentre si compila un modulo, l'aggiornamento periodico **non cancella ciò che si sta
      scrivendo** — né quello dei dati ogni 30 s né il ridisegno dei colori ogni 60 s.
- [ ] Un `403` su una scrittura produce un messaggio diverso da un `403` su una lettura.
- [ ] Aprendo l'app sul telefono, **i comandi si riaccendono da soli entro trenta secondi**, senza
      ricaricare la pagina: l'informazione viaggia nel polling che già c'è.

### US-007 · Non perdere quello che già funzionava
**Priorità:** Must Have

Come utente della web app di oggi, voglio che tutto ciò che funziona continui a funzionare.

**Criteri di accettazione:**
- [ ] L'indirizzo di lettura si comporta esattamente come la pagina di oggi.
- [ ] `304` continua a funzionare e la posizione di scorrimento non si perde.
- [ ] **Il certificato non viene riemesso**: l'impronta dopo questa feature è la stessa di prima.
- [ ] La porta parla solo TLS; nessun percorso nuovo è servito in chiaro.
- [ ] Le tre porte fisse restano distinte (47653 / 47700 / 9888).
- [ ] `./gradlew :shared:desktopTest :desktopApp:test` verde.

---

## 5. Architettura tecnica

### Componenti coinvolti

```
                                       ┌── token: lettura | scrittura
                                       │
Browser ═══TLS═══▶ HttpServer ──▶ leggiRichiesta ──▶ Router ──▶ AccessToken.verifica
   ▲               (invariato)      (+ corpo, NUOVO)    │              │
   │                                                    │              ▼ poi, e solo poi:
   │                                                    │       app aperta?  ◀── MainActivity
   │                                                    │       (no ⇒ 503)       onStart/onStop
   │                                                    │
   │              ┌─────────────────────────────────────┼──────────────────────┐
   │              ▼                                     ▼                      ▼
   │      GET  (lettura+)                    POST / PUT  (scrittura)     asset statici
   │              │                                     │                (elenco chiuso,
   │              │                            ScrittureWeb  (NUOVO)      invariato)
   │              │                              ├─ valida          (D-04)
   │              │                              ├─ copy() sulla riga esistente
   │              │                              ├─ updateIfUnchanged ──▶ EventDao ──▶ events
   │              │                              └─ cancel + schedule ──▶ AlarmScheduler
   │              │                                     │                      │
   │              └──────────▶ corpoEventi ◀────────────┘                      ▼
   │                          (una sola rappresentazione,               notifiche del
   └──────────────────────────  con il suo ETag)                            telefono
```

Due proprietà da leggere nel disegno:

- **`corpoEventi` è l'unico punto che costruisce la rappresentazione**, e ci arrivano sia la lettura
  sia le scritture (D-05). Lettura e scrittura non possono divergere perché non hanno due strade.
- **`ScrittureWeb` è l'unico punto che tocca il database e gli allarmi insieme.** È la mitigazione
  strutturale di R-05: non esistono due posti da tenere allineati.

**Precedente:** `SyncEngine` (`commonMain/sync/`) ha esattamente questa forma — DAO + `AlarmScheduler`,
scrive e rimette in riga le sveglie — ed è costruito in `ReminderApp.kt:56` con le stesse due
dipendenze. `ScrittureWeb` ripete una struttura accettata, non ne introduce una.

### Modifiche al data model

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| `events` (schema) | **Nessuna** | Ha già tutte le colonne: è la stessa scrittura che fa l'app. **Nessuna migrazione**, lo schema resta alla v5, `shared/schemas/` invariato |
| `EventDao` | **Modifica** | Un metodo nuovo: `updateIfUnchanged(...): Int` — `UPDATE … WHERE id = :id AND deleted = 0 AND updatedAt = :atteso`. La `SET` **non contiene `uuid` né `origin`**, quindi la regola «copiare, mai ricostruire» diventa una proprietà del codice invece che disciplina (R-10) |
| `FakeEventDao` | **Modifica** | Stessa semantica, conteggio delle righe compreso |
| `VoceWeb` | **Modifica** | `+ updatedAt` — serve al controllo ottimistico. Non è identità: è un numero di versione della riga |
| `WebPayload` | **Modifica** | `+ permessi` (`"lettura"` \| `"scrittura"`), `+ scritturaDisponibile` (D-08/D-09), `versione` 1 → 2 |
| `HttpRequest` | **Modifica** | `+ body: String = ""`. **`String` e non `ByteArray`**: è una data class, e questo codice ha già scritto a mano `equals`/`hashCode` due volte per quel motivo |
| `WebStatus` | **Modifica** | `token`/`url` → `tokenLettura`/`tokenScrittura`, `urlLettura`/`urlScrittura` |

### Nuove API o endpoint

| Metodo | Path | Descrizione | Auth |
|---|---|---|---|
| `GET` | `/api/eventi` | invariato, payload v2 | token **lettura o scrittura** |
| `POST` | `/api/eventi` | crea un promemoria | token **scrittura** |
| `PUT` | `/api/eventi/{id}` | modifica, completamento compreso | token **scrittura** |

**Corpo della `POST`** — `Content-Type: application/json`:

```jsonc
{ "titolo": "Dentista", "descrizione": "Portare la tessera",
  "dateTimeMillis": 1756000000000, "advanceMinutes": 15 }
```

**Corpo della `PUT`** — gli stessi più due:

```jsonc
{ "titolo": "…", "descrizione": null, "dateTimeMillis": 1756000000000, "advanceMinutes": 15,
  "completato": false,            // «fatto» e «annulla» sono questo campo
  "attesoUpdatedAt": 1755900000000 }
```

**Risposte:**

| Codice | Quando | Corpo |
|---|---|---|
| `200` / `201` | `PUT` / `POST` riuscita | lista intera + `ETag` (D-05) |
| `400` | JSON illeggibile, corpo oltre il limite, `Content-Length` assente o non numerico, `Transfer-Encoding` presente, `attesoUpdatedAt` mancante | vuoto |
| `403` | token assente, sbagliato, oltre soglia, **o di sola lettura su una scrittura** | vuoto — indistinguibili sul filo |
| `404` | percorso ignoto, id inesistente **o tombstone** | vuoto |
| `405` | metodo sbagliato su percorso valido | vuoto, con `Allow` per quel percorso |
| `409` | `attesoUpdatedAt` non coincide | lista **aggiornata** + `ETag` |
| `415` | `Content-Type` non è `application/json` | vuoto |
| `422` | validazione fallita | **con il nome del campo** |
| `503` | token di scrittura valido, ma **l'app non è aperta** sul telefono (D-08) | breve motivo |

**Il `503` è raggiungibile solo dopo l'autorizzazione al livello di scrittura**, e l'ordine non è
un dettaglio: controllare prima l'app aperta lascerebbe a uno sconosciuto senza token il modo di
sondare se il telefono è in uso.

**`scritturaDisponibile` non sostituisce `permessi`, gli sta accanto.** Servono due informazioni
diverse: `permessi` dice quale potere ha *questo indirizzo* — non cambia mai finché la porta resta
accesa — mentre `scritturaDisponibile` dice se in *questo momento* è esercitabile. Fonderli darebbe
una pagina che si traveste da quella di sola lettura appena il telefono va in tasca (D-09).
Il campo viaggia nel payload che il polling già chiede ogni trenta secondi: **la riaccensione dei
comandi non costa nemmeno una richiesta in più**.

Due asimmetrie deliberate, da non correggere in corsa:

- **Il `415` esiste per il CSRF.** Un modulo HTML su una pagina ostile può inviare solo
  `x-www-form-urlencoded`, `multipart/form-data` o `text/plain`: pretendere `application/json` fa
  scattare il preflight CORS, che una pagina cross-origin non supera. Costa una riga.
- **Il `422` dice quale campo, il `403` non dice niente.** Parlano a due interlocutori diversi: il
  `403` risponde a uno sconosciuto che sta sondando, il `422` a un client che ha **già dimostrato**
  di avere il token di scrittura.

### Breaking changes

| Componente | Tipo | Piano di migrazione |
|---|---|---|
| **`WebStatus.url` e `.token`** | **Rimossi**, non deprecati | È voluto: tenerli come alias del solo indirizzo di lettura darebbe codice che compila e sbaglia in silenzio, dando o negando per errore i pieni poteri. L'errore di compilazione è la migrazione. Punti da correggere: `SezioneWebApp.kt:80`, cinque test in `WebServiceTest` |
| **`WEB_PAYLOAD_VERSION` 1 → 2** | Formato di scambio | È esattamente il caso per cui quella costante esiste. Il client controlla la versione e, se non la riconosce, **lo dice** invece di provare a lavorare. In pratica non capita: `Cache-Control: no-store` fa riscaricare `app.js` |
| `AccessToken.rigenera()` / `.valore` | Cambiano tipo | Interno a `jvmSharedMain`; 12 test da adeguare |
| `Router` (costruttore) | Nuove dipendenze | Interno; un punto di costruzione (`WebService.kt:44`) e uno di test |
| `HttpRequest.body` | **Additivo** con valore predefinito | Nessuna: i tre punti di costruzione non cambiano |
| `WebService` (costruttore) | `+ alarmScheduler`, `+ deviceId` | `ReminderApp.kt:66` ha già entrambi nello scope (righe 44 e 46): due argomenti, zero logica |

**Nessun breaking change verso l'utente:** nessuna migrazione di database, nessun dato da convertire,
nessun indirizzo salvato che smetta di funzionare più di quanto già non faccia (il token si rigenera
a ogni accensione da sempre).

---

## 6. Piano di implementazione

Responsabile unico: Alberto Goldoni. Aree: **Core** (logica in `:shared`), **Infra** (verifiche di
piattaforma), **UI** (Compose), **Web** (asset della pagina), **Test**, **Doc**.

| ID | Task | Area | Stima (gg) | Dipende da |
|---|---|---|---|---|
| T-01 | **Verifica bloccante V-B:** il conteggio delle righe restituito da un `@Query("UPDATE …")` in Room KMP, su `BundledSQLiteDriver` (desktop) e `AndroidSQLiteDriver`. Inserire, chiamare due volte con lo stesso `attesoUpdatedAt`, attendersi `1` e poi `0` | Infra | 0,15 | — |
| T-02 | `HttpMessages`: `MAX_CORPO`, `Content-Length` obbligatorio e validato, rifiuto di `Transfer-Encoding` e del doppio `Content-Length`, `body` in `HttpRequest`, codici `409`/`415`/`422` in `descrizione()`. **Il commento delle righe 82-84 va riscritto, non cancellato**: la ragione per cui ora il corpo si legge sta accanto ai limiti che lo rendono sicuro. +12 test | Core | 0,60 | — |
| T-03 | `AccessToken`: due segreti, `Accesso` a quattro esiti, confronto **contro entrambi senza scorciatoie**, un solo contatore dei tentativi, nessun addebito per token valido ma insufficiente. +6 test | Core | 0,40 | — |
| T-04 | `EventDao.updateIfUnchanged` + `FakeEventDao` + test del conteggio | Core | 0,25 | T-01 |
| T-05 | `WebPayload` v2: `updatedAt` in `VoceWeb`, `permessi` e `scritturaDisponibile` nella busta, `WEB_PAYLOAD_VERSION` a 2 | Core | 0,25 | — |
| T-06 | `ScrittureWeb`: validazione (D-04), `copy()` sulla riga esistente, controllo ottimistico, riprogrammazione degli allarmi **riusando la regola di `SyncEngine.reschedule`** | Core | 0,60 | T-04, T-05 |
| T-07 | `ScrittureWebTest` — ~20 test, il cuore della feature senza rete | Test | 0,50 | T-06 |
| T-08 | `Router`: rotte di scrittura, id nel percorso, autorizzazione per livello, `Allow` per percorso, `405`/`409`/`415`/`422`, e il **`503` dopo il controllo del token** (D-08) | Core | 0,55 | T-02, T-03, T-06 |
| T-09 | `RouterTest`: **spaccare** `ogni metodo diverso da GET riceve 405` (riga 43) in «mai ammessi» e «ammessi solo su certi percorsi»; `"versione":2` (riga 143); +20 nuovi, fra cui l'**ordine** dei due controlli | Test | 0,55 | T-08 |
| T-10 | `WebService`: `alarmScheduler` e `deviceId` in ingresso, coppia di token in `enable()`/`apri()`, riuso del `now` già presente | Core | 0,25 | T-08 |
| T-11 | `WebStatus`: due token e due indirizzi. **Commit isolato, nessuna logica** | Core | 0,10 | — |
| T-12 | `ReminderApp`: due argomenti a `WebService`. **`MainActivity`: `onStop` che abbassa la bandiera** e `onStart` che la rialza — il commento delle righe 37-40, che dice «non c'è un `onStop` corrispondente», va riscritto perché ora ce n'è uno **che non chiude la porta** | Core | 0,20 | T-10 |
| T-13 | `WebServiceTest`: adeguamenti + 5 test nuovi, fra cui una `POST`, un `409` e un `503` **sopra TLS** attraverso il servizio vero | Test | 0,40 | T-12 |
| T-14 | `SezioneWebApp`: indirizzo di lettura visibile, indirizzo completo dietro una riga a scomparsa che **ricalca `Impronta` (riga 179)**, conferma di copia che dice quale indirizzo | UI | 0,40 | T-11 |
| T-15 | `index.html` + `app.css`: contenitore del modulo **fuori da `#elenco`**, stili con le variabili cromatiche già presenti | Web | 0,30 | — |
| T-16 | `app.js`: modulo di modifica e creazione, «fatto» e annullamento, `409` con messaggio comprensibile, i **due significati del `403`** (righe 117-121), **sospensione del ridisegno mentre il modulo è aperto** (R-07), lettura di `permessi` e **stato spento con la ragione** quando `scritturaDisponibile` è falso (D-09). Stile ES5 del file | Web | 1,15 | T-09, T-15 |
| T-17 | **Verifica sul campo** V-01…V-06: la notifica all'ora nuova, l'editor dell'app sull'evento modificato dal web, **la scrittura rifiutata ad app chiusa e i comandi che si riaccendono da soli riaprendola**, i due indirizzi copiati, il modulo in un browser vero, l'impronta invariata | Test | 0,55 | T-14, T-16 |
| T-18 | `CLAUDE.md` (la sezione `web/` cambia in **quattro** punti oggi scritti al contrario), i documenti di feature, `status.md`, riga di rimando nella 003 | Doc | 0,40 | T-17 |

**Stima totale:** **7,60 giorni/uomo** — intervallo realistico **6–9**.
**Breakdown:** Core 3,20 · Infra 0,15 · UI 0,40 · Web 1,45 · Test 2,00 · Doc 0,40

> **+0,45 per D-08.** La bandiera del primo piano costa poco in sé (T-12), ma si paga in quattro
> punti diversi: il campo nel payload, il controllo nel router **nell'ordine giusto**, lo stato
> spento nella pagina e i test dell'ordine. In cambio **toglie** l'unica incognita senza piano B —
> la scrittura al database ad app chiusa (V-D) — che stava in fondo alla verifica sul campo, cioè
> nel posto peggiore in cui trovare una sorpresa.

> **Sulla differenza con la Fase 1** (6,25 → 7,15 con l'analisi, poi → 7,60 con D-08). Sul primo
> scarto, **+0,90**: Lo scarto sta in due posti e vale la
> pena dirlo: la verifica bloccante T-01 non era una voce della tabella di fase 1, e i test erano
> stimati in blocco (1,00) mentre enumerati diventano 1,95 — perché `ScrittureWebTest` e i diciotto
> test nuovi del router sono lavoro vero, non contorno. Il lavoro di scrittura è rimasto quello
> previsto. **È lo stesso scarto, e per lo stesso motivo, che hanno registrato la 002 (8,00 → 11,00)
> e la 003 (3,00 → 5,25): le stime a grana grossa dimenticano la verifica.**

**Ordine consigliato.** T-11 è isolabile e si può committare per primo. T-01 va eseguita
**all'inizio**, non a metà: è l'unica incognita che potrebbe cambiare la forma di `updateIfUnchanged`
e quindi le firme che T-06 usa. T-02, T-03 e T-05 sono indipendenti fra loro e da T-01: se la
verifica facesse storie, c'è lavoro pronto. T-15 non dipende da niente e si può fare in un momento
morto.

---

## 7. Piano di test

**Strategia generale.** Tre scelte, ciascuna con la sua ragione.

1. **La superficie nuova più esposta è il corpo della richiesta, e si prova a livello di unità.**
   `HttpMessages` è l'unico punto che legge byte da una rete pubblica, e i suoi difetti sono di
   forma, non di integrazione: `Content-Length` mentito, doppio, negativo, `chunked`. Dodici test
   piccoli valgono più di un test di integrazione grande.
2. **Il cuore della feature si prova senza rete.** `ScrittureWeb` riceve DAO e programmatore di
   allarmi e restituisce un esito: gli allarmi si verificano con un finto programmatore che registra
   le chiamate, esattamente come si fa per `SyncEngine`. La rete non aggiunge nulla a quelle
   verifiche e aggiunge solo modi di fallire.
3. **Un solo giro completo sopra TLS, ma vero.** `WebServiceTest:327` è già l'unico test che
   attraversa il servizio reale cifrato: va **esteso**, non affiancato da un secondo.

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Unit | Un corpo con `Content-Length` esatto si legge per intero, N byte e non uno di più | Alta |
| TC-02 | Unit | `Content-Length` assente su `POST`, non numerico, negativo, oltre `MAX_CORPO` ⇒ `400` **senza aver letto il corpo** | Alta |
| TC-03 | Unit | `Transfer-Encoding: chunked` e due `Content-Length` in disaccordo ⇒ `400`, senza tentare di interpretarli | Alta |
| TC-04 | Unit | `Content-Length` maggiore dei byte inviati ⇒ non si resta appesi; minore ⇒ si legge solo quanto dichiarato | Alta |
| TC-05 | Unit | Il token di scrittura legge; quello di lettura **non** scrive; un token valido ma insufficiente **non consuma tentativi**; i due contatori sono uno solo | Alta |
| TC-06 | Unit | `advanceMinutes` fuori da {0,5,15,30,60} ⇒ `422`. *Il test cita il perché: `EventEditScreen.kt:153` farebbe lanciare l'editor dell'app* | Alta |
| TC-07 | Unit | Titolo vuoto / soli spazi / oltre il limite ⇒ `422` col nome del campo; `trim()` e descrizione vuota ⇒ `null`, **come fa il ViewModel** | Alta |
| TC-08 | Unit | **`uuid` e `origin` identici prima e dopo una modifica**; un evento creato nasce con `origin = deviceId` e `uuid` nuovo | Alta |
| TC-09 | Unit | Allarmi: creare ⇒ `schedule`; spostare ⇒ `cancel` + `schedule`; completare ⇒ `cancel` e **non** `schedule`; annullare ⇒ `cancel` + `schedule` | Alta |
| TC-10 | Unit | Una scrittura rifiutata (`409`, `422`) ⇒ **nessuna chiamata al programmatore di allarmi e nessuna riga toccata** | Alta |
| TC-11 | Integration | `attesoUpdatedAt` diverso ⇒ `409` **con la lista aggiornata nel corpo**; assente ⇒ `400` | Alta |
| TC-12 | Integration | Due `PUT` simultanee sullo stesso evento: **una sola passa** | Alta |
| TC-13 | Integration | Token di lettura su una scrittura ⇒ `403` **e il database non è cambiato** | Alta |
| TC-14 | Integration | La risposta di una scrittura è identica al corpo di `GET /api/eventi`, con `ETag` coerente: la richiesta successiva riceve `304` | Alta |
| TC-15 | Integration | `POST /` ⇒ `405` con `Allow: GET`; `PUT /api/eventi` ⇒ `405`; `/api/eventi/abc` ⇒ `404`; id ignoto e id di tombstone ⇒ `404`; `Content-Type` sbagliato ⇒ `415` | Media |
| TC-16 | Integration | Payload v2: `permessi` riflette il token usato, `"versione":2`, **`uuid` e `origin` restano fuori** | Media |
| TC-17 | Integration | Giro completo **sopra TLS**: `POST`, `PUT`, `409`, `403` col token di lettura | Alta |
| TC-17b | Integration | Con l'app dichiarata chiusa: `POST` e `PUT` ⇒ `503`; `GET` ⇒ `200`. E **l'ordine**: senza token, ad app chiusa, la risposta è `403` e non `503` — altrimenti si potrebbe sondare se il telefono è in uso | Alta |
| TC-17c | Integration | `scritturaDisponibile` nel payload segue la bandiera; `permessi` **no**, resta quello del token | Media |
| TC-18 | Manuale · V-01 | **Spostare l'orario dal browser e vedere arrivare la notifica all'ora nuova.** *Criterio principale della feature* | Alta |
| TC-19 | Manuale · V-02 | Aprire nell'editor dell'app un evento modificato dal web: nessuna eccezione | Alta |
| TC-20 | Manuale · V-03 | Con l'**app chiusa**: la pagina si legge, i comandi sono spenti con la loro ragione, e **riaprendo l'app si riaccendono da soli entro trenta secondi** senza ricaricare | Alta |
| TC-21 | Manuale · V-04 | I due indirizzi copiati dalla schermata funzionano e hanno poteri diversi | Alta |
| TC-22 | Manuale · V-05 | Il modulo in un browser vero: tastiera, `<dialog>`, e **il ridisegno che non cancella ciò che si sta scrivendo** | Alta |
| TC-23 | Manuale · V-06 | Non-regressione 003: **l'impronta del certificato non cambia** dopo l'aggiornamento | Alta |
| TC-24 | Manuale | Non-regressione 002: `304`, posizione di scorrimento, asset, `403` senza token | Media |

### Definition of Done

- [ ] `./gradlew :shared:desktopTest :desktopApp:test` verde: da **237** test a circa **305** (+12 corpo HTTP, +6 token, +18 router, +20 scritture, +4 servizio, +2 DAO).
- [ ] TC-01…TC-17 automatizzati e passanti.
- [ ] TC-18…TC-24 spuntati a mano, con esito annotato in `status.md`.
- [ ] Tutti i criteri di accettazione di §4 verificati.
- [ ] Nessun ANR e nessun crash nei log durante la sessione di verifica.
- [ ] Nessuna dipendenza nuova nel catalogo delle versioni; nessuna migrazione di schema.
- [ ] `CLAUDE.md` aggiornato nei **quattro** punti che questa feature contraddice.
- [ ] Rilettura del codice di `ScrittureWeb` con un occhio solo: **si può arrivare al database
      senza passare dal programmatore di allarmi?** Se sì, è R-05 che rientra dalla finestra.

---

## 8. Rischi e mitigazioni

| Rischio | Prob. | Impatto | Mitigazione |
|---|---|---|---|
| **R-05 — La modifica c'è nel database ma l'allarme è quello vecchio.** Il guasto silenzioso peggiore della feature: sembra funzionare e fallisce all'ora sbagliata | Media | **Alto** | Un solo punto che scrive e riprogramma (`ScrittureWeb`), con la regola **già scritta** in `SyncEngine.reschedule` riusata alla lettera. TC-09, TC-10 e TC-18. La domanda della Definition of Done è il presidio finale |
| **R-01 — Leggere un corpo è codice nuovo su una porta esposta.** `HttpMessages` oggi non lo legge *di proposito* | Media | **Alto** | Limite duro, solo `Content-Length` numerico, rifiuto esplicito di `Transfer-Encoding` e del doppio header. Attenuante strutturale: **non c'è keep-alive**, quindi un corpo letto male non può contaminare una richiesta successiva — non ce n'è una. TC-01…TC-04 |
| **R-07 — Il ridisegno cancella il modulo mentre si scrive.** `app.js:91` azzera `#elenco`; gira ogni 30 s e ogni 60 s | **Alta** | Medio | **Due difese**: il modulo vive fuori da `#elenco`, e il ridisegno è sospeso mentre è aperto. Si manifesta sempre, quindi TC-22 lo vede subito |
| **R-08 — Nascondere i comandi non è proteggerli.** La stessa pagina servita ai due token deve *sembrare* diversa | Bassa | **Alto** | Il campo `permessi` è dichiarato per iscritto come dato di presentazione; il controllo è in `Router`, su ogni scrittura. TC-13 verifica **che il database non sia cambiato**, non il codice di stato |
| **R-17 ⚠️ nuovo — La rotazione dello schermo abbassa la bandiera.** `onStop`/`onStart` è un giro completo a ogni rotazione: per una frazione di secondo la scrittura risulta non disponibile. **È la terza volta che questo progetto inciampa nella stessa cosa** — token rigenerato, certificato riemesso, e ora questo | Media | Basso | Il danno massimo è un comando spento per un istante o un `503` da ritentare. La pagina non deve perdere il testo digitato quando riceve un `503`, che è già il criterio di US-006. Da verificare in TC-20 **ruotando davvero il telefono** |
| **R-02 — Il token completo è un telecomando.** Se sfugge, l'attaccante sabota invece di leggere | Bassa | **Alto** | I due token separati; l'indirizzo completo dietro un tocco in più (D-03); rigenerazione a ogni accensione; **nessuna cancellazione**, quindi il danno massimo è reversibile a mano; TLS della 003. **D-08 lo riduce ancora:** il telecomando funziona solo mentre il proprietario ha l'app davanti |
| **R-04 — La corsa fra due scritture.** `HttpServer` serve ogni connessione in una coroutine sua | Bassa | Medio | `updateIfUnchanged`: in SQLite un `UPDATE … WHERE` è atomico e il conteggio *è* l'esito del confronto. TC-12. Dipende da T-01 |
| **R-11 — Valori assurdi accettati.** Più grave di come sembrava: non produce un allarme strano, produce **un'eccezione nell'editor dell'app** | Media | Medio | Insieme chiuso per `advanceMinutes` (D-04a) e limiti dichiarati per il resto. TC-06 |
| **R-10 — Ricostruire l'entità invece di copiarla.** Chi scrive il gestore parte da un JSON, che *sembra* già un evento | Bassa | **Alto** | **Reso impossibile per costruzione**: la `SET` di `updateIfUnchanged` non contiene `uuid` né `origin`. Da rischio di disciplina a proprietà del codice. TC-08 |
| **R-06 — CSRF e DNS rebinding** | Bassa | Medio | Senza token non passa nulla, e il token non è leggibile dal `Referer` (`no-referrer`). In più il `415` su `Content-Type`, che chiude la via del modulo cross-origin a costo di una riga |
| **R-14 — `WebStatus.url` sparisce e qualcosa resta indietro** | Alta | Basso | È voluto: l'errore di compilazione è la migrazione. Sei punti noti |
| **R-12 — Il `403` ha due significati** | Media | Basso | Distinzione **nel client**, in base a che cosa si stava facendo; la risposta sul filo resta indistinguibile, perché la ragione della 002 vale ancora |
| **R-15 — La finestra di 30 secondi.** Una modifica dal telefono resta invisibile alla pagina fino a mezzo minuto | Alta | Basso | Fatto, non difetto — ed è **la ragione per cui il `409` è obbligatorio e non opzionale** |
| **R-03 — Fuso orario del browser diverso da quello del telefono** | Bassa | Basso | **Declassato a nota** dopo l'analisi: la pagina già *mostra* le date nel fuso del browser, quindi scriverle lì è coerente con ciò che l'utente vede. La scrittura non aggiunge divergenza. Da documentare, non da risolvere |
| **R-13 — Payload v2 contro un `app.js` in cache** | Bassa | Basso | `no-store` lo rende improbabile; il client controlla `versione` e lo dice invece di lavorare al buio |
| **R-16 ⚠️ nuovo — Lo scope si allarga in corsa alla cancellazione.** A metà lavoro «manca solo `DELETE`» sembrerà un'ora | Media | Medio | È una decisione di prodotto presa e scritta in tre documenti, non un dettaglio implementativo: va riaperta esplicitamente, non aggiunta |

---

## 9. Rollout e feature flag

**Strategia di rilascio:**
- [x] **Graduale con feature flag** — il flag esiste già ed è quello della 002.
- [ ] Deploy diretto
- [ ] Canary release

Non c'è un canale di distribuzione da orchestrare: l'APK si installa a mano sui dispositivi
dell'utente. La gradualità sta nel fatto che, dopo l'aggiornamento, **l'app si comporta esattamente
come prima** finché l'interruttore resta spento — e quando lo si accende, la differenza visibile è
una riga in più nella schermata e dei comandi in più sulla pagina.

**Feature flag:** `webEnabled` in `AppSettings`, spento di default, persistito nelle
`SharedPreferences`. **Questa feature non ne introduce uno nuovo**, e vale la pena dire perché non
serve un secondo interruttore «consenti modifiche»: il potere è stato messo nell'indirizzo (D-03), e
un indirizzo che non si è mai copiato è già un permesso non concesso.

**Un effetto di D-08 sul rilascio, che vale come gradualità in più.** Ad app chiusa la porta si
comporta **esattamente come nella 003**: serve la pagina, serve i dati, rifiuta le scritture. Il
comportamento nuovo esiste solo mentre l'utente ha l'app davanti — cioè mentre è nella posizione di
accorgersi se qualcosa va storto.

**Piano di rollback:**

1. **Spegnere l'interruttore** dall'app. La porta si chiude, entrambi i token si invalidano.
   Immediato e sufficiente nella quasi totalità dei casi.
2. Se il difetto fosse nel percorso di scrittura: **reinstallare l'APK precedente**. Torna a servire
   la sola lettura. **Non serve toccare il database:** lo schema resta alla v5 e non c'è nessuna
   migrazione da annullare — è la conseguenza pratica del fatto che questa feature non ne ha una.
3. I due file DER restano sul disco e la versione precedente li usa: **il certificato è lo stesso**,
   quindi l'eccezione già accettata dal browser continua a valere e l'avviso non ritorna.
4. T-11 (`WebStatus`) e T-15 (asset) sono commit isolati e si revocano da soli.

**Ciò che il rollback non può annullare** — e va saputo prima, non scoperto dopo:

- **I promemoria creati o modificati dal browser restano.** Sono righe normali, con `updatedAt`
  aggiornato. Non è un residuo da ripulire: è il lavoro che l'utente ha fatto, e cancellarlo sarebbe
  il guasto, non la cura.
- **Le modifiche già replicate ai peer restano sui peer.** La sincronizzazione ha fatto il suo
  mestiere; un rollback su un dispositivo non le richiama indietro dall'altro.
- **Un'eccezione di certificato già accettata in un browser** resta legata a quel certificato e a
  quell'origine, come già per la 003.

---

## 10. Checklist di approvazione

Progetto personale a sviluppatore unico: i ruoli coincidono nella stessa persona. La tabella resta
perché i cappelli vanno indossati comunque, e separarli aiuta a non saltarne uno.

| Revisione | Responsabile | Stato | Data |
|---|---|---|---|
| Decisioni D-01, D-02, D-03 chiuse | Alberto Goldoni | ✅ Fatto | 2026-08-23 |
| Decisioni D-04a, D-04b, D-05, D-06 chiuse dall'analisi | Alberto Goldoni | ✅ Fatto | 2026-08-23 |
| **D-08 (scrittura solo ad app aperta) e D-09 (comandi spenti con la ragione)** | Alberto Goldoni | ✅ Fatto | 2026-08-23 |
| D-04c/d/e (limiti numerici), D-07 (nessun segnale sul telefono) | Alberto Goldoni | ✅ Fatto | 2026-08-23 |
| Revisione tecnica (architettura, breaking changes) | Alberto Goldoni | ⏳ In attesa | — |
| Revisione prodotto (**la cancellazione resta fuori; la scrittura richiede l'app aperta**) | Alberto Goldoni | ⏳ In attesa | — |
| Stima approvata (**7,60 gg**: +0,90 dall'analisi, +0,45 da D-08) | Alberto Goldoni | ⏳ In attesa | — |
| Rischi accettati (in particolare R-05, R-01, R-02) | Alberto Goldoni | ⏳ In attesa | — |
| Data di inizio confermata | Alberto Goldoni | ⏳ In attesa | — |

---

## Domande aperte

Devono ricevere risposta prima che il documento sia approvato — o, dove indicato, prima del task che
le usa.

1. ✅ **Chiusa (D-04c/d/e): 200 / 2000 / 1970–2100**, come proposto. Sono gli unici numeri di
   questa feature che nessuna riga di codice imponeva, e vanno scritti come costanti nominate in
   `ScrittureWeb`, non sparsi fra i controlli.
2. ✅ **Chiusa (D-07): no.** Il telefono non dà nessun segno — nessuna notifica, nessun indicatore.
   La lista si aggiorna da sé, e questo non costa niente perché `getActiveSortedAsc()` è già un
   `Flow` di Room. Se un giorno l'indirizzo di lettura verrà condiviso davvero, la domanda tornerà
   con un'altra faccia («chi ha cambiato questo?»): resta annotata fra le idee rimandate.
3. ✅ **Chiusa (D-08): la scrittura è possibile solo ad app aperta.** La domanda era «cosa facciamo
   se scrivere ad app chiusa non funziona»; la risposta è che non ci si prova. **V-D sparisce dalle
   verifiche bloccanti** e con essa l'unica incognita che non aveva un piano B.

   *Il prezzo, dichiarato:* la feature nasce per togliere il gesto «prendere in mano il telefono», e
   questo vincolo lo rimette in parte. US-002 — scrivere una descrizione lunga con la tastiera vera —
   conserva quasi tutto il suo valore; US-001, la correzione al volo, ne perde. In cambio la
   posizione di sicurezza diventa coerente: **il telecomando funziona solo mentre si è alla
   consolle**, e ad app chiusa la porta resta quella, già provata, della 003.
4. **Il testo della riga a scomparsa** («Serve anche modificare dal browser?») è l'unica parte non
   tecnica della feature ed è ciò che decide se D-03 protegge davvero o solo sulla carta. Va scritto
   guardando la schermata, in T-14, non deciso qui.

---

## Riferimenti

- [phase-1-requirements.md](phase-1-requirements.md) — obiettivo, scope, user stories, stima iniziale
- [phase-2-analysis.md](phase-2-analysis.md) — analisi su codice reale, file e righe, pattern, verifiche bloccanti
- [003-https-web-app](../003-https-web-app/status.md) — il trasporto cifrato che rende possibile questa feature
- [002-web-app-android](../002-web-app-android/status.md) — la porta, il token, gli asset, il custode del processo

---

*Documento generato con la skill `claude-code-feature`.*


---

## 11. Revoca di D-08 e D-09 (23/08/2026)

**Il vincolo «si scrive solo ad app aperta» è stato tolto.** Con esso sono spariti `503`,
`scritturaDisponibile`, lo stato spento della pagina, `WebServerController.pause()`,
`WebStatus.appDavanti` e l'`onStop` di `MainActivity`.

### Perché

Due ragioni, e la prima è un fatto misurato.

**V-D è passata.** La verifica che D-08 aveva fatto decadere — «una scrittura al DAO con l'app
chiusa e il processo tenuto vivo da `WebServerService`» — è stata eseguita sull'emulatore
disattivando temporaneamente il cancello, e ha risposto **sì** in entrambi gli stati:

| Stato | Scrittura | Sveglia |
|---|---|---|
| App in secondo piano (Home premuto, Activity viva) | `201` | programmata |
| App **tolta dai recenti** — `MainActivity` distrutta, vive solo il servizio | `201` | programmata all'ora giusta |

**La distinzione che era stata persa.** D-08 nasceva dalla domanda 3, che parlava di app *chiusa*;
l'implementazione l'aveva resa su Activity *visibile*, che è più stretto. Gli stati sono tre — in
primo piano, in secondo piano, chiusa — e il secondo veniva rifiutato senza che nessuno l'avesse
chiesto. Con V-D passata, mettere il confine fra il secondo e il terzo avrebbe voluto dire rifiutare
qualcosa che funziona: un `503` che dice una bugia.

Restava quindi una scelta binaria, e il cancello non era più un limite tecnico ma una posizione di
sicurezza — modesta, perché non ferma chi ha il token di scrittura e sceglie il momento in cui il
telefono è in uso. Il suo valore vero era togliere un'incognita, e l'incognita non c'era.

### Che cosa cambia nei rischi

- **R-02** resta, e sale di un gradino: l'indirizzo con le modifiche funziona a qualunque ora, non
  solo mentre il proprietario è davanti al telefono. Rischio **accettato**, dichiarato qui.
- **R-17** (la rotazione dello schermo abbassa la bandiera) **sparisce**: non c'è più una bandiera.
- **V-D** esce dalle verifiche aperte: eseguita e passata.

### Una cosa emersa per strada, che non riguarda questa feature

Al primo giro di prova, con `POST_NOTIFICATIONS` negato, **`WebServerService` non risultava avviato
affatto** — né servizio né notifica in `dumpsys` — mentre la card dell'app non segnalava nulla,
perché la porta funzionava lo stesso grazie all'Activity viva. Se si conferma, vuol dire che negando
le notifiche la promessa «resta aperta anche ad app chiusa» decade **in silenzio**. Viene dalla
feature 002 e va guardato a parte.
