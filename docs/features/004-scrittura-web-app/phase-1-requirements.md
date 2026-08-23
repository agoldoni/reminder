# Feature: Promemoria modificabili dalla web app locale — Requisiti (Fase 1)

**Slug:** `scrittura-web-app`
**Data:** 2026-08-23
**Stato:** Bozza in attesa di approvazione
**Progetto:** Promemoria (`it.agoldoni.reminder`) — Compose Multiplatform, Android + Desktop JVM, Kotlin 2.3.21 / Room KMP / MVVM
**Team:** 1 sviluppatore (Alberto Goldoni)
**Piattaforma interessata:** solo Android, come la 002 e la 003 (il codice resta in `jvmSharedMain` e provabile su desktop)
**Estende:** [002-web-app-android](../002-web-app-android/status.md) (la porta) e [003-https-web-app](../003-https-web-app/status.md) (il traffico cifrato)

**Decisioni già prese dall'utente (input di questa fase):**

| Decisione | Scelta | Conseguenza principale |
|---|---|---|
| Operazioni | **Modifica, creazione, completamento** | La **cancellazione resta fuori**: dal browser non si distrugge niente |
| Accesso | **Due URL: uno in sola lettura, uno con pieni poteri** | Due token, non un interruttore. Il potere è una proprietà dell'indirizzo, non dell'app |
| Conflitti | **Controllo ottimistico: `409` e ricarica** | Il browser dichiara che cosa credeva di modificare; se il telefono è andato avanti, la scrittura non passa |

---

## 1. Obiettivo e motivazione

La 002 ha messo la lista dei promemoria in un browser sulla rete di casa. La 003 ha cifrato il filo.
Quello che resta è un'asimmetria che si sente ogni volta: la lista è lì, sullo schermo grande, con la
tastiera sotto le mani — e per spostare un appuntamento di mezz'ora bisogna prendere in mano il
telefono, sbloccarlo, trovare l'app, trovare la riga. La pagina web sa dire *che cosa* c'è ma non
serve a *farci* niente.

Questa feature chiude quell'asimmetria e non fa nient'altro: da una pagina aperta sul computer si
modifica un promemoria, se ne crea uno nuovo, se ne segna uno fatto.

**Perché ha senso adesso e non prima.** Non è una questione di ordine dei lavori: è che *prima
sarebbe stato irresponsabile*. Su una porta in chiaro, aggiungere la scrittura non avrebbe aggiunto
un'API — avrebbe messo in rete un **telecomando**. Chi ascoltava il filo prima della 003 leggeva il
token e vedeva una lista; con la scrittura avrebbe potuto spostare un appuntamento di tre ore e far
suonare la notifica a cose fatte. La 003 non è un prerequisito burocratico di questa feature: è la
ragione per cui questa feature si può fare.

**Il terreno è già preparato, e non per caso.** Tre scelte della 002 esistono esattamente per questo
momento, e sono scritte nel codice con la loro motivazione:

- `Router` smista **prima per metodo e poi per percorso**, con il commento che dice perché: così
  aggiungere le scritture vuol dire aggiungere un ramo, non riscrivere la struttura.
- `VoceWeb` porta un campo `id` che oggi non serve a nulla, messo lì perché «una scrittura futura
  nominerà l'evento» e aggiungerlo dopo sarebbe stata una rottura di formato.
- `WEB_PAYLOAD_VERSION` esiste «per quando arriverà la scrittura», perché un client vecchio contro un
  server nuovo fallisca dicendolo invece di provare a capirsi.

Questa feature è il seguito che quei tre commenti annunciano.

### Che cosa cambia nel modello di rischio

Non cambia l'attaccante — resta quello della 003, il **Wi-Fi con password condivisa**: casa con
ospiti, piccolo ufficio, bed & breakfast. Cambia il **danno**, ed è la cosa da pesare:

