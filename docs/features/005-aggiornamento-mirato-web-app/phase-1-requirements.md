# Feature: La pagina si aggiorna subito, e solo dove serve — Requisiti (Fase 1)

**Slug:** `aggiornamento-mirato-web-app`
**Data:** 2026-08-23
**Stato:** Bozza in attesa di approvazione
**Progetto:** Promemoria (`it.agoldoni.reminder`) — Compose Multiplatform, Android + Desktop JVM, Kotlin 2.3.21 / Room KMP / MVVM
**Team:** 1 sviluppatore (Alberto Goldoni)
**Piattaforma interessata:** solo Android, come la 002, la 003 e la 004 (il codice resta in `jvmSharedMain` e provabile su desktop)
**Estende:** [002-web-app-android](../002-web-app-android/) (la porta), [003-https-web-app](../003-https-web-app/) (il filo cifrato), [004-scrittura-web-app](../004-scrittura-web-app/) (la scrittura)

**Decisioni già prese dall'utente (input di questa fase):**

| Decisione | Scelta | Conseguenza principale |
|---|---|---|
| Immediatezza | **Il cambiamento è spinto verso la pagina** | Una modifica fatta nell'app compare nel browser senza aspettare il giro dei 30 s |
| Granularità | **Si aggiorna solo la scheda cambiata** | `disegna()` smette di azzerare `#elenco` e ricostruirlo |
| Ambito | **Entrambe** | Le due metà sono una feature sola, e la sezione 1 spiega perché non si possono separare |

---

## 1. Obiettivo e motivazione

Oggi la pagina web fa una cosa sola, e la fa in un modo che si vede: ogni 30 secondi chiede al
telefono se qualcosa è cambiato e, **se è cambiato, butta via l'elenco e lo ricostruisce da capo**.

```js
elenco.textContent = '';
for (var i = 0; i < eventi.length; i++) elenco.appendChild(riga(eventi[i]));
```

Sono due difetti diversi che sembrano uno solo.

**Il primo è il ritardo.** Sposto un appuntamento sul telefono, guardo lo schermo grande, e la
pagina resta com'era. Per quanto? Non lo so: dipende da dove si trovava il timer quando ho salvato.
Nel caso peggiore mezzo minuto — che è poco per un orologio e tantissimo per qualcuno che sta
verificando se ha salvato davvero. L'effetto non è «l'aggiornamento arriva tardi», è **il dubbio**:
si riprende in mano il telefono per controllare, che è esattamente il gesto che le feature 002-004
esistevano per togliere.

