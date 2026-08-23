# La pagina si aggiorna subito, e solo dove serve — Implementation Plan

**Stato:** Bozza — in attesa di approvazione
**Autore:** Alberto Goldoni
**Data:** 2026-08-23
**Versione:** 1.1 — *v1.0 → v1.1 il 2026-08-23: D-07 e D-08 confermate, D-09 accolta
(l'evidenziazione entra nello scope, +0,30 gg), D-06 declassata da bloccante a misura precoce.*
**Feature:** `005-aggiornamento-mirato-web-app`
**Fonti:** [phase-1-requirements.md](phase-1-requirements.md) · [phase-2-analysis.md](phase-2-analysis.md)

---

## 1. Executive Summary

La pagina web dei promemoria, quella che si apre dal browser sulla rete di casa, oggi chiede al
telefono ogni trenta secondi se qualcosa è cambiato e — quando qualcosa è cambiato — **ricostruisce
l'intero elenco da capo**. Il risultato sono due fastidi: una modifica fatta sul telefono può
metterci mezzo minuto a comparire, e quando compare fa lampeggiare tutta la pagina, riportando in
cima chi stava leggendo in fondo.

Questa feature cambia le due cose insieme: il telefono **avvisa** il browser appena qualcosa cambia,
e il browser aggiorna **solo la scheda interessata** — segnalandola con una breve evidenziazione,
perché un aggiornamento che tocca una riga sola è anche un aggiornamento che si può perdere di
vista. Non cambia né il formato dei dati, né il database, né la sicurezza: solo il momento in cui
l'aggiornamento arriva e la quantità di pagina che tocca.

**Stima: 6,20 giorni/uomo**, con un margine di +1 giorno legato a una misura sul dispositivo
(T-01) da fare presto. Nessuna dipendenza nuova, nessuna migrazione, nessun breaking change.

---

## 2. Obiettivo e motivazione

### Problema che risolve

Due difetti che sembrano uno solo, e che si tengono per mano:

1. **Il ritardo.** `INTERVALLO_RETE = 30000` in `app.js`. Chi salva sul telefono e guarda lo
   schermo grande non sa se la pagina è indietro o se il salvataggio non è andato: riprende in mano
   il telefono per controllare, cioè fa esattamente il gesto che le feature 002-004 esistevano per
   togliere.
2. **Il ridisegno totale.** `disegna()` esegue `elenco.textContent = ''` e ricostruisce tutte le
   schede. Quaranta nodi rifatti per una modifica, con quello che ne consegue: sfarfallio,
   posizione di scorrimento persa, selezione del testo cancellata, e l'impossibilità di aggiungere
   qualunque cosa duri nel tempo.

**Perché insieme.** Il ritmo lento sta *nascondendo* il ridisegno totale: un elenco che si
ricostruisce due volte al minuto è un difetto che si nota a fatica. Rendere immediato l'arrivo
senza rendere mirato il ridisegno lo trasformerebbe in un difetto evidente — ogni tocco su
qualunque promemoria, da qualunque sorgente, farebbe lampeggiare la pagina *adesso*, mentre la si
guarda. **Fare la prima metà senza la seconda peggiora la pagina.**

### Metriche di successo

Misurabili dopo il rilascio, non opinioni:

- [ ] **M-1 — Ritardo.** Mediana su 10 prove del tempo fra il salvataggio nell'app e il
      cambiamento visibile nel browser: **< 2 s**. Oggi: fra 0 e 30 s, mediana attesa ~15 s.
- [ ] **M-2 — Nodi toccati.** Nodi `article.evento` rimossi e ricreati per una modifica singola:
      **1**. Oggi: uno per ogni promemoria aperto. Misurabile con un `MutationObserver` nella
      console del browser.
- [ ] **M-3 — Posizione.** `window.scrollY` invariato quando arriva una modifica su una scheda
      fuori schermo. Oggi: torna a 0.
- [ ] **M-4 — Non-regressione.** I **35** test di `RouterTest` e i **15** di `WebServiceTest`
      passano **senza che quei file siano stati toccati** (vedi §5, default inerti).
- [ ] **M-5 — Costo di rete.** Attese scadute in un'ora con la pagina aperta e ferma: **~144**
      (una ogni 25 s) contro le ~120 richieste/ora di oggi. Stesso ordine di grandezza: la
      feature non deve costare batteria in più.

### Legame con gli obiettivi del prodotto

Chiude la serie 002-004. La 002 ha messo la lista in un browser, la 003 ha cifrato il filo, la 004
ha reso la pagina un posto in cui si **lavora**. Da quel momento la stessa lista è viva in due
posti insieme e ci si scrive da entrambi: due viste che divergono per mezzo minuto non erano un
problema finché una delle due era di sola lettura. Adesso lo sono.

---

## 3. Scope

### Incluso

**Lato telefono**

- Attesa lunga (long-poll) su `GET /api/eventi`, attivata dal parametro `attendi=1`.
- Sorgente unica del segnale di cambiamento, derivata dall'invalidazione della tabella `events`.
- Tetto alle attese contemporanee, con degrado a risposta immediata.
- Compatibilità all'indietro in entrambe le direzioni.

**Lato browser**

- Ridisegno **incrementale** per chiave: si toccano solo le schede comparse, sparite, cambiate o
  spostate.
- Dati legati al nodo invece che alla closure dei gestori (prerequisito del punto sopra, §5.5).
- Fascia cromatica aggiornata **senza** ricostruire nulla.
- Ritmo nuovo: ciclo auto-schedulato, `AbortController` su `visibilitychange`, backoff sugli
  errori, guardia contro una richiesta che non si risolve mai.
- Le risposte alle scritture (`200`, `201`, `409`) passano dallo stesso percorso incrementale.
- **Evidenziazione breve della scheda appena cambiata o appena comparsa** (D-09), con rispetto di
  `prefers-reduced-motion`.

### Escluso (out of scope)

| Fuori | Perché |
|---|---|
| SSE e WebSocket | Pretendono una risposta scritta a rate su una connessione che resta aperta, cioè riaprire la questione dello stato di connessione che la 002 ha chiuso di proposito. Il long-poll dà lo stesso risultato dentro l'architettura che c'è |
| Delta sul filo (mandare *solo* l'evento cambiato) | Romperebbe l'invariante di `corpoEventi`, unico posto che costruisce la rappresentazione, e con essa il calcolo dell'`ETag`. «Solo il promemoria modificato» si ottiene nel DOM, dove il difetto vive; il payload intero costa pochi kilobyte su rete locale |
| Notifiche a browser chiuso, service worker, push di sistema | Altra feature. La 002 ha già stabilito che questa non è una PWA installabile |
| Aggiornamento mirato nell'app Compose | `Flow` + `LazyColumn` fanno già la cosa giusta: il difetto è solo del browser |
| Animazioni oltre l'evidenziazione di D-09 (transizioni di posizione, riordino animato) | Il riordino sposta un nodo fra due punti dell'elenco: animarlo vuol dire misurare due posizioni e interpolarle mentre l'elenco cambia sotto. È un problema a sé, e non è quello che questa feature risolve |
| Test automatici del JavaScript | Decisione D-05, §3 «Decisioni». Non è una svista: è una scelta con un costo dichiarato |
| Desktop, cancellazione dal browser, lista dei completati nel browser | Invariati rispetto alla 004 |

### Decisioni

Le cinque domande aperte della Fase 1 sono state chiuse dall'analisi; le tre che restavano sono
state decise il 2026-08-23. **Nessuna decisione è aperta e nessuna blocca l'inizio dei lavori.**
Resta una misura sul dispositivo, D-06, che è un'altra cosa — §3.1 spiega perché non è un cancello.

| # | Decisione | Esito | Stato |
|---|---|---|---|
| **D-01** | Endpoint nuovo o parametro? | **Parametro `?attendi=1`.** La risorsa è la stessa, cambia solo la consegna; un percorso nuovo andrebbe aggiunto alla tabella `Allow` di `metodoSbagliato` (`Router.kt:174-181`) e ragionato contro `StaticAssets`; la compatibilità all'indietro viene gratis in entrambe le direzioni | ✅ Chiusa in Fase 2 |
| **D-02** | Chi decide la durata dell'attesa? | **Il server.** `attendi` è un interruttore, non una durata: un client che potesse chiedere dieci minuti inchioderebbe una coroutine e un socket per dieci minuti, e ne basterebbero otto per esaurire il tetto | ✅ Chiusa in Fase 2 |
| **D-03** | Che cosa fa il ridisegno mirato mentre il modulo di modifica è aperto? | **Resta sospeso.** Tecnicamente ora si potrebbe aggiornare il resto, ma la scheda in modifica è una riga dell'elenco *sotto* il dialogo: aggiornare le altre può spostarla (cambia l'ordinamento) e chi chiude si ritrova il contesto cambiato sotto. Il guadagno sarebbe vedere aggiornarsi schede coperte da un dialogo modale | ✅ Chiusa in Fase 2 |
| **D-04** | Come si prova il JavaScript? | **Non automaticamente.** Nel progetto non esiste alcun `package.json` (verificato su tutto l'albero) e nessuna infrastruttura JS. Aggiungere npm e jsdom a una codebase che scrive a mano ODS, HKDF e DER, per provare 150 righe, non è un affare | ✅ Chiusa in Fase 2 |
| **D-05** | Che cosa si fa al posto dei test JS? | Riconciliazione tenuta come **funzione pura** `(precedenti, nuovi) → operazioni`, separata dall'applicazione al DOM — non la prova nessuno oggi, ma resta provabile il giorno in cui il progetto avrà una ragione indipendente per avere un runner. Più la checklist manuale V-01…V-10 di §7 | ✅ Chiusa in Fase 2 |
| **D-06** | **Il long-poll regge a schermo spento?** | **Aperta, ma non bloccante.** Il codice non può rispondere — il comportamento di Doze verso una connessione *già aperta e ferma* si misura, non si deduce — ma la risposta **non cambia il piano**, per le tre ragioni di §3.1. La misura è T-01, presto e non prima | 📏 Misura precoce |
| **D-07** | Durata dell'attesa e tetto delle attese | **25 s e 8, confermati.** Il ragionamento sul tetto: gli utenti di questa porta sono le persone di una casa, ciascuna con al più due o tre schede; otto copre il caso reale con margine, e otto coroutine sospese più otto socket TLS sono trascurabili anche su un telefono. Alzarlo a trenta non regalerebbe niente a nessuno se non a chi apre trenta connessioni di proposito. **I due numeri vanno scritti accanto alla costante con questo perché**, o il prossimo li cambierà a caso | ✅ Confermata |
| **D-08** | Si accetta di **non** avere test automatici sul JavaScript (D-04/D-05)? | **Sì, accettato.** È il compromesso più visibile del piano, ed è stato accettato esplicitamente e non per inerzia. Conseguenza operativa: la checklist manuale di §7 **non è un di più, è il piano di test del frontend**, e va eseguita e annotata con l'esito — non con una spunta | ✅ Accettata |
| **D-09** | L'evidenziazione della scheda appena cambiata entra nello scope? | **Sì, dentro.** Questa feature la rende possibile per la prima volta: oggi il nodo non sopravvive a un giro, quindi non c'è niente su cui posare un'evidenziazione. Ed è più che un ornamento — chiude un difetto che la feature stessa introdurrebbe: un aggiornamento che tocca una riga sola in mezzo a quaranta è un aggiornamento che si può non vedere, mentre il ridisegno totale di oggi almeno lampeggiava. **+0,30 gg (T-12b)** | ✅ Confermata |

### 3.1 — Perché D-06 non blocca l'inizio dei lavori

Il documento in versione 1.0 lo dava per bloccante. È stato declassato, e le tre ragioni vanno
scritte perché il declassamento non sembri un'indulgenza.

**US-001 è per costruzione uno scenario a schermo acceso.** La storia di punta — «sposto un
appuntamento sul telefono e guardo lo schermo grande» — nasce dal fatto che l'utente ha *appena
toccato il telefono*: schermo, CPU e radio sono svegli, e la scrittura stessa è l'evento che li
sveglia. Ciò che T-01 misura è il caso in cui la modifica arriva **da altrove** — una
sincronizzazione col desktop, uno snooze da una notifica — mentre il telefono sta fermo in tasca.
È un caso reale ma secondario, e farci dipendere l'inizio dei lavori è mettere il cancello davanti
alla porta sbagliata.

**Il piano degrada da sé, qualunque sia la risposta.** La guardia R-15 e il backoff di T-13 fanno
sì che un'attesa che non torna venga abbandonata e rifatta — e un'attesa abbandonata e rifatta *è*
polling. Non esiste una decisione di progetto a valle della misura: il codice è lo stesso in
entrambi i casi.

**Non c'è nessuna promessa nell'interfaccia da ritirare.** La pagina non dichiara «in tempo reale»
da nessuna parte, e non deve iniziare a farlo. Quella che la versione 1.0 chiamava «la promessa»
vive in un paragrafo di `CLAUDE.md` e in `status.md`: **D-06 governa un paragrafo, non
un'architettura.**

#### La previsione, scritta prima della misura

Serve a poter essere smentita. Il long-poll è **strettamente meno** traffico e meno risvegli del
polling di oggi: una connessione ogni 25 s invece di una ogni 30, e nel mezzo silenzio in entrambi
i casi. Non introduce niente che non ci sia già, tranne il socket tenuto aperto, che non costa né
wake lock né batteria.

Il rischio vero non è la rete: è che `delay()` non scada a CPU sospesa. **Verificato: nel progetto
nessuno acquisisce un partial wake lock** — il permesso `WAKE_LOCK` è dichiarato per gli allarmi, e
l'unico `acquire()` del codice è il multicast lock di `NsdDiscovery`. Quindi il timeout slitterà. Ed
è innocuo, perché ciò che deve svegliare l'attesa — una scrittura — è di per sé un evento che sveglia
la CPU.

#### La sola risposta che cambierebbe il piano

Perché la prova non sia una formalità, va nominata la condizione di fallimento vera: **se tenere il
socket aperto impedisse al telefono di entrare in Doze** (cioè costasse batteria) **o facesse
uccidere il servizio.** Non me l'aspetto — un socket inattivo non tiene sveglio niente — ma è
l'unico esito che cambierebbe il piano invece di cambiare un paragrafo, quindi va cercato con
l'intenzione di trovarlo: T-01 include il controllo che il telefono entri in Doze regolarmente con
**otto** attese appese.

---

## 4. User Stories e criteri di accettazione

### US-001 · Il cambiamento fatto sul telefono compare subito
**Priorità:** Must Have

Come utente con il browser aperto sul computer e il telefono in mano, voglio che una modifica
salvata nell'app compaia sulla pagina in circa un secondo, per non dover riprendere in mano il
telefono a controllare se ho salvato davvero.

**Criteri di accettazione:**
- [ ] Con la pagina visibile, la modifica appare in **meno di 2 secondi** su rete locale (misurato).
- [ ] Vale per **ogni** sorgente: modifica dall'app, completamento dall'app, snooze da una notifica,
      arrivo di una sincronizzazione dal desktop, scrittura da un altro browser.
- [ ] Se nulla cambia, l'attesa scade e risponde `304` senza ridisegnare.
- [ ] Un client della versione precedente (polling secco) continua a funzionare contro il server
      nuovo.
- [ ] Un segnale che **non** cambia l'impronta (una scrittura su un evento già completato, un
      tombstone dalla sincronizzazione) non produce un `200`: l'attesa prosegue.

### US-002 · L'aggiornamento non mi sposta da dove sto guardando
**Priorità:** Must Have

Come utente che sta leggendo in fondo a un elenco lungo, voglio che l'arrivo di una modifica su un
altro promemoria non riporti la pagina in cima e non faccia lampeggiare tutto, per poter continuare
a leggere quello che stavo leggendo.

**Criteri di accettazione:**
- [ ] `window.scrollY` non cambia di un pixel quando cambia una scheda fuori schermo.
- [ ] Nessun nodo `article.evento` non coinvolto viene rimosso e ricreato.
- [ ] Una selezione di testo su una scheda non toccata sopravvive all'aggiornamento.

### US-003 · Solo la scheda toccata cambia
**Priorità:** Must Have

Come utente che segna «Fatto» dal browser, voglio vedere sparire quella riga e nient'altro, per
capire a colpo d'occhio che cos'è successo e a che cosa.

**Criteri di accettazione:**
- [ ] Cambiare un titolo aggiorna **quel** nodo, senza ricrearlo.
- [ ] Cambiare la data lo sposta nella posizione giusta dell'ordinamento, senza ricostruire le
      altre e **senza che ne compaiano due**.
- [ ] Completare rimuove solo la sua scheda; creare inserisce solo la sua, nella posizione giusta.
- [ ] Il `409` applica il valore vero passando dallo stesso percorso incrementale.
- [ ] **Dopo un aggiornamento in loco, i pulsanti della scheda usano i dati nuovi.** Modificare un
      promemoria dall'app e poi toccare «Fatto» sulla stessa scheda dal browser **non** deve
      produrre un conflitto. *(È R-11: vedi §5.5.)*

### US-004 · La pagina lasciata aperta resta viva
**Priorità:** Must Have

Come utente che tiene la pagina aperta tutto il giorno su una scheda del browser, voglio che si
ricolleghi da sola dopo una caduta del Wi-Fi o dopo che il telefono si è addormentato, senza che io
debba ricaricare per accorgermene.

**Criteri di accettazione:**
- [ ] Spegnendo e riaccendendo il Wi-Fi, la pagina torna ad aggiornarsi da sola.
- [ ] Il rientro dopo un errore usa un backoff: nessuna raffica contro un telefono che non risponde.
- [ ] Nascondendo la scheda, **nessuna attesa resta appesa sul telefono**; riportandola davanti,
      l'aggiornamento è immediato.
- [ ] Con la pagina aperta e ferma per un'ora, l'elenco è ancora allineato al telefono.
- [ ] N schede aperte insieme (N ≥ 3) non fanno superare il tetto; superarlo **degrada al polling**,
      non produce un errore.
- [ ] Una richiesta che non si risolve mai viene abbandonata e rifatta, invece di lasciare la pagina
      a mostrare dati vecchi come se fossero freschi.

### US-005 · Il colore si aggiorna senza rifare la pagina
**Priorità:** Should Have

Come utente che guarda la pagina alle 18:00 in punto, voglio che il promemoria delle 18:00 diventi
«scaduto» da solo, senza che l'intero elenco venga ricostruito per cambiare un colore.

**Criteri di accettazione:**
- [ ] Al passaggio dell'ora la fascia cambia senza che nessun nodo venga ricreato.
- [ ] Il ricalcolo non fa traffico di rete.

### US-006 · Un solo punto da cui nasce il segnale
**Priorità:** Must Have

Come sviluppatore, voglio che «qualcosa è cambiato» nasca dall'invalidazione del database e non da
chiamate sparse nei punti di scrittura, per non poterlo dimenticare in un ramo nuovo — un guasto
che non si vede, perché la pagina sembra solo lenta.

**Criteri di accettazione:**
- [ ] Nessun punto di scrittura contiene una chiamata «avvisa i browser».
- [ ] Un test scrive sul DAO **senza passare da `ScrittureWeb`** e l'attesa si sveglia lo stesso.

### US-007 · Vedo che cosa e' cambiato
**Priorità:** Should Have

Come utente con la pagina aperta davanti, voglio che la scheda appena cambiata si faccia notare per
un istante, per non dovermi chiedere che cosa sia successo in un elenco che non ha lampeggiato.

**Perché esiste, e perché non è un ornamento.** È il rovescio esatto di US-002: rendere
l'aggiornamento silenzioso risolve il fastidio del lampeggio e, nello stesso gesto, ne crea uno
nuovo — una riga su quaranta che cambia senza che nulla lo segnali si può semplicemente non vedere.
Il ridisegno totale di oggi, con tutti i suoi difetti, almeno *diceva* che era successo qualcosa.

**Priorità Should Have, e non è un ripensamento.** È il solo pezzo del piano che si può togliere
senza danneggiare nient'altro: le altre sei storie si tengono a vicenda, questa poggia su di loro e
non regge niente. Se il tempo stringe, è la prima a cadere — ed è per questo che sta in un task suo
(T-12b) e non spalmata dentro T-11.

**Criteri di accettazione:**
- [ ] Una scheda aggiornata in loco o appena inserita si evidenzia, e l'evidenziazione svanisce da
      sola entro un paio di secondi.
- [ ] **Al primo caricamento non si evidenzia niente.** Evidenziare tutto all'apertura sarebbe
      esattamente il lampeggio che US-002 esiste per togliere.
- [ ] L'evidenziazione **non sopravvive** al cambio di fascia cromatica né viceversa: le due cose
      scrivono sulla stessa scheda e non devono cancellarsi a vicenda (§5.8).
- [ ] Con `prefers-reduced-motion: reduce` non c'è transizione: il colore compare e sparisce.
- [ ] Il ridisegno sospeso a modulo aperto (D-03) non accumula evidenziazioni da mostrare tutte
      insieme alla chiusura.

---

## 5. Architettura tecnica

### 5.1 — Il flusso, prima e dopo

```
OGGI

  Browser                          Telefono
     │   ogni 30 s                    │
     ├─ GET /api/eventi ─────────────►│ corpoEventi() → etag
     │◄──────── 200 lista intera ─────┤
     │                                │
  disegna(): elenco.textContent = ''  │
  40 schede ricostruite per 1 modifica


DOPO

  Browser                          Telefono                    Room
     │                                │                          │
     ├─ GET /api/eventi?attendi=1 ───►│ versione = N             │
     │      If-None-Match: "abc"      │ corpoEventi() → "abc"    │
     │                                │ ⇣ sospende               │
     │            (nessun traffico)   │                          │
     │                                │◄── invalidazione events ─┤ ← app, snooze,
     │                                │    versione = N+1        │   SyncEngine,
     │                                │ ricompone → "def" ≠"abc" │   ScrittureWeb
     │◄──────── 200 lista intera ─────┤                          │
     │                                │                          │
  riconcilia(precedenti, nuovi)       │
  → 1 scheda aggiornata in loco
```

Il flusso di destra **esisteva già**: `Router.eventi()` (`Router.kt:77-86`) è da sempre il punto
unico che confronta `if-none-match` con `corpo.etag` e sceglie fra `200` e `304`. L'attesa è la
stessa decisione presa più tardi, non una decisione nuova.

### 5.2 — Componenti coinvolti

| Componente | Percorso | Modifica |
|---|---|---|
| `Cambiamenti` | `shared/src/jvmSharedMain/…/web/Cambiamenti.kt` | **Nuovo.** ~40 righe |
| `Router` | `shared/src/jvmSharedMain/…/web/Router.kt` | Ramo di attesa dentro `eventi()`; parametro in coda |
| `WebService` | `shared/src/jvmSharedMain/…/web/WebService.kt` | Parametro `cambiamenti: Flow<*>`; costruisce `Cambiamenti` |
| `ReminderApp` | `androidApp/src/main/…/ReminderApp.kt:66` | Passa il flusso di invalidazione. **Unico posto che ha in mano `database` e non solo `database.eventDao()`** |
| `app.js` | `shared/src/webAssets/web/app.js` | **Il grosso.** ~150 righe su 461 |
| `index.html` | `shared/src/webAssets/web/index.html` | Solo il commento che precede `<dialog>` |
| `app.css` | `shared/src/webAssets/web/app.css` | Solo l'evidenziazione di D-09: una classe, la sua transizione e il blocco `prefers-reduced-motion`. Le quattro fasce cromatiche non si toccano |

**Non toccati, e vale la pena dirlo:** `HttpMessages.kt`, `HttpServer.kt`, `ScrittureWeb.kt`,
`WebPayload.kt`, `AccessToken.kt`, `StaticAssets.kt`, `TlsIdentity.kt`, `CertificateStore.kt`,
`EventDao.kt`, `AppDatabase.kt`, il manifest, `WebServerService.kt`. Trasporto, crittografia,
formato, sicurezza e database restano esattamente come sono.

### 5.3 — Modifiche al data model

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| `events` | **Nessuna** | Nessuna colonna, nessun indice, nessuna migrazione. Lo schema resta alla v5 |
| `peers` | **Nessuna** | — |
| `EventDao` | **Nessuna** | Resta a 15 metodi. `getActiveSortedAsc()` esiste già ed è ciò che serve |
| `WebPayload` / `VoceWeb` | **Nessuna** | `WEB_PAYLOAD_VERSION` resta **2** (`WebPayload.kt:18`) |

La chiave della riconciliazione è `VoceWeb.id` (`WebPayload.kt:56`), che è nel payload **dalla
002**, messo lì con il commento «una scrittura futura nominerà l'evento». Serve una seconda volta,
per una ragione che allora non era prevista. `uuid` resta fuori, come deciso: il browser parla con
un solo dispositivo, quindi `id` è identità sufficiente.

### 5.4 — API

| Metodo | Path | Descrizione | Auth |
|---|---|---|---|
| GET | `/api/eventi?t=<token>` | **Invariata.** Risposta immediata: `200` o `304` | Sì |
| GET | `/api/eventi?t=<token>&attendi=1` | **Nuova modalità.** Resta appesa finché l'impronta non cambia o finché non scade l'attesa decisa dal server (D-02, proposta 25 s), poi `304` | Sì |

Codici, corpo e semantica dell'`ETag` **identici**. `AccessToken.verifica` non va toccata: una
richiesta in attesa è **una** richiesta e non consuma più tentativi di una normale.

**L'ordine delle prime due righe del ramo di attesa è il cuore della correttezza**, e va scritto con
il suo perché:

```kotlin
// Si legge il contatore PRIMA di comporre il corpo. Al contrario, una scrittura che cadesse
// fra la composizione e la sottoscrizione non sveglierebbe nessuno: il client resterebbe
// appeso fino alla scadenza con in mano un dato già vecchio. È il difetto che si manifesterebbe
// come «ogni tanto la pagina è in ritardo di venticinque secondi», cioè nel modo più difficile
// da riprodurre.
var visto = cambiamenti.versione
var corpo = rappresentazione(accesso)
```

Poi, in ciclo fino alla scadenza: impronta diversa → `200` subito (il client si era perso un giro);
impronta uguale e nessuna attesa → `304` come oggi; impronta uguale e attesa → si aspetta che il
contatore superi `visto`, **si ricompone il corpo e si ricontrolla**. Il ricontrollo non è
pignoleria: una scrittura su `events` che non tocca i promemoria aperti muove il contatore senza
cambiare l'impronta, e rispondere `200` su quel segnale manderebbe il browser a ridisegnare per
niente, in un ciclo stretto.

### 5.5 — La modifica più importante di `app.js`, e non è la riconciliazione

Oggi il legame fra una scheda e i suoi dati è la **closure** (`app.js:141-142`):

```js
comandi.appendChild(bottone('Fatto', false, function () { completa(evento); }));
comandi.appendChild(bottone('Modifica', true, function () { apriModulo(evento); }));
```

Funziona perché ogni scheda vive meno di un aggiornamento: al giro dopo il nodo non c'è più, e con
lui la closure. **Riusando il nodo, la closure sopravvive ai dati.** Un click manderebbe
l'`attesoUpdatedAt` che quella scheda aveva *quando è stata creata*, e l'utente vedrebbe «questo
promemoria è stato modificato sul telefono nel frattempo» dopo aver toccato un pulsante su una
scheda che sullo schermo mostra il valore giusto.

È **R-11**, ed è la ragione per cui il lavoro su `app.js` non è «un diff invece di un rebuild». Il
contratto nuovo: i dati vivono sul nodo (`card._evento`), i gestori li leggono al momento del
click. **Va fatto prima della riconciliazione** (T-09 prima di T-10), non insieme: è una modifica
piccola, verificabile da sola e senza effetti visibili, ed è il fondamento su cui tutto il resto
poggia.

### 5.6 — I default inerti, e perché non sono comodità

```kotlin
internal class Router(…, private val cambiamenti: Cambiamenti = Cambiamenti.fermo)
class WebService(…, private val cambiamenti: Flow<*> = emptyFlow(), …)
```

Sono ciò che tiene i **35** test di `RouterTest` e i **3 siti di costruzione** di `WebServiceTest`
(righe 70, 209, 292) compilabili senza toccarli. Un test che non cambia è un test che continua a
dire la stessa cosa di prima, e in una feature che riscrive il percorso di lettura è esattamente la
garanzia che serve — è la metrica M-4.

Il default inerte non nasconde un errore di cablaggio: senza segnale l'attesa **scade** e risponde
`304`, cioè la pagina torna al ritmo di oggi. Il presidio contro il cablaggio dimenticato è
TC-08, non il tipo.

### 5.7 — Breaking changes: nessuno

Non c'è una tabella di migrazione perché non c'è niente da migrare, e la ragione merita una riga:
il formato non cambia di un byte, quindi **un client vecchio non manda `attendi` e riceve la
risposta di sempre; un client nuovo contro un server vecchio manda un parametro che quel server
ignora, e ricade sul polling.** Le due direzioni degradano entrambe verso il comportamento di oggi.

Il caso concreto in cui la cosa serve davvero: una scheda del browser lasciata aperta durante
l'aggiornamento dell'APK continua a girare con il **vecchio** `app.js` contro il **nuovo** server.
Funziona, senza che nessuno debba ricaricare.

### 5.8 — L'evidenziazione, e le due trappole che porta con sé

Da dove viene l'informazione: `riconcilia(precedenti, nuovi)` restituisce **operazioni**, non una
lista. Quali schede si evidenziano è già scritto lì — `inserita` e `aggiornata` sì, `spostata` da
sola no (una scheda che si sposta perché *un'altra* ha cambiato data non è cambiata). È un
argomento in più per tenere quella funzione pura e a base di operazioni, come vuole D-05: se
restituisse solo lo stato finale, l'evidenziazione andrebbe ricavata una seconda volta, da un
secondo confronto, con la certezza che prima o poi i due divergerebbero.

**Trappola 1 — `className` cancella tutto.** Oggi la fascia si scrive così:

```js
card.className = 'evento ' + fascia(evento);
```

Assegnare `className` **sovrascrive l'intera lista di classi**, evidenziazione compresa. Il difetto
sarebbe intermittente e quasi impossibile da riprodurre a comando: l'evidenziazione sparisce solo
quando `aggiornaColori()` capita a passare nei due secondi in cui è accesa — cioè raramente, e mai
mentre la si sta cercando. La regola è che **da qui in avanti sulla scheda si scrive con
`classList`**, mai con `className`, e vale in entrambe le direzioni: anche l'evidenziazione non
deve poter cancellare la fascia.

**Trappola 2 — il primo caricamento.** La prima risposta è, tecnicamente, un elenco di schede tutte
«appena inserite». Evidenziarle sarebbe far lampeggiare l'intera pagina all'apertura: il difetto
che US-002 esiste per togliere, reintrodotto dalla porta di servizio. L'evidenziazione si accende
solo **dopo** la prima risposta — c'è già `primaRisposta` in `app.js`, ed è esattamente questa la
distinzione che quella variabile serve a fare.

Stessa logica alla chiusura del modulo (D-03): il ridisegno è rimasto sospeso e riparte con un
confronto contro dati vecchi di minuti. Quel giro **non** evidenzia, o restituirebbe in una volta
sola tutte le modifiche accumulate mentre si scriveva.

---

## 6. Piano di implementazione

| ID | Task | Area | Stima (gg) | Dipende da |
|---|---|---|---|---|
| **T-01** | **Misura (D-06), non un cancello.** App chiusa, schermo spento, `adb shell dumpsys deviceidle force-idle`. Tre domande invece di «regge?», che è troppo vaga per produrre un esito: **(a)** la connessione appesa sopravvive 30 s? *(previsione: sì)* · **(b)** all'arrivo di una scrittura, **in quanto tempo parte la risposta?** — è la misura che conta, e anche 2 s andrebbero benissimo *(previsione: sotto il secondo)* · **(c)** i 25 s di scadenza scadono o slittano? *(previsione: slittano, ed è innocuo)*. Più il controllo di §3.1: con **otto** attese appese il telefono entra in Doze regolarmente | Infra | 0,25 | — |
| **T-02** | **Verifica:** `InvalidationTracker.createFlow("events")` con `AndroidSQLiteDriver` sull'emulatore. Smoke test, non un test permanente | Infra | 0,20 | — |
| **T-03** | `Cambiamenti.kt`: contatore monotono alimentato da un `Flow`, `attendiOltre(visto)`, tetto delle attese con `finally` | Core | 0,35 | T-02 |
| **T-04** | `CambiamentiTest` | Test | 0,25 | T-03 |
| **T-05** | `Router.eventi()`: parametro `attendi`, ordine contatore→corpo, ciclo con ricontrollo dell'impronta, scadenza, tetto. Durata come parametro con valore predefinito, **non** costante letta dentro: altrimenti i test aspettano davvero 25 secondi | Core | 0,40 | T-03 |
| **T-06** | `AttesaLungaTest` — i 7 casi di §7 | Test | 0,45 | T-05 |
| **T-07** | `HttpServerTest`: **una connessione appesa non blocca le altre.** In teoria non può (`HttpServer.kt:65` lancia una coroutine per connessione); in pratica è il genere di garanzia che si scopre falsa il giorno in cui la si dà per buona | Test | 0,15 | T-05 |
| **T-08** | Cablaggio: `WebService` accetta il flusso, `ReminderApp` lo passa | Core | 0,20 | T-03 |
| **T-09** | **`app.js`: i dati sul nodo invece che nella closure (R-11).** Da solo, prima della riconciliazione: piccolo, verificabile, senza effetti visibili | UI | 0,35 | — |
| **T-10** | `app.js`: `riga()` spaccata in `creaScheda`/`aggiornaScheda`; **funzione pura** `riconcilia(precedenti, nuovi) → operazioni`, separata dall'applicazione al DOM (D-05) | UI | 0,70 | T-09 |
| **T-11** | `app.js`: applicazione al DOM — inserimento, rimozione, aggiornamento in loco, **riordino per `dateTimeMillis`** | UI | 0,60 | T-10 |
| **T-12** | `app.js`: `aggiornaColori()` senza ricostruzione; `setInterval(disegna, 60000)` diventa `setInterval(aggiornaColori, 60000)` | UI | 0,20 | T-11 |
| **T-12b** | `app.js` + `app.css`: **evidenziazione della scheda cambiata (D-09).** Guidata dalle operazioni di `riconcilia`, spenta alla prima risposta e al rientro da modulo aperto, scritta con `classList` e non con `className`, con il blocco `prefers-reduced-motion` | UI | 0,30 | T-12 |
| **T-13** | `app.js`: ritmo nuovo — ciclo auto-schedulato, `AbortController` su `visibilitychange` (R-14), backoff esponenziale con tetto, guardia sul `fetch` che non si risolve (R-15) | UI | 0,55 | T-05, T-11 |
| **T-14** | I commenti che oggi **dichiarano** il ridisegno totale: intestazione di `app.js` e il blocco che precede `<dialog>` in `index.html`. La premessa cambia, le due scelte restano ma per ragioni diverse: vanno riscritte, non cancellate | Doc | 0,15 | T-11 |
| **T-15** | Verifica manuale nel browser, checklist V-01…V-10, con `tools/sessione-x.sh browser <url>` | Test | 0,50 | T-13 |
| **T-16** | Misure sul dispositivo: M-1 (ritardo), M-2 (nodi), R-03 (costo di un handshake TLS ripreso da sessione) | Test | 0,25 | T-13 |
| **T-17** | `CLAUDE.md` paragrafo `web/` + `status.md` della feature | Doc | 0,35 | T-16 |

**Stima totale: 6,20 giorni/uomo**
**Breakdown:** Infra 0,45 · Core (BE) 0,95 · UI (FE) 2,70 · Test 1,60 · Doc 0,50

**Margine: +1,00 gg** legato a D-06 — e serve solo nell'esito che §3.1 chiama «la sola risposta che
cambierebbe il piano». Negli altri due esiti possibili (regge / regge male) il margine non si tocca:
si scrive il numero misurato in `status.md` e si va avanti.

### Scostamento dalla Fase 1

| Area | Fase 1 | Ora | Perché |
|---|---|---|---|
| Backend | 1,50 | **1,40** (Infra + Core) | L'architettura regge il long-poll senza modifiche strutturali: `gestisci` è già `suspend`, `Router.eventi()` è già il punto unico della decisione, e `createFlow` esiste già |
| Frontend | 2,00 | **2,70** | R-11 (il difetto della closure non era noto in Fase 1) e D-09 (l'evidenziazione è entrata nello scope dopo la Fase 2) |
| Test | 1,50 | 1,60 | — |
| Doc | 0,50 | 0,50 | — |

Il totale sale poco (6,20 contro ≈6,00) ma **il peso si è spostato dal telefono al browser**, che è
anche dove sta il rischio maggiore: il backend è sceso di 0,10 e il frontend è salito di 0,70.

### Ordine di consegna, se il tempo stringe

**T-09 → T-10 → T-11 → T-12 da soli**, cioè il ridisegno mirato a ritmo invariato di 30 s, sono una
consegna sensata: migliorano la pagina e rendono sicuro tutto il resto. **Il contrario no.** Il
push senza il ridisegno mirato peggiora la pagina (§2) e non va consegnato da solo.

**T-12b è il primo pezzo da lasciare indietro**, ed è l'unico che si può lasciare indietro senza
conseguenze: US-007 poggia sulle altre sei storie e non ne regge nessuna. Ma va lasciato indietro
**per intero**, non a metà: un'evidenziazione che ogni tanto sparisce (trappola 1 di §5.8) è peggio
di nessuna evidenziazione, perché insegna a non fidarsene.

### Da dove si comincia davvero

**Dal frontend, il primo giorno, in parallelo a T-01…T-08.** T-09/T-10/T-11 non dipendono dal server
— si provano a ritmo di oggi — e sono dove stanno i tre rischi più alti del piano: R-11 (la closure
che sopravvive ai dati), R-04 (il riordino) e R-17 (`className` che cancella l'evidenziazione).
Cominciare dal pezzo rischioso lascia margine; cominciare da una misura che non cambia il codice
(§3.1) lo consuma.

T-01 resta **presto**, perché costa 0,25 gg e perché il numero che produce va scritto nella
documentazione: presto non vuol dire prima.

---

## 7. Piano di test

**Strategia generale.** Il telefono si prova con test automatici sui socket veri, come le 002-004;
il browser si prova a mano, con una checklist scritta, e la scelta è dichiarata (D-04/D-05). I test
esistenti **non vanno toccati**: che continuino a passare senza modifiche è la metrica M-4.

`FakeEventDao` va bene per l'attesa perché `getActiveSortedAsc()` è un `MutableStateFlow.map`
(`FakeEventDao.kt:15`): emette a ogni scrittura, esattamente come farà l'`InvalidationTracker`.

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Unit | Il contatore **non** parte da un'emissione iniziale (`emitInitialState = false`) e sale a ogni emissione | Alta |
| TC-02 | Unit | `attendiOltre(visto)` ritorna **subito** se il contatore è già oltre — con un ordine deliberatamente sfavorevole. *È la corsa di R-12* | Alta |
| TC-03 | Unit | Più attese contemporanee si svegliano tutte con una sola emissione | Media |
| TC-04 | Integration | `attendi=1` con `If-None-Match` **diverso** ⇒ `200` subito, senza aspettare | Alta |
| TC-05 | Integration | `attendi=1` con impronta uguale, poi una scrittura sul DAO ⇒ `200` con il corpo nuovo | Alta |
| TC-06 | Integration | `attendi=1`, impronta uguale, nessuna scrittura ⇒ `304` alla scadenza | Alta |
| TC-07 | Integration | Segnale che **non** cambia l'impronta (scrittura su un evento già completato) ⇒ resta appeso, **non** risponde `200` | Alta |
| TC-08 | Integration | Scrittura su un DAO raggiunto **senza passare da `ScrittureWeb`** ⇒ l'attesa si sveglia lo stesso. *Presidio di US-006: dimostra che il segnale nasce dal database e non dal percorso di scrittura* | Alta |
| TC-09 | Integration | Nona attesa contemporanea con tetto 8 ⇒ risposta immediata, **nessun errore** | Alta |
| TC-10 | Integration | `GET /api/eventi` **senza** `attendi` ⇒ identico a oggi (compatibilità all'indietro) | Alta |
| TC-11 | Integration | Due token diversi: si svegliano insieme e ciascuno riceve **il proprio** corpo, con i propri permessi e la propria impronta | Media |
| TC-12 | Integration | Una connessione appesa **non blocca** le altre connessioni al server | Alta |
| TC-13 | Manuale · V-01 | Modifico un titolo dall'app: cambia **una scheda sola** (`MutationObserver` in console). **Criterio principale della feature** | Alta |
| TC-14 | Manuale · V-02 | Scorro in fondo a un elenco lungo, modifico dall'app un evento in cima: `scrollY` non cambia | Alta |
| TC-15 | Manuale · V-03 | Seleziono del testo su una scheda, ne modifico un'altra dall'app: la selezione resta | Media |
| TC-16 | Manuale · V-04 | Sposto la data di un evento: la scheda cambia posizione e **non ne compaiono due** | Alta |
| TC-17 | Manuale · V-05 | **R-11:** modifico dall'app, poi tocco «Fatto» sulla *stessa* scheda dal browser ⇒ nessun conflitto | Alta |
| TC-18 | Manuale · V-06 | «Fatto» dal browser: sparisce una scheda sola, la barra dell'annullamento compare, «Annulla» rimette la scheda al suo posto | Alta |
| TC-19 | Manuale · V-07 | Modifico dall'app **mentre il modulo è aperto**: ciò che sto scrivendo non si muove; a modulo chiuso la modifica c'è (D-03) | Alta |
| TC-20 | Manuale · V-08 | Nascondo la scheda del browser: sul telefono **nessuna attesa resta appesa** (R-14). Spengo e riaccendo il Wi-Fi: la pagina si riallinea da sola | Alta |
| TC-21 | Manuale · V-09 | Lettore di schermo (R-16): l'aggiornamento annuncia la **scheda cambiata**, non tutto l'elenco | Media |
| TC-22 | Manuale · V-10 | **D-09:** modifico dall'app ⇒ quella scheda si evidenzia e l'evidenziazione svanisce da sola. **Ricaricando la pagina non si evidenzia niente.** Lasciando la pagina aperta qualche minuto, la fascia cromatica che cambia **non** cancella un'evidenziazione accesa (§5.8, trappola 1) | Alta |
| TC-23 | Manuale | Non-regressione 002/003/004: `304` e posizione di scorrimento, asset, `403` senza token, **impronta del certificato invariata**, scrittura e conflitto | Alta |

### Definition of Done

Adattata al progetto: non c'è CI né staging, quindi i criteri sono quelli eseguibili qui.

- [ ] `./gradlew :shared:desktopTest :desktopApp:test` verde.
- [ ] I test di `androidInstrumentedTest` verdi sull'emulatore (`CorpoPiattaformaTest`,
      `TlsPiattaformaTest`) — non toccati da questa feature, ma è dove si scoprono le trappole di
      piattaforma.
- [ ] **M-4: `RouterTest.kt` e `WebServiceTest.kt` non compaiono nel diff.**
- [ ] Checklist manuale V-01…V-10 eseguita e annotata in `status.md`, con l'esito, non con una
      spunta.
- [ ] M-1 e M-2 misurate, con il numero scritto.
- [ ] `CLAUDE.md` aggiornato: la frase sul ridisegno totale non deve restare com'è.
- [ ] I commenti di `app.js` e `index.html` che dichiarano il comportamento vecchio: riscritti.

---

## 8. Rischi e mitigazioni

| # | Rischio | Prob. | Impatto | Mitigazione |
|---|---|---|---|---|
| **R-02** | **Doze verso una connessione già aperta e ferma.** `WebServerService` fa già tutto il possibile, ma nessuno ha mai chiesto al telefono di tenere una richiesta appesa 25 s a schermo spento | Media | **Medio** *(era Alto: §3.1)* | T-01, misura precoce non bloccante. Se non regge, il piano non cambia — degrada da sé al polling (R-15) — e cambia un paragrafo della documentazione. L'impatto è alto **solo** nell'esito «impedisce il Doze o fa uccidere il servizio», che T-01 cerca apposta |
| **R-11** | **La closure sopravvive ai dati** (§5.5). Un `409` incomprensibile su una scheda che mostra il valore giusto | Alta senza mitigazione | **Alto** | T-09 **prima** di T-10, come modifica isolata. TC-17 in checklist |
| **R-04** | **Riordino.** Cambiare la data sposta la scheda; un algoritmo che gestisce male lo spostamento produce duplicati o buchi | Media | Alto | Riconciliazione **per chiave** (`id`), mai per indice. TC-16 |
| **R-12** | **Corsa fra composizione del corpo e sottoscrizione al segnale.** Si manifesta come «ogni tanto la pagina è in ritardo di 25 secondi»: il modo più difficile da riprodurre | Media | Medio | L'ordine di §5.4 con il commento che lo spiega, più TC-02 |
| **R-14** | **La scheda nascosta lascia un'attesa appesa.** Senza `AbortController`, ogni scheda dimenticata trattiene una coroutine e un socket per tutta la durata dell'attesa — cioè il tetto consumato da schede che nessuno guarda | Alta senza mitigazione | Medio | `AbortController` annullato su `visibilitychange` (T-13). È anche ciò che rende onesto il tetto a 8 |
| **R-15** | **Un `fetch` che non si risolve mai** blocca il ciclo auto-schedulato e la pagina muore in silenzio, mostrando dati vecchi come freschi | Media | Medio | Guardia `attesa + margine` → `abort` e ripartenza (T-13). Sostituisce il vago «polling lento di sicurezza» della Fase 1 |
| **R-09** | **`app.js` cresce.** Oggi 461 righe con una struttura dichiarata in testa; la riconciliazione è la parte più intricata del file | Alta | Medio | Riconciliazione come funzione pura separata (T-10) e intestazione riscritta (T-14) |
| **R-13** | **`READ_TIMEOUT_MILLIS = 10_000` sembra vietare l'attesa e non la vieta**: è `soTimeout`, un timeout di *lettura* impostato prima di `leggiRichiesta` (`HttpServer.kt:89`); dopo non si legge più | Bassa | Medio | Un commento nel punto dell'attesa. Senza, un lettore futuro "aggiusta" una contraddizione che non esiste, o alza quel timeout credendo che serva |
| **R-01** | **Connessioni appese.** Ridimensionato: il modello è già una coroutine per connessione, un'attesa non aggiunge una macchina | Bassa | Medio | Tetto a 8 (D-07) con degrado a risposta immediata |
| **R-03** | **Costo TLS.** Ridimensionato: `SSLContext` costruito una volta sola (003) e cache di sessione attiva. Un'attesa che scade ogni 25 s costa quanto il polling ogni 30 s | Bassa | Basso | Misurato in T-16 (M-5), non assunto |
| **R-17** | **`className` cancella l'evidenziazione** (§5.8, trappola 1). Il difetto è intermittente per costruzione: si manifesta solo se `aggiornaColori()` passa nei due secondi in cui l'evidenziazione è accesa — cioè raramente, e mai mentre lo si cerca | Alta senza mitigazione | Medio | Sulla scheda si scrive con `classList`, mai con `className`, in **entrambe** le direzioni. TC-22 |
| **R-18** | **L'evidenziazione al primo caricamento** farebbe lampeggiare l'intera pagina all'apertura: il difetto che US-002 esiste per togliere, reintrodotto dalla porta di servizio. Stesso caso al rientro da modulo aperto, dove il confronto è contro dati vecchi di minuti | Alta senza mitigazione | Medio | Si accende solo dopo `primaRisposta`, che in `app.js` esiste già per fare questa distinzione. TC-22 |
| **R-16** | **`aria-live` cambia comportamento**: oggi un lettore di schermo rilegge l'intero elenco, domani solo il nodo cambiato. È un miglioramento, ma è un cambiamento | Alta | Basso | TC-21 |
| **R-10** | **Nessun test automatico del JavaScript**, e il grosso del lavoro è JavaScript | Certa | Medio | **Accettato consapevolmente** (D-04/D-05/D-08): funzione pura provabile domani + checklist manuale V-01…V-10 |

**Chiusi dall'analisi:** R-05 (`uuid` fuori dal payload: `id` basta), R-06 (modulo aperto: la
sospensione resta), R-07 (`daAnnullare` tiene una copia dei dati, non un nodo — `app.js:308-311`),
R-08 (due token, due impronte: il contatore è globale, l'impronta è per client).

**Non verificabile dall'analisi, e resta tale fino alla prova:** il comportamento di Doze (R-02), il
comportamento di `createFlow` sul driver di Android (T-02), il costo reale di un handshake ripreso
da sessione su questo telefono, e se 8 sia davvero il numero giusto — è un ragionamento, non una
misura.

---

## 9. Rollout

**Strategia di rilascio:**
- [x] **Deploy diretto.** L'APK si installa a mano sui dispositivi dell'utente.
- [ ] Graduale con feature flag
- [ ] Canary release

**Nessun feature flag nuovo, e la ragione è che non servirebbe a niente.** L'interruttore c'è già
ed è `webEnabled` in `AppSettings`, spento di default dalla 002: finché è spento non c'è porta, non
c'è pagina e non c'è niente da aggiornare. Un secondo interruttore «usa il push» aggiungerebbe uno
stato in più da provare per proteggere da un cambiamento che, se va storto, **degrada da solo verso
il comportamento di oggi** — che è precisamente ciò che un flag serve a garantire.

La gradualità sta altrove, ed è strutturale (§5.7): l'attesa è opt-in dal client, e un client che
non la chiede riceve la risposta di sempre.

**Piano di rollback:**
1. `git revert` del commit. Non ci sono migrazioni da disfare, non c'è stato persistito da ripulire,
   non c'è formato da riportare indietro.
2. Reinstallare l'APK precedente **funziona senza altri passi**: lo schema del database è invariato
   (v5) e il payload è invariato (v2), quindi una versione precedente apre lo stesso database e
   parla lo stesso protocollo.
3. Le schede del browser aperte non vanno ricaricate: un `app.js` nuovo contro un server vecchio
   manda un parametro che viene ignorato e ricade sul polling.

**Rollback parziale, che è l'opzione più probabile.** Se T-01 dice che il long-poll non regge a
schermo spento, non si revoca niente: si spegne l'invio di `attendi=1` nel client e restano T-09…T-12,
cioè il ridisegno mirato a ritmo di 30 s. È la consegna di §6 «se il tempo stringe», e vale la pena
notare che il piano è ordinato apposta perché quel ripiego sia sempre disponibile.

---

## 10. Checklist di approvazione

Progetto personale a sviluppatore unico: i ruoli coincidono nella stessa persona. La tabella resta
perché i cappelli vanno indossati comunque, e separarli aiuta a non saltarne uno.

| Revisione | Responsabile | Stato | Data |
|---|---|---|---|
| Ambito confermato: push **e** ridisegno mirato insieme, non uno solo | Alberto Goldoni | ✅ Fatto | 2026-08-23 |
| D-01, D-02, D-03, D-04, D-05 chiuse dall'analisi | Alberto Goldoni | ✅ Fatto | 2026-08-23 |
| **D-06 — declassata da bloccante a misura precoce** (§3.1) | Alberto Goldoni | ✅ Confermata | 2026-08-23 |
| Esito di T-01 (Doze a schermo spento): tre numeri, scritti in `status.md` | Alberto Goldoni | 📏 Da misurare | — |
| D-07 — attesa 25 s, tetto 8 attese | Alberto Goldoni | ✅ Confermata | 2026-08-23 |
| **D-08 — si accetta nessun test automatico del JavaScript** | Alberto Goldoni | ✅ Accettata | 2026-08-23 |
| **D-09 — l'evidenziazione della scheda cambiata entra nello scope** (+0,30 gg) | Alberto Goldoni | ✅ Confermata | 2026-08-23 |
| Revisione tecnica (long-poll invece di SSE; nessun breaking change) | Alberto Goldoni | ⏳ In attesa | — |
| Revisione prodotto (**l'evidenziazione è dentro; le animazioni di riordino restano fuori**) | Alberto Goldoni | ⏳ In attesa | — |
| Stima approvata (**6,20 gg** + 1,00 di margine su R-02) | Alberto Goldoni | ⏳ In attesa | — |
| Rischi accettati (in particolare **R-11**, **R-17**, **R-10**; R-02 declassato a Medio) | Alberto Goldoni | ⏳ In attesa | — |
| Data di inizio confermata | Alberto Goldoni | ⏳ In attesa | — |

---

## Domande aperte

**Nessuna decisione resta aperta.** Resta una **misura**, che è un'altra cosa: non attende una
risposta da qualcuno, attende un telefono.

1. **Quanto ci mette il telefono a rispondere a schermo spento?** (D-06 → T-01, ~0,25 gg) Le tre
   domande e le tre previsioni sono in §3.1 e nella riga T-01 di §6. Il risultato non sblocca il
   lavoro — §3.1 spiega perché non lo blocca — ma **produce tre numeri che vanno scritti in
   `status.md` e in `CLAUDE.md`**: senza, la documentazione direbbe «si aggiorna subito» senza
   sapere se è vero, che è il modo in cui una promessa diventa una bugia per omissione.

### Chiuse il 2026-08-23

| Era | Risposta | Effetto sul piano |
|---|---|---|
| **25 secondi e 8 attese vanno bene?** (D-07) | **Sì, confermati** | Nessuno sulla stima. I due numeri vanno scritti accanto alla costante **con il loro perché**: un numero senza motivazione è un numero che il prossimo cambierà a caso |
| **Si accetta che il grosso del lavoro non abbia test automatici?** (D-08) | **Sì, accettato** | La checklist manuale V-01…V-10 di §7 **è** il piano di test del frontend, non un complemento. Va eseguita e annotata con l'esito, non con una spunta |
| **Il long-poll regge a schermo spento?** (D-06) | **Non è una decisione: è una misura, e non blocca** | T-01 declassato da cancello a misura precoce; §3.1 nuova con le tre ragioni e la previsione scritta prima; R-02 da Alto a Medio; si comincia dal frontend il primo giorno |
| **L'evidenziazione della scheda cambiata resta fuori?** (D-09) | **No: entra** | +0,30 gg (**T-12b**), US-007 in §4, §5.8 nuova, `app.css` non è più «nessuna modifica», TC-22 e V-10 nuovi, R-17 e R-18 nuovi. Totale **6,20 gg** |

**Una nota su D-09, perché il piano è cambiato più di quanto lo dica la stima.** L'evidenziazione
sembrava un ornamento e in Fase 1 era stata messa fuori come tale. Guardandola dentro lo scope si
vede che è il rovescio di US-002: rendere l'aggiornamento silenzioso toglie il lampeggio e nello
stesso gesto toglie anche il **segnale**. E porta con sé due trappole che una riga di CSS non
lascerebbe sospettare — `className` che cancella l'evidenziazione in modo intermittente e quasi
irriproducibile, e il primo caricamento che la reintrodurrebbe come lampeggio globale (§5.8). Sono
0,30 giorni di lavoro e mezza pagina di ragionamento: il rapporto è quello giusto per una cosa che
si tocca ogni volta che si guarda la pagina.

---

*Documento generato con la skill `claude-code-feature`.*