| | Fino alla 003 | Con questa feature |
|---|---|---|
| Token compromesso | l'attaccante **legge** i promemoria | l'attaccante **modifica** i promemoria |
| Guasto peggiore | perdita di riservatezza | un appuntamento spostato, e **una notifica che non suona** |
| Si nota? | no, mai | sì, ma **dopo** — quando l'allarme non è arrivato |

È un salto vero: si passa da un guasto che *espone* a un guasto che *sabota*, e un promemoria serve
proprio a non dimenticare una cosa. Da qui discendono due scelte di questo documento — la
cancellazione fuori dallo scope, e i due indirizzi separati — che non sono prudenza generica ma la
risposta a questa riga della tabella.

### I due indirizzi

Il token non diventa più forte: resta lo stesso segreto da otto caratteri della 002, retto dalla
coppia «token corto + dieci tentativi al minuto per indirizzo». Cambia che **i token diventano due**,
e il potere sta nell'indirizzo che si usa:

- **Indirizzo di lettura** — la pagina di oggi: elenco, colori, aggiornamento. Nessun comando.
- **Indirizzo completo** — la stessa pagina, con i comandi di modifica.

La conseguenza pratica è quella che rende la scelta preferibile a un interruttore nell'app:
**l'indirizzo di lettura diventa una cosa che si può dare a qualcun altro**. Il coinquilino, la
persona con cui si condivide il calendario di casa, il collega che deve solo sapere quando è la
riunione: guardano, non toccano. Con un interruttore unico bisognava scegliere fra «tutti guardano»
e «tutti comandano».

**Metriche di successo (verifica manuale, nessuna analytics):**

- [ ] Dal computer si sposta l'orario di un appuntamento e la notifica sul telefono arriva **all'ora
      nuova**, senza aver toccato il telefono. ⚠️ **Superata da D-08:** l'app dev'essere aperta, quindi
      il telefono si tocca. La formulazione corretta è in `phase-3-implementation-plan.md` §2.
- [ ] Aprendo l'indirizzo di lettura i comandi non ci sono, e una scrittura costruita a mano contro
      quell'indirizzo riceve `403`.
- [ ] Un promemoria modificato sul telefono mentre la pagina era aperta **non viene sovrascritto**: la
      pagina lo dice e mostra il valore vero.
- [ ] Una modifica fatta dal browser arriva sull'altro dispositivo associato al giro di
      sincronizzazione successivo, senza trattamenti speciali.

---

## 2. Scope

### Incluso

**Operazioni**

1. **Modifica di un promemoria esistente**: titolo, descrizione, data e ora, minuti di anticipo — gli
   stessi quattro campi dell'editor dell'app, né uno di più.
2. **Creazione di un promemoria nuovo** dalla pagina.
3. **Completamento** di un promemoria («fatto»), con **annullamento della completazione appena
   fatta** finché la pagina non viene ricaricata (vedi D-01).

**Meccanica**

4. **Due token** per accensione, con due livelli d'accesso; entrambi si rigenerano a ogni accensione
   dell'interruttore e si invalidano allo spegnimento, come oggi.
5. **Due indirizzi copiabili** nella schermata dell'app, ciascuno con la sua etichetta e il suo tocco
   per copiare — lo stesso gesto della riga di oggi.
6. **Controllo ottimistico** su ogni scrittura: il browser dichiara l'`updatedAt` che aveva letto, il
   server rifiuta con `409` se nel frattempo la riga è cambiata.
7. **Validazione lato server** di tutto ciò che arriva: titolo non vuoto, campi entro limiti
   dichiarati. Il browser controlla per cortesia; il server controlla perché è l'unico che conta.
8. **Riprogrammazione degli allarmi** a ogni scrittura, con le stesse regole dell'app.
9. **Lettura del corpo delle richieste** in `HttpMessages`, che oggi di proposito non lo legge:
   `Content-Length` esplicito, limite duro, niente `Transfer-Encoding`.