**Il secondo è il ridisegno totale.** Quando l'aggiornamento arriva, non arriva per il promemoria
che ho toccato: arriva per tutti. Quaranta schede rifatte per una modifica. Le conseguenze non sono
teoriche — sfarfallio, posizione di scorrimento persa proprio mentre si guarda il fondo di un elenco
lungo, selezione del testo che sparisce, e l'impossibilità di aggiungere qualunque cosa duri nel
tempo (un'evidenziazione della riga appena cambiata, una transizione) perché il nodo su cui
poggerebbe non sopravvive al giro dopo. C'è anche il caso senza rete: il timer dei colori chiama
`disegna()` **ogni 60 secondi**, quindi l'elenco intero si ricostruisce anche quando non è cambiato
niente, solo per cambiare una classe CSS.

### Perché le due metà non si separano

È il punto che rende questa una feature sola e non due.

Il ritmo lento di oggi sta **nascondendo** il ridisegno totale. Un elenco che si ricostruisce due
volte al minuto, e solo quando qualcosa è cambiato davvero, è un difetto che si nota a fatica.
Renderlo immediato senza renderlo mirato lo trasformerebbe in un difetto evidente: ogni tocco su
qualsiasi promemoria — dall'app, da un altro browser, da una sincronizzazione con il desktop —
farebbe lampeggiare l'intera pagina, *adesso*, mentre la si guarda.

La direzione opposta è altrettanto sbilanciata: un ridisegno chirurgico che aggiorna la scheda
giusta con trenta secondi di ritardo è precisione sprecata su un dato vecchio.

**Fare la prima senza la seconda peggiora la pagina.** È questa la ragione per cui la scelta
dell'utente («entrambe») non è un ampliamento dello scope ma la sua forma minima corretta.

### Perché ha senso adesso

Finché la pagina era in sola lettura (002 e 003), era una vetrina: la si apriva, si guardava, si
chiudeva. Con la 004 è diventata un posto in cui si **lavora**, e da allora vale una cosa che prima
non valeva: la stessa lista è viva in due posti insieme, il telefono e il browser, e ci si scrive da
tutti e due. Due viste dello stesso dato che divergono per mezzo minuto non erano un problema
quando una delle due era di sola lettura. Adesso lo sono.

### Il vincolo che decide la forma

`HttpServer` non fa keep-alive, ed è una scelta scritta e motivata: *«una richiesta per connessione,
poi si chiude … fa risparmiare tutta la gestione dello stato di una connessione riutilizzata, che è
dove si annidano i problemi»*. Sembra una condanna per qualunque forma di push. Non lo è, e la
ragione è due righe più sotto nello stesso file:

```kotlin
private val gestisci: suspend (HttpRequest, String) -> HttpResponse
```

Il gestore è **già sospendibile**, e ogni connessione ha già una coroutine sua
(`launch(Dispatchers.IO) { servi(client) }`). Una richiesta che invece di rispondere subito
*aspetta* fino a quando c'è qualcosa da dire non richiede nessuna nuova macchina: è il modello che
c'è già, con una sospensione in mezzo. È il **long-poll**, e in questa architettura costa quasi zero.

SSE, l'alternativa ovvia, costerebbe molto di più: pretende una risposta scritta a rate su una
connessione che resta aperta, cioè riaprire proprio la questione dello stato di connessione che la
002 aveva chiuso — e `HttpResponse`/`scriviRisposta` oggi costruiscono la risposta tutta in memoria
e poi la scrivono. WebSocket ancora peggio: un secondo protocollo su una porta che ne parla uno.

C'è un secondo pezzo già pronto, ed è quello che dice *quando* smettere di aspettare: `EventDao`
espone già `getActiveSortedAsc(): Flow<List<EventEntity>>`. L'invalidazione di Room è **per
tabella**, quindi quel flusso emette per ogni scrittura su `events` — da `EventEditViewModel`, dallo
snooze della notifica, da `SyncEngine`, da `ScrittureWeb` — senza che nessuno debba ricordarsi di
annunciarlo. È lo stesso argomento con cui la 004 ha concentrato database e sveglie in
`ScrittureWeb`: *un secondo posto da cui notificare sarebbe un secondo posto in cui dimenticarsene*,
e quel difetto è invisibile — la pagina semplicemente non si aggiorna, e sembra che sia lenta.

---

## 2. Scope

### Incluso

**Lato telefono**

1. Un modo per chiedere `/api/eventi` **in attesa**: la richiesta resta appesa finché il contenuto
   non cambia rispetto all'`ETag` dichiarato, oppure finché non scade un timeout dichiarato dal
   server (`304`, come oggi).
2. La sorgente del segnale di cambiamento, unica, derivata dall'invalidazione di Room — non un bus
   di notifiche chiamato a mano dai punti di scrittura.
3. Un tetto al numero di richieste in attesa contemporanee, perché una connessione appesa è una
   coroutine e un socket tenuti in vita.
4. Compatibilità all'indietro: una pagina vecchia che interroga `/api/eventi` come fa oggi deve
   continuare a funzionare senza modifiche.

**Lato browser**

5. Ridisegno **incrementale** dell'elenco: dato il nuovo payload, si toccano solo le schede
   comparse, sparite, cambiate o spostate; le altre restano gli stessi nodi del DOM.
6. Aggiornamento della fascia cromatica **senza ricostruire niente**: cambia la classe della scheda
   e basta, così il timer dei 60 s smette di essere un ridisegno totale periodico.
7. Anche le risposte alle scritture (`200`, `201`, `409`) passano dal ridisegno incrementale, non
   da un percorso separato.
8. Ritmo nuovo: attesa lunga quando la scheda è visibile, niente quando è nascosta
   (`visibilitychange`, come oggi), rientro con backoff quando il telefono non risponde.
9. Un polling lento di sicurezza resta come rete: se l'attesa lunga cade in un modo che il client
   non si accorge, la pagina si riallinea comunque.

### Escluso (out of scope)

| Fuori | Perché |
|---|---|
| SSE e WebSocket | Vedi §1: costano il modello di connessione che la 002 ha deliberatamente evitato, e il long-poll dà lo stesso risultato dentro l'architettura che c'è |
| Delta sul filo (mandare *solo* l'evento cambiato) | Romperebbe l'invariante di `corpoEventi`, che è **l'unico posto che costruisce la rappresentazione**, e con essa il calcolo dell'`ETag`. «Solo il promemoria modificato» si ottiene nel DOM, dove il difetto vive: il costo del payload intero è di pochi kilobyte su rete locale |
| Notifiche a browser chiuso, service worker, push del sistema operativo | È un'altra feature, e la 002 ha già stabilito che questa non è una PWA installabile |
| Aggiornamento mirato nell'app Compose | L'app usa `Flow` + `LazyColumn`, che fanno già la cosa giusta: il difetto è solo del browser |
| La lista dei completati nel browser | Non esiste oggi e non nasce qui |
| Desktop | La web app è cablata solo da Android |
| Animazioni ed evidenziazione della riga cambiata | Diventano *possibili* grazie a questa feature, ma non sono questa feature. Da valutare dopo. **→ Rivisto il 2026-08-23: l'evidenziazione è stata inclusa (D-09 della Fase 3); le animazioni di riordino restano fuori.** |
| Cancellazione dal browser | Resta esclusa come nella 004 |

---

## 3. User Stories

**US-1 — Il cambiamento fatto sul telefono compare subito**
> Come utente con il browser aperto sul computer e il telefono in mano, voglio che una modifica
> salvata nell'app compaia sulla pagina in circa un secondo, per non dover riprendere in mano il
> telefono a controllare se ho salvato davvero.

**US-2 — L'aggiornamento non mi sposta da dove sto guardando**
> Come utente che sta leggendo in fondo a un elenco lungo, voglio che l'arrivo di una modifica su un
> altro promemoria non riporti la pagina in cima e non faccia lampeggiare tutto, per poter continuare
> a leggere quello che stavo leggendo.

**US-3 — Solo la scheda toccata cambia**
> Come utente che segna «Fatto» dal browser, voglio vedere sparire quella riga e nient'altro, per
> capire a colpo d'occhio che cos'è successo e a che cosa.

**US-4 — La pagina lasciata aperta resta viva**
> Come utente che tiene la pagina aperta tutto il giorno su una scheda del browser, voglio che si
> ricolleghi da sola dopo una caduta del Wi-Fi o dopo che il telefono si è addormentato, senza che
> io debba ricaricare per accorgermene.

**US-5 — Il colore si aggiorna senza rifare la pagina**
> Come utente che guarda la pagina alle 18:00 in punto, voglio che il promemoria delle 18:00 diventi
> «scaduto» da solo, senza che l'intero elenco venga ricostruito per cambiare un colore.

**US-6 — Un solo punto da cui nasce il segnale**
> Come sviluppatore, voglio che «qualcosa è cambiato» nasca dall'invalidazione del database e non da
> chiamate sparse nei punti di scrittura, per non poterlo dimenticare in un ramo nuovo — un guasto
> che non si vede, perché la pagina sembra solo lenta.

---

## 4. Criteri di accettazione

### US-1 — Push
- [ ] Con la pagina aperta e visibile, una modifica salvata nell'app appare nel browser in **meno di
      2 secondi** su rete locale (misurato, non stimato).
- [ ] Vale per ogni sorgente di scrittura: modifica dall'app, completamento dall'app, snooze da una
      notifica, arrivo di una sincronizzazione dal desktop, scrittura da un altro browser.
- [ ] Se nulla cambia, la richiesta in attesa scade e risponde `304` senza ridisegnare.
- [ ] Il timeout dell'attesa è **minore** di quello di lettura del socket e degli intermediari
      tipici, e il client sa ripartire subito dopo.
- [ ] Un client della versione precedente (polling secco su `/api/eventi`) continua a funzionare
      contro il server nuovo.

### US-2 — Nessuno spostamento
- [ ] Con l'elenco scorso a metà e una modifica in arrivo su una scheda **fuori** dallo schermo, la
      posizione di scorrimento non cambia di un pixel.
- [ ] Nessun nodo `article.evento` non coinvolto viene rimosso e ricreato (verificabile con un
      `MutationObserver` nel test o marcando i nodi).
- [ ] Una selezione di testo su una scheda non toccata sopravvive all'aggiornamento.

### US-3 — Mirato
- [ ] Cambiare il titolo di un promemoria aggiorna **quel** nodo, senza ricrearlo.
- [ ] Cambiare la data lo sposta nella posizione giusta dell'ordinamento, senza ricostruire le altre.
- [ ] Completare un promemoria rimuove **solo** la sua scheda.
- [ ] Creare un promemoria inserisce **solo** la sua scheda, nella posizione giusta.
- [ ] Il `409` applica il valore vero con lo stesso percorso incrementale.
- [ ] Con il modulo di modifica aperto, il comportamento è quello deciso in §5-R6 ed è verificato.

### US-4 — Resilienza
- [ ] Spegnendo il Wi-Fi e riaccendendolo, la pagina torna ad aggiornarsi da sola senza ricarica.
- [ ] Il rientro dopo un errore usa un backoff, non un ciclo stretto: nessuna raffica di richieste
      contro un telefono che non risponde.
- [ ] Nascondendo la scheda del browser, nessuna richiesta resta appesa; riportandola in primo piano,
      l'aggiornamento è immediato.
- [ ] Con la pagina aperta e ferma per **un'ora**, l'elenco è ancora allineato al telefono.
- [ ] N schede aperte insieme (N ≥ 3) non fanno superare il tetto delle attese, e superarlo degrada
      al polling invece di rompersi.

### US-5 — Colori
- [ ] Al passaggio dell'ora la fascia cromatica cambia senza che nessun nodo venga ricreato.
- [ ] Il ricalcolo non fa traffico di rete.

### US-6 — Segnale unico
- [ ] Nessun punto di scrittura contiene una chiamata «avvisa i browser»: il segnale nasce
      dall'invalidazione della tabella `events`.
- [ ] Un test lo presidia scrivendo sul DAO da un punto che questa feature non ha toccato e
      verificando che l'attesa si sveglia.

---

## 5. Rischi e dipendenze

| # | Rischio | Impatto | Mitigazione proposta |
|---|---|---|---|
| **R1** | **Connessioni appese su Android.** Ogni attesa è una coroutine e un socket TLS vivi. Più schede aperte, più risorse trattenute | Medio | Tetto esplicito sulle attese contemporanee; oltre il tetto si risponde subito e il client ripiega sul polling. Da dimensionare in Fase 2 |
| **R2** | **Doze e processo in background.** La porta resta aperta grazie a `WebServerService`, ma una connessione *appesa* per decine di secondi con lo schermo del telefono spento è un caso che nessuno ha ancora provato | **Alto** | Va verificato sul dispositivo, non ragionato. Se cade, il client se ne accorge e riparte: il degrado deve essere «torna lenta», mai «smette» |
| **R3** | **Costo TLS.** Niente keep-alive: ogni attesa che scade costa un handshake. Oggi è un handshake ogni 30 s | Basso | Con timeout di attesa ~25-30 s il conto è **lo stesso di oggi**, e la cache di sessione dell'`SSLContext` — costruito una volta sola, 003 — lo tiene sui ~15 ms. Da misurare, non da assumere |
| **R4** | **Riordino.** `dateTimeMillis` decide la posizione: modificare una data sposta la scheda. Un algoritmo incrementale che gestisce male lo spostamento produce duplicati o buchi | Medio | Chiave stabile per riga (`id`, già nel payload dalla 002 e già usato dalle scritture della 004) e riconciliazione per chiave, non per indice. Test dedicati sul riordino |
| **R5** | **Il payload non porta `uuid`**, e per scelta motivata non deve uscire | Basso | `id` è la chiave primaria e il browser parla con un solo dispositivo: è sufficiente. Nessuna modifica al formato, quindi **nessun bump di `WEB_PAYLOAD_VERSION`** se il corpo resta identico |
| **R6** | **Interazione con il modulo aperto.** Oggi `disegna()` si sospende del tutto mentre `<dialog>` è aperto. Con l'aggiornamento mirato si *potrebbe* aggiornare il resto — ma la scheda in modifica non deve muoversi sotto il dialogo | Medio | **Decisione da prendere in Fase 2.** L'ipotesi prudente è tenere la sospensione com'è: il guadagno è piccolo e il rischio è quello che la 004 aveva già isolato |
| **R7** | **La barra dell'annullamento** (`daAnnullare`) fa riferimento a un evento che l'aggiornamento può togliere dall'elenco | Basso | Il riferimento è già a una copia dei dati, non al nodo. Da confermare leggendo `completa()`/`annullaCompletamento()` |
| **R8** | **Due token, due impronte.** `permessi` sta dentro il corpo, quindi lettura e scrittura hanno `ETag` diversi | Basso | Il segnale di risveglio è globale, il confronto dell'impronta resta per client: si comporta già bene. Da presidiare con un test |
| **R9** | **`app.js` cresce.** Oggi 461 righe con una struttura dichiarata in testa («la pagina fa tre cose separate»). La riconciliazione è la parte più intricata del file | Medio | Isolarla in una funzione pura testabile e aggiornare il commento d'intestazione: se la struttura cambia, la sua descrizione va cambiata con essa |
| **R10** | **Testare il ridisegno incrementale.** Non c'è oggi nessun test del JavaScript in questo progetto | Medio | Le opzioni (test della sola funzione pura di riconciliazione lato JVM, oppure prova manuale guidata) vanno pesate in Fase 2; scegliere «nessun test» è ammesso solo se dichiarato |

**Dipendenze**

- Nessuna dipendenza nuova. Nessuna libreria in più — coerente con la linea delle 002-004 (ODS a
  mano, HKDF a mano, DER a mano).
- Room ≥ quella già in uso, per `Flow` sul DAO: c'è già.
- Verifica finale sul **dispositivo reale**, non solo sull'emulatore: R2 non si chiude altrimenti.

---

## 6. Stima effort

| Area | Giorni/uomo | Che cosa comprende |
|---|---|---|
| Backend (Kotlin, `jvmSharedMain`) | **1,5** | Sorgente del segnale da Room; attesa nel `Router` con timeout e tetto; parsing del parametro; compatibilità all'indietro |
| Frontend (`app.js`, `app.css`) | **2,0** | Riconciliazione per chiave con riordino; fascia cromatica senza ricostruzione; ritmo nuovo, backoff, `visibilitychange`; percorso unico per letture e scritture |
| Test | **1,5** | Attesa che si sveglia / che scade / tetto superato; client vecchio contro server nuovo; funzione di riconciliazione; presidio del segnale unico |
| Verifica sul dispositivo | **0,5** | R2 (schermo spento, Doze), R3 (misura del costo TLS), US-1 (misura del ritardo) |
| Documentazione | **0,5** | `CLAUDE.md` (il paragrafo `web/` va esteso: il ritmo non è più «ogni 30 s») + `status.md` della feature |
| **Totale** | **≈ 6,0** | |

Margine consigliato: **+1 giorno** su R2. Se le attese lunghe non reggono a schermo spento, il
comportamento va ridisegnato — non il codice, ma la promessa che la pagina fa all'utente.

---

## 7. Milestones

| # | Milestone | Esito verificabile | Blocca |
|---|---|---|---|
| **M0** | **Prova d'attrito su R2** — una richiesta appesa 30 s con schermo spento e app chiusa, contro `WebServerService` | Si sa se il push regge o se serve un ripiego, **prima** di scrivere il resto | tutto |
| **M1** | Sorgente unica del segnale di cambiamento dall'invalidazione di Room | Un test scrive sul DAO da un punto qualsiasi e il segnale arriva | M2 |
| **M2** | Attesa lunga su `/api/eventi`, con timeout e tetto | `curl` resta appeso, si sveglia su una scrittura, scade in `304`; il client vecchio funziona ancora | M4 |
| **M3** | Riconciliazione incrementale nel browser, a ritmo invariato (30 s) | Cambiare un titolo tocca un nodo solo; scorrimento fermo. **Verificabile da solo**, senza M2 | M4 |
| **M4** | Ritmo nuovo nel client: attesa lunga, backoff, visibilità, polling di sicurezza | US-1 misurata sotto i 2 s; la pagina sopravvive a un'ora e a una caduta di rete | M5 |
| **M5** | Colori senza ricostruzione + percorso unico per le risposte alle scritture | US-3 e US-5 verdi | M6 |
| **M6** | Verifica sul dispositivo, misure, documentazione | R2 e R3 chiusi con numeri; `CLAUDE.md` aggiornato | — |

**M3 prima di M2 se serve tagliare.** Le due metà sono una feature sola (§1), ma se il tempo
stringe l'ordine giusto di consegna è **M3 da solo**: migliora la pagina anche a ritmo invariato ed
è quello che rende sicura M4. Il contrario — il push senza il ridisegno mirato — peggiora la pagina,
e non va consegnato da solo.

---

## Domande aperte per la Fase 2

1. **R2 è davvero il rischio principale?** M0 esiste per rispondere prima di costruirci sopra.
2. **Un endpoint nuovo o un parametro su quello che c'è?** (`/api/eventi?attendi=1` contro
   `/api/eventi/attesa`). Tocca `Router`, che smista prima per metodo e poi per percorso.
3. **Tetto delle attese: quante?** Va scelto un numero e scritto perché.
4. **R6: che cosa fa l'aggiornamento mirato mentre il modulo è aperto?** Prudenza (resta sospeso) o
   aggiornamento del resto dell'elenco?
5. **R10: come si prova il JavaScript in questo progetto?** Oggi non si prova. Va deciso ora, non a
   codice scritto.