10. **Versione 2 del formato di scambio**, con il livello di accesso dichiarato dal server nel
    payload.
11. **La scrittura richiede l'app aperta** sul telefono (D-08, decisa dopo questa stesura).

### Escluso (out of scope)

| Fuori | Perché |
|---|---|
| **Cancellazione** | È l'unica operazione irreversibile, e dal browser mancherebbe la rete di sicurezza che sul telefono c'è: la vista dei completati, il gesto lento, il dispositivo che è già in mano. Conseguenza accettata e da dire all'utente: **un promemoria creato per sbaglio dal browser si toglie solo dal telefono.** |
| **Vista dei completati** | La pagina mostra gli aperti, come oggi. Riaprire un promemoria completato ieri resta un lavoro da app. |
| **Modifica di `uuid`, `origin`, `deleted`** | Sono l'identità dell'evento fra dispositivi: non escono verso il browser oggi e non entrano da lì domani. |
| **Sincronizzazione immediata dopo una scrittura** | Una modifica dal web tocca `updatedAt` e viene raccolta dal giro di sincronizzazione successivo. Farla partire subito è un'altra feature, e ha i suoi problemi (il telefono potrebbe non essere in primo piano). |
| **Scrittura dal desktop** | `WebService` su desktop non è cablato (`WebServerNonDisponibile`). Non cambia. |
| **Più utenti, permessi per evento, registro delle modifiche** | Il modello resta «un proprietario, due indirizzi». |
| **Ricorrenze, allegati, categorie** | Non esistono nell'app. |
| **PWA installabile / uso offline** | Resta bloccata dal certificato non fidato, come accertato dalla 003. Un modulo di modifica che funzionasse offline vorrebbe una coda di scritture da riconciliare: è un'altra feature, non un dettaglio. |
| **Autenticazione diversa dal token nella query** | Cambiare il modo in cui il segreto viaggia è un lavoro suo, già annotato fra le idee rimandate della 003. |

---

## 3. User Stories

**US-01 — Correggere un orario senza prendere il telefono**
> Come utente seduto al computer, voglio cambiare data e ora di un promemoria dalla pagina web, per
> non dover sbloccare il telefono per una modifica da dieci secondi.

**US-02 — Scrivere con una tastiera vera**
> Come utente che sta creando un promemoria con una descrizione lunga, voglio poterlo fare dalla
> pagina web, per scrivere con la tastiera del computer invece che con quella a schermo.

**US-03 — Tenere pulita la lista mentre si lavora**
> Come utente con la pagina aperta in una scheda, voglio segnare fatto un promemoria con un tocco,
> per non accumulare cose già fatte fino a sera.

**US-04 — Far guardare senza far toccare**
> Come utente, voglio poter dare a un'altra persona un indirizzo che mostra i promemoria ma non
> permette di cambiarli, per condividere l'informazione senza condividere il comando.

**US-05 — Non perdere una modifica fatta sul telefono**
> Come utente che ha modificato un promemoria dal telefono, voglio che una scheda del browser aperta
> da un'ora non me la sovrascriva in silenzio, per potermi fidare di entrambe le viste.

**US-06 — Sapere se una modifica è andata a segno**
> Come utente che ha appena salvato dal browser, voglio vedere subito il promemoria aggiornato nella
> lista, per non restare nel dubbio di aver premuto a vuoto.

---

## 4. Criteri di accettazione

### US-01 — Modifica

- [ ] Ogni riga della lista, sull'indirizzo completo, offre un modo evidente di aprire la modifica.
- [ ] Il modulo si apre **con i valori attuali** dell'evento: titolo, descrizione, data e ora,
      minuti di anticipo.
- [ ] Salvando, la riga nella lista mostra i valori nuovi **senza attendere il giro di polling**.
- [ ] Sul telefono, la riga corrispondente mostra gli stessi valori (l'elenco è un `Flow` di Room: si
      aggiorna da sé).
- [ ] **L'allarme è riprogrammato**: spostando l'orario in avanti la notifica arriva all'ora nuova;
      spostandolo indietro, idem. È il criterio che vale più di tutti gli altri messi insieme.
- [ ] `uuid` e `origin` della riga sono **identici** a prima della modifica.
- [ ] Un titolo vuoto o di soli spazi non viene accettato, e la pagina lo dice.
- [ ] Annullando il modulo, niente è stato scritto.

### US-02 — Creazione

- [ ] Dall'indirizzo completo si apre un modulo vuoto, con dei valori iniziali sensati (gli stessi
      dell'app: fra un'ora, quindici minuti di anticipo).
- [ ] L'evento creato compare nella lista dell'app **e** sulla pagina.
- [ ] L'evento nasce con `origin` uguale all'identità del **telefono** (non del browser: il browser
      non ha identità in questo sistema) e con un `uuid` nuovo generato dal server.
- [ ] L'allarme del nuovo evento è programmato.
- [ ] Un evento creato con data nel passato è accettato (l'app lo permette) e compare come scaduto.

### US-03 — Completamento

- [ ] Un tocco su un comando esplicito segna fatto il promemoria; la riga esce dalla lista degli
      aperti su entrambe le viste.
- [ ] L'allarme è **annullato**: la notifica non arriva più.
- [ ] Finché la pagina non è ricaricata, l'ultima completazione si può annullare, e l'evento torna
      aperto con l'allarme riprogrammato.
- [ ] Il completamento non altera titolo, data o descrizione.

### US-04 — I due indirizzi

- [ ] La schermata dell'app mostra **due** indirizzi, distinti da un'etichetta che dice senza
      ambiguità che cosa permette ciascuno.
- [ ] Toccando ciascuna riga si copia l'indirizzo corrispondente, con la conferma già in uso.
- [ ] Aprendo l'indirizzo di lettura, **nessun comando di scrittura è visibile**.
- [ ] Una richiesta di scrittura costruita a mano con il token di lettura riceve `403` e **non
      modifica niente**. È questo il criterio, non il precedente: nascondere non è proteggere.
- [ ] Il token completo permette anche la lettura (non serve tenere aperte due schede).
- [ ] Spegnendo e riaccendendo l'interruttore, **entrambi** gli indirizzi cambiano, e i vecchi
      ricevono `403`.
- [ ] Dieci tentativi falliti da uno stesso indirizzo IP fanno smettere di rispondere per un minuto,
      esattamente come oggi, contando insieme i tentativi contro l'uno e contro l'altro token.

### US-05 — Conflitti

- [ ] Modificando lo stesso evento prima dal telefono e poi dalla scheda del browser rimasta ferma, il
      salvataggio dal browser **fallisce**.
- [ ] La pagina lo spiega con parole comprensibili — non «409» — e mostra il valore attuale.
- [ ] Dopo la ricarica, rifare la stessa modifica funziona.
- [ ] Il caso simmetrico (due schede del browser) si comporta allo stesso modo.
- [ ] Due scritture simultanee sullo **stesso** evento non possono passare entrambe.

### US-06 — Riscontro

- [ ] Al salvataggio riuscito, la lista mostra i dati nuovi senza un giro di rete in più.
- [ ] Se il telefono non risponde durante un salvataggio, il testo digitato **non va perso** e si può
      ritentare.
- [ ] Mentre si compila un modulo, l'aggiornamento periodico **non cancella ciò che si sta
      scrivendo** — né quello dei dati ogni 30 s né il ridisegno dei colori ogni 60 s.
- [ ] Le date scritte dal browser corrispondono a quelle mostrate dall'app quando i due dispositivi
      sono nello stesso fuso orario (vedi R-03 per il caso contrario).

### Non-regressione (002 e 003)

- [ ] L'indirizzo di lettura si comporta esattamente come la pagina di oggi.
- [ ] `304` continua a funzionare e la posizione di scorrimento non si perde.
- [ ] Il certificato **non** viene riemesso: l'impronta dopo questa feature è la stessa di prima.
- [ ] La porta parla solo TLS; nessun percorso nuovo è servito in chiaro.
- [ ] Le tre porte fisse restano distinte (47653 / 47700 / 9888) e i test di presidio restano verdi.
- [ ] `./gradlew :shared:desktopTest :desktopApp:test` verde (236 test alla fine della 003).

---

## 5. Rischi e dipendenze

### Rischi tecnici

| ID | Rischio | Impatto | Prob. | Mitigazione |
|---|---|---|---|---|
| **R-01** | **Leggere un corpo di richiesta è codice nuovo su una porta esposta.** `HttpMessages` oggi non lo legge *di proposito*, e il commento lo dice: «un corpo che non si legge è un corpo che non si deve interpretare». Quella difesa viene rimossa. | Alto | Media | Solo `Content-Length` numerico e piccolo (limite duro dichiarato); `Transfer-Encoding` presente ⇒ `400` secco, mai un tentativo di interpretarlo; lettura di esattamente N byte e non un byte di più; nessun keep-alive, quindi nessuna richiesta successiva sulla stessa connessione da poter confondere. |
| **R-02** | **Il token completo è un telecomando.** Se sfugge, l'attaccante non legge: sabota, e il guasto si scopre quando la notifica non arriva. | Alto | Bassa | I due token separati (si condivide quello che non comanda); rigenerazione a ogni accensione; nessuna cancellazione, quindi il danno massimo è reversibile a mano; TLS della 003. |
| **R-03** | **Fuso orario del browser diverso da quello del telefono.** La 002 ha scelto di formattare le date nel browser; in lettura è giusto, in scrittura significa che «18:00» digitato su un computer in un altro fuso diventa un'altra ora sul telefono. | Medio | Bassa | Il caso realistico è «stessa casa, stesso fuso». Da decidere se il modulo debba mostrare il fuso quando i due divergono; in ogni caso il server riceve **millisecondi**, quindi l'ambiguità è tutta nel browser e va risolta lì. Documentare. |
| **R-04** | **La corsa fra due scritture.** Leggere la riga, confrontare `updatedAt` e scrivere non è atomico: due richieste simultanee possono superare entrambe il confronto. `HttpServer` serve ogni connessione in una coroutine sua, quindi il caso è raggiungibile. | Medio | Bassa | Rendere atomico il controllo (aggiornamento condizionato su `updatedAt` oppure transazione), non affidarsi alla sequenza. Verificarlo con un test che spara due scritture insieme. |
| **R-05** | **La modifica c'è nel database ma l'allarme è quello vecchio.** È il guasto silenzioso peggiore di tutta la feature: sembra funzionare, e fallisce all'ora sbagliata. | Alto | Media | Un solo punto che scrive **e** riprogramma, mai due strade; `AlarmScheduler` iniettato in `WebService` (oggi non c'è); test che verificano la chiamata al programmatore per ognuna delle tre operazioni, completamento compreso (lì è un `cancel`). |
| **R-06** | **CSRF e DNS rebinding.** Una pagina qualsiasi aperta sul computer potrebbe provare a scrivere sulla porta del telefono. | Medio | Bassa | Senza token non passa nulla, e il token non è indovinabile né leggibile dal `Referer` (`no-referrer`). In più: pretendere `Content-Type: application/json`, che un modulo HTML cross-origin **non può** produrre senza preflight — costa una riga e chiude la strada più semplice. |
| **R-07** | **Il ridisegno periodico cancella il modulo mentre si scrive.** `disegna()` azzera l'elenco e lo ricostruisce; gira ogni 30 s per i dati e ogni 60 s per i colori. Un modulo aperto dentro l'elenco sparirebbe sotto le dita. | Medio | **Alta** | È un difetto che si manifesta *sempre*, non a caso: mentre un modulo è aperto il ridisegno va sospeso o il modulo va tenuto fuori dall'albero che si ricostruisce. Da decidere in fase 2, ma da decidere. |
| **R-08** | **Nascondere i comandi non è proteggerli.** La stessa pagina servita ai due token deve *sembrare* diversa, ma la differenza che conta è nel server. | Alto | Bassa | Ogni percorso di scrittura verifica il token completo per conto proprio; il campo nel payload serve alla presentazione e non è mai un controllo d'accesso. Un test costruisce la scrittura a mano con il token di lettura. |
| **R-09** | **`WebService` ha bisogno di cose che oggi non riceve**: `AlarmScheduler` e l'identità del dispositivo per `origin`. Cambia la costruzione in `ReminderApp` e la firma usata da dieci test. | Basso | Alta (certa) | Parametri con valori sensati e un solo punto di costruzione; è lavoro meccanico, ma va contato. |
| **R-10** | **Ricostruire l'entità invece di copiarla.** È il modo classico di perdere `uuid` e `origin`, ed è già scritto in `CLAUDE.md` come regola. Chi scrive il gestore delle scritture parte da un JSON e la tentazione c'è tutta. | Alto | Media | Il gestore parte **sempre** dalla riga letta e usa `copy()`, come fa `EventEditViewModel.save()`; un test confronta `uuid` e `origin` prima e dopo. |
| **R-11** | **Valori assurdi accettati**: un anticipo di un milione di minuti, una data nell'anno 30000. L'app protegge con l'interfaccia, il browser no. | Medio | Media | Limiti espliciti lato server, con un rifiuto che dice quale campo è fuori. Da fissare in fase 2 e da scrivere nel documento, non da lasciare al buon senso di chi implementa. |
| **R-12** | **Il `403` oggi ha un significato solo.** La pagina dice «indirizzo non più valido»; con due token, un `403` su una scrittura vuol dire «questo indirizzo è in sola lettura» — un messaggio diverso, e dirlo sbagliato manda l'utente a riaccendere l'interruttore per niente. | Basso | Media | Distinguere i due casi nel client in base a **che cosa** stava facendo, senza differenziare la risposta sul filo (la 002 ha scelto di non farlo, e la ragione vale ancora). |
| **R-13** | **Il payload versione 2 contro una pagina in cache.** Il browser tiene `no-store`, quindi il caso è improbabile, ma un `app.js` vecchio con un server nuovo è esattamente ciò per cui `WEB_PAYLOAD_VERSION` esiste. | Basso | Bassa | Il client controlla la versione e, se non la riconosce, lo dice invece di provare a lavorare. |

### Dipendenze

- **003 (TLS)** — completata. Concettualmente **bloccante**: senza, questa feature non andrebbe fatta.
- **002 (porta, token, asset, `ProcessKeeper`)** — completata; se ne toccano diversi pezzi.
- **Nessuna libreria nuova.** `kotlinx.serialization` è già in uso per il payload; Room, `AlarmScheduler` e il resto ci sono.
- **Nessuna migrazione di schema.** La tabella `events` ha già tutto: è la stessa scrittura che fa l'app.
- **Sincronizzazione** — non si tocca, ma se ne eredita il modello: le scritture dal web sono scritture normali e viaggiano ai peer con `updatedAt`, risolte da `resolveMerge` come tutte le altre.
- **Verifica sul campo** — serve il telefono; la prova su rete reale è la stessa che è rimasta aperta nella 003 (PC e telefono oggi su due sottoreti diverse).

---

## 6. Stima effort

Un solo sviluppatore. Giorni/uomo, con il dettaglio perché la somma sia discutibile invece che da
accettare in blocco.

| Area | Voce | Giorni |
|---|---|---|
| **BE** | Corpo delle richieste in `HttpMessages`: `Content-Length`, limiti, rifiuto di `Transfer-Encoding` | 0,5 |
| **BE** | `AccessToken` a due livelli: due segreti, un solo contatore dei tentativi | 0,5 |
| **BE** | `Router`: rotte di scrittura, id nel percorso, `405`/`409`/`415`/`422`, `Allow` corretto | 0,75 |
| **BE** | Le scritture vere: validazione, `copy()` sulla riga esistente, atomicità, allarmi | 0,75 |
| **BE** | `WebService`/`WebStatus`/`ReminderApp`: due token, due indirizzi, dipendenze nuove | 0,5 |
| | *Subtotale backend* | **3,0** |
| **FE** | Schermata dell'app: due righe copiabili, etichette che non si prestano a equivoci | 0,5 |
| **FE** | Pagina: modulo di modifica e creazione, comando «fatto», protezione del testo digitato dal ridisegno (R-07), messaggi di conflitto | 1,25 |
| | *Subtotale pagina* | **1,75** |
| **Test** | Unità (corpo HTTP, token, validazione, merge dell'entità) + giro completo sopra TLS + corsa di R-04 + il `403` di R-08 | 1,0 |
| **Doc** | `CLAUDE.md`, i tre documenti di feature, `status.md` | 0,5 |
| | **Totale** | **6,25** |

**Margine dichiarato:** più o meno un giorno, e sta quasi tutto in due punti — la protezione del
modulo dal ridisegno (R-07), che è un problema di stato del DOM e non di codice, e la prova sul
telefono, che nella 003 ha prodotto due scoperte che hanno cambiato il codice. Non è escluso che ne
produca altre.

**Confronto:** la 003 era stimata in 3 giorni. Questa è più grande e il motivo è dichiarabile in una
riga: la 003 aggiungeva un trasporto sotto un servizio che restava lo stesso, questa aggiunge
**superficie** — corpi da leggere, comandi da autorizzare, stato da tenere in una pagina che finora
non ne aveva.

---

## 7. Milestones

| # | Milestone | Contenuto | Perché in quest'ordine |
|---|---|---|---|
| **M1** | **Il filo regge un corpo** | Lettura del corpo con i suoi limiti; `AccessToken` con due livelli. Nessuna funzione nuova visibile. | Sono le due fondamenta, e sono anche le due che toccano la sicurezza: vanno scritte e provate quando non c'è nient'altro da guardare. |
| **M2** | **Il server sa scrivere** | Rotte, validazione, aggiornamento a partire dalla riga esistente, allarmi, `409`. Provabile con `curl`, senza pagina. | Fa esistere la feature dove conta. Se qualcosa nel modello non torna, si scopre qui invece che dentro un modulo HTML. |
| **M3** | **L'app dà le chiavi** | Due indirizzi in `WebStatus`, due righe copiabili in `SezioneWebApp`, `ReminderApp` che passa le dipendenze nuove. | Serve a M4 (senza indirizzo completo non si prova la pagina) ed è il punto in cui l'utente vede per la prima volta che cosa sta ottenendo. |
| **M4** | **La pagina comanda** | Modulo di modifica e creazione, «fatto» e annullamento, convivenza con il ridisegno periodico, messaggi di errore in italiano. | È la parte più esposta a ripensamenti: farla per ultima significa farla una volta sola. |
| **M5** | **Prova sul campo** | Giro completo sopra TLS via `adb`; le tre operazioni su telefono vero con verifica che **la notifica arrivi all'ora nuova**; il `403` con il token di lettura; la corsa di R-04. | La 003 insegna che la prova sul dispositivo dice cose che i test su desktop non possono dire. |
| **M6** | **Documenti** | `CLAUDE.md` (la sezione `web/` cambia in tre punti: non è più in sola lettura, i token sono due, i corpi si leggono), i documenti di feature, `status.md`. | Le regole che governano questo codice stanno lì, e una regola non aggiornata è peggio di una assente. |

---

## 8. Decisioni da confermare

> **Aggiornamento 23/08/2026.** D-01, D-02 e D-03 sono **confermate dall'utente**. D-03 è stata
> decisa in una forma **diversa** da quella proposta qui sotto: vedi la riga, e la ragione in
> `phase-2-analysis.md` §C.8. Resta aperta D-04 per i tre limiti numerici nuovi.
>
> **Aggiornamento 23/08/2026 (secondo giro).** Tutte le decisioni sono chiuse — D-04 su 200 / 2000 /
> 1970–2100. Due nuove incidono su questo documento e vanno lette qui: **D-08** limita la scrittura
> all'**app aperta** sul telefono — ad app chiusa la porta torna a essere quella della 003, in sola
> lettura — e **D-09** stabilisce che in quel caso i comandi della pagina si vedono ma sono spenti,
> con la ragione scritta. Il prezzo è dichiarato in fase 3: US-002 conserva quasi tutto il suo
> valore, US-001 ne perde una parte. Quadro completo in `phase-3-implementation-plan.md` §3.

| ID | Domanda | Proposta | Perché non è stata decisa qui |
|---|---|---|---|
| **D-01** ✅ | Che cosa vuol dire «riapri», visto che la pagina mostra solo gli aperti? | **Annullare la completazione appena fatta**, finché la pagina non viene ricaricata: la riga resta visibile con un comando di annullamento. Nessuna vista dei completati. | L'alternativa — un elenco dei completati sul web — è una feature sua, con la sua interfaccia e il suo carico; questa proposta copre il caso reale (il tocco sbagliato) al costo di poche righe. |
| **D-02** ✅ | Forma delle rotte di scrittura. | Due sole: `POST /api/eventi` per creare, `PUT /api/eventi/{id}` per modificare — con «completato» fra i campi, così **anche il completamento è una modifica** e passa dallo stesso controllo ottimistico. | L'alternativa (rotte separate per ogni azione) moltiplica i rami del router e i punti da autorizzare. La proposta ne fa due, ma va guardata contro il codice in fase 2. |
| **D-03** ✅ | Quale indirizzo mostra per primo la schermata dell'app? | ~~Prima quello completo, sotto quello di lettura.~~ **Decisa al contrario:** si vede l'indirizzo di **sola lettura**, quello completo sta dietro una riga a scomparsa. | La proposta ordinava per frequenza d'uso, che è il criterio sbagliato: i due indirizzi si copiano entrambi di rado. Il criterio giusto è l'**asimmetria degli errori** — copiare per sbaglio quello completo regala il telecomando e non dà segnali, copiare per sbaglio quello di lettura si scopre in tre secondi. |
| **D-04** | Limiti sui valori accettati (lunghezza del titolo e della descrizione, intervallo delle date, anticipo massimo). | Da fissare in fase 2 leggendo che cosa fa oggi l'editor dell'app, così che le due strade non divergano. | Metterli qui a caso significherebbe inventare numeri; vanno ricavati dal codice esistente. |

---

## 9. Riepilogo

Si aggiungono tre operazioni — modifica, creazione, completamento — a una pagina che oggi sa solo
guardare, e si separa in due il potere di usarla. Non si aggiunge la cancellazione, perché è l'unica
cosa che dal browser non si potrebbe disfare. La feature è possibile perché la 003 ha cifrato il
filo, ed è economica perché la 002 aveva già lasciato aperte le porte giuste — un `id` nel payload,
un router smistato per metodo, un numero di versione in attesa di servire.

Il criterio che vale più di tutti gli altri è uno solo, ed è a metà del §4: **spostando l'orario dal
browser, la notifica sul telefono deve arrivare all'ora nuova.** Tutto il resto è contorno.
