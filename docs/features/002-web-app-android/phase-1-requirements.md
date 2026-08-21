# Feature: Web app locale su Android — Requisiti (Fase 1)

**Slug:** `web-app-android`
**Data:** 2026-08-21
**Stato:** Bozza in attesa di approvazione
**Progetto:** Promemoria (`it.agoldoni.reminder`) — Compose Multiplatform, Android + Desktop JVM, Kotlin 2.3.21 / Room KMP / MVVM
**Team:** 1 sviluppatore (Alberto Goldoni)
**Piattaforma interessata:** solo Android (il desktop resta fuori scope, vedi §2)

**Decisioni già prese dall'utente (input di questa fase):**

| Decisione | Scelta | Conseguenza principale |
|---|---|---|
| Ciclo di vita del server | **Solo con app in primo piano** | Nessun permesso nuovo, nessun foreground service, nessuna notifica persistente |
| Controllo dell'accesso | **Token nell'URL** | Chi non ha il token riceve `403`; nessun login, nessuna sessione da gestire |
| Grado di "PWA" | **Web app responsive + `manifest.json`, senza service worker** | Niente offline e niente installazione piena: è una conseguenza dei secure context, non una scelta di comodo |
| Stack | **Server HTTP scritto a mano + HTML/CSS/JS statici** | Zero dipendenze nuove nell'APK, zero toolchain JavaScript |
| Contenuto | **Sola lettura della vista principale** | Nessuna scrittura dal browser in questa iterazione |

---

## 1. Obiettivo e motivazione

Oggi i promemoria si leggono in due soli modi: aprendo l'app Android, oppure aprendo l'app
desktop Linux su una macchina che sia stata **installata e associata** al telefono. La
sincronizzazione punto-punto introdotta con la feature `001-desktop-linux` risolve bene il caso
«ho i miei due dispositivi e li tengo allineati», ma non tocca affatto il caso opposto e molto
più frequente: *«sono davanti a uno schermo qualsiasi sulla stessa rete e voglio dare
un'occhiata alla lista senza installare niente e senza prendere in mano il telefono»*.

Quello schermo può essere il PC di lavoro (dove installare un AppImage personale non è sempre
lecito), un portatile con un altro sistema operativo, un tablet, il computer di casa di
qualcun altro. In tutti questi casi manca l'app, manca l'associazione, e installarle per
leggere cinque righe è sproporzionato. Il browser, invece, c'è ovunque.

**Problema che risolve:**

- Nessun modo di consultare i promemoria da un dispositivo su cui l'app non è installata.
- L'associazione richiesta dalla sincronizzazione è un impegno permanente (scambio di chiavi,
  peer che resta in elenco): eccessivo per una consultazione occasionale.
- L'export ODS è a senso unico, produce un file da aprire a mano e fotografa un istante:
  non è una vista viva.

**Perché una porta *attivabile* e non sempre aperta:** i promemoria sono dati personali e la
rete locale non è un confine di fiducia — un Wi-Fi di ufficio, di casa condivisa o di un
albergo mette sullo stesso segmento decine di dispositivi altrui. Vale qui la stessa scelta
già fatta per `sync_enabled`: **spento di default**, si accende con un atto esplicito
dell'utente e si spegne da sé quando l'app lascia il primo piano.

**Perché «progressive» oggi vale meno di quanto sembri:** tutto ciò che rende «progressive» una
web app è dietro al *secure context* (HTTPS, oppure `localhost`), e una pagina servita su
`http://192.168.x.y` non lo è. Il service worker non si registra, quindi niente cache offline; e
non c'è nemmeno l'installazione, benché — verificato in fase di realizzazione — Chrome **non
pretenda più un service worker** per offrirla: a mancare è il contesto sicuro, non il service
worker. La conferma è che sullo stesso servizio raggiunto via `127.0.0.1`, che secure context lo è,
Chrome offre «Installa». Ciò che resta di realizzabile — e che copre l'obiettivo dichiarato «per
ora deve solo mostrare la vista principale» — è una **web app responsive con `manifest.json`
e icone**, aggiungibile alla schermata Home come scorciatoia. Questa feature consegna quella;
la PWA piena resta annotata come passo successivo in §8.

**Metriche di successo (verifica manuale, nessuna analytics):**

- [ ] Da un browser sulla stessa rete, digitando l'URL mostrato dall'app, la lista dei
      promemoria aperti compare in meno di 2 secondi.
- [ ] La lista mostrata nel browser coincide, riga per riga e nell'ordine, con quella mostrata
      dall'app in quel momento.
- [ ] Con l'interruttore spento, la porta rifiuta la connessione (`ECONNREFUSED`), non risponde
      con una pagina di errore: da fuori l'app deve sembrare che il servizio non esista.
- [ ] Una richiesta senza token, o con token sbagliato, riceve `403` e nessun dato.
- [ ] Mandando l'app in background la porta si chiude entro pochi secondi; riportandola in
      primo piano torna raggiungibile allo stesso indirizzo.
- [ ] Nessuna regressione: allarmi, sincronizzazione ed export si comportano come prima, e la
      dimensione dell'APK non cresce in modo apprezzabile (nessuna libreria nuova).

---

## 2. Scope

### Incluso

**A. Server HTTP minimale**
- HTTP/1.1 scritto a mano sopra `ServerSocket`, sullo stesso modello già collaudato da
  `SyncServer` in [SyncTransport.kt](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/sync/SyncTransport.kt):
  ciclo di `accept()` su `Dispatchers.IO`, una connessione che va male chiude solo se stessa.
- Sottoinsieme deliberatamente ristretto del protocollo: solo `GET`, `Connection: close`,
  nessun keep-alive, nessun chunked, nessun upload. Tutto il resto risponde `405`.
- Porta fissa e distinta dalle due già in uso — `SYNC_PORT` (47700) e la porta dell'istanza
  singola desktop (47653) — così i servizi non si contendono nulla e l'indirizzo resta
  indovinabile. *(Corretto in Fase 2: `CLAUDE.md` attribuisce erroneamente 47653 alla
  sincronizzazione.)*
- Il codice vive in `jvmSharedMain` — è `java.net` puro, funziona su entrambi i target JVM —
  ma **in questa feature viene cablato solo su Android**. Non costa nulla lasciare la porta
  aperta a un futuro riuso desktop; costerebbe riscriverlo dopo.

**B. Controllo dell'accesso a token**
- Token casuale generato a ogni accensione dell'interruttore e invalidato a ogni spegnimento:
  un indirizzo copiato ieri non funziona oggi.
- Lunghezza pensata per essere digitabile a mano (ordine di 8 caratteri) e sufficiente contro
  la scansione automatica una volta unita alla limitazione dei tentativi.
- Ogni richiesta senza token valido riceve `403` con corpo vuoto — nessuna distinzione fra
  «token assente» e «token sbagliato», che sarebbe un aiuto gratuito a chi tenta.
- Limitazione dei tentativi falliti per indirizzo di provenienza.
- Header di sicurezza sulle risposte: `Referrer-Policy: no-referrer` (il token è nell'URL e
  non deve viaggiare verso terzi), `Cache-Control: no-store`, `X-Content-Type-Options`.

**C. Contenuto servito — la vista principale, in sola lettura**
- Elenco dei promemoria **aperti** (`deleted = 0 AND completed = 0`), ordinati per data
  crescente: esattamente la query che già alimenta la schermata dell'app.
- Per ogni riga: titolo, data e ora dell'evento, orario della notifica calcolato con
  `advanceMinutes`, ed eventuale descrizione.
- Stessa codifica cromatica dell'app (scaduto / oggi / domani / più avanti), perché la vista
  serve a colpo d'occhio e due codici diversi per gli stessi dati sarebbero un difetto.
- Stato vuoto esplicito («Nessun evento»), come nell'app.
- Formattazione delle date in italiano, coerente con `formatDateTime`.

**D. Confezione web**
- Pagina unica, responsive, leggibile su telefono e su schermo grande.
- Tema chiaro/scuro seguendo `prefers-color-scheme`.
- `manifest.json` con nome, icone e `display: standalone`, così l'aggiunta alla schermata Home
  produce una scorciatoia dignitosa.
- Asset statici (HTML, CSS, JS, icone) imbarcati negli `assets/` dell'APK; nessun passo di
  build JavaScript.
- Aggiornamento periodico del contenuto via `fetch` su un endpoint JSON, così una scheda
  lasciata aperta non mostra dati vecchi.

**E. Integrazione nell'app Android**
- Nuovo interruttore persistente, **spento di default**, sul modello di `syncEnabled` in
  [AppSettings.kt](shared/src/commonMain/kotlin/it/agoldoni/reminder/platform/AppSettings.kt).
- Punto nell'interfaccia da cui accendere, spegnere e **leggere l'indirizzo completo** da
  digitare sull'altro dispositivo (indirizzo IP locale, porta, token) — senza questo l'utente
  non avrebbe modo di conoscerlo, come già accade per l'indirizzo mostrato dalla schermata di
  sincronizzazione.
- Aggancio al ciclo di vita: il server parte con l'app in primo piano e si ferma quando esce.
- Riuso di `siteAddress()`/`LocalAddress` per scegliere l'indirizzo da mostrare.

**F. Test e documentazione**
- Test automatici del server e del controllo d'accesso.
- Aggiornamento di `CLAUDE.md` con la nuova area e le sue trappole.

### Escluso (out of scope)

| Fuori scope | Motivo |
|---|---|
| **Qualsiasi scrittura dal browser** (creare, modificare, completare, eliminare) | L'utente ha chiesto esplicitamente «per ora deve solo mostrare la vista principale». La sola lettura riduce di molto la superficie d'attacco di una porta esposta in rete |
| Vista «Fatti», editor, schermata di sincronizzazione | Stessa ragione: una vista sola, fatta bene |
| **Service worker, cache offline, installazione PWA piena** | Impossibili su HTTP in rete locale (secure context). Vedi §5 R1 |
| **HTTPS / certificati** | Un autofirmato dà avvisi del browser e Chrome non tratta come sicuro un contesto con certificato non fidato: non sbloccherebbe né service worker né installazione |
| Accesso da fuori la rete locale (port forwarding, UPnP, relay, tunnel) | Cambierebbe completamente il modello di minaccia |
| **Foreground service / raggiungibilità ad app chiusa** | Decisione presa: costo in batteria e notifica permanente non giustificati per una consultazione occasionale |
| Attivazione su desktop | La richiesta riguarda le versioni Android. Il codice resta riusabile, ma nessun interruttore né UI desktop |
| Codice QR per l'URL | Richiederebbe un encoder QR (nessuna libreria in progetto). Digitare host, porta e token è accettabile; annotato in §8 |
| Multiutente, account, permessi differenziati | Un solo utente, un solo token |
| Localizzazione | Come tutto il resto dell'app: solo italiano |
| Push/WebSocket per l'aggiornamento in tempo reale | Il polling basta e costa un ordine di grandezza in meno di codice |

---

## 3. User Stories

**US-1 — Consultare senza installare**
> Come utente davanti a un computer sul quale non ho l'app, voglio aprire nel browser la lista
> dei miei promemoria, per non dover prendere in mano il telefono ogni volta che voglio
> ricordare cosa ho in programma.

**US-2 — Non lasciare la porta aperta**
> Come utente che si connette anche a reti non sue, voglio che la porta sia chiusa finché non
> la accendo io, per non esporre i miei promemoria a chiunque condivida il Wi-Fi.

**US-3 — Sapere che indirizzo digitare**
> Come utente voglio leggere sull'app l'indirizzo completo da digitare sull'altro dispositivo,
> per non dovere cercare l'indirizzo IP del telefono nelle impostazioni di sistema.

**US-4 — Non farsi leggere da chi passa di lì**
> Come utente voglio che chi conosce indirizzo e porta ma non il token non veda nulla, per non
> dovermi fidare del fatto che nessuno scansioni la rete.

**US-5 — Lasciare la scheda aperta**
> Come utente che tiene una scheda del browser aperta accanto al lavoro, voglio che la lista si
> aggiorni da sola, per non guardare senza accorgermene una fotografia vecchia di un'ora.

**US-6 — Leggere bene su qualunque schermo**
> Come utente che apre la pagina ora dal telefono e ora da un monitor grande, voglio una vista
> che si adatti e che usi gli stessi colori dell'app, per riconoscere a colpo d'occhio cosa
> scade oggi.

**US-7 — Sapere cosa resta acceso**
> Come utente voglio che chiudendo l'app la porta si chiuda davvero, per non restare col dubbio
> di aver lasciato un servizio attivo sul telefono.

---

## 4. Criteri di accettazione

### US-1 — Consultare senza installare
- [ ] Con l'interruttore acceso e l'app in primo piano, un browser sulla stessa rete che apre
      l'URL mostrato riceve una pagina HTML con la lista dei promemoria aperti.
- [ ] L'ordine delle righe è per data crescente, identico a quello dell'app.
- [ ] Ogni riga mostra titolo, data/ora dell'evento e orario della notifica; la descrizione,
      quando c'è, è visibile.
- [ ] Con nessun promemoria aperto la pagina mostra «Nessun evento» e non una lista vuota muta.
- [ ] I promemoria completati e quelli cancellati (tombstone) **non** compaiono.
- [ ] La pagina si apre correttamente su Chrome e su Firefox, desktop e mobile.

### US-2 — Non lasciare la porta aperta
- [ ] Al primo avvio dopo l'installazione l'interruttore è spento.
- [ ] Con l'interruttore spento nessun processo è in ascolto sulla porta: una connessione viene
      **rifiutata**, non accettata e poi chiusa.
- [ ] Spegnere l'interruttore chiude il socket e invalida il token corrente entro pochi secondi.
- [ ] Lo stato dell'interruttore sopravvive alla chiusura e alla riapertura dell'app.
- [ ] Accendere l'interruttore non richiede permessi Android nuovi rispetto a quelli già
      dichiarati.

### US-3 — Sapere che indirizzo digitare
- [ ] L'app mostra host, porta e token in una forma copiabile e digitabile senza ambiguità.
- [ ] L'indirizzo mostrato è quello della rete locale (non `127.0.0.1`, non `127.0.1.1`).
- [ ] Se il telefono non ha un indirizzo di rete locale utilizzabile, l'app lo dice invece di
      mostrare un indirizzo inservibile.
- [ ] L'indirizzo mostrato si aggiorna se cambia la rete mentre la schermata è aperta.

### US-4 — Non farsi leggere da chi passa di lì
- [ ] Richiesta senza token → `403`, corpo vuoto, nessun dato.
- [ ] Richiesta con token errato → `403` indistinguibile dal caso precedente.
- [ ] Il token cambia a ogni accensione: un URL salvato dalla sessione precedente riceve `403`.
- [ ] Dopo un numero prefissato di tentativi falliti dallo stesso indirizzo, le richieste
      successive vengono respinte senza nemmeno confrontare il token.
- [ ] Metodi diversi da `GET` → `405`.
- [ ] Un percorso costruito per uscire dalla cartella degli asset (`../`, percorsi codificati)
      non restituisce file fuori da quella cartella.
- [ ] Le risposte portano `Referrer-Policy: no-referrer` e `Cache-Control: no-store`.

### US-5 — Lasciare la scheda aperta
- [ ] Un promemoria creato sull'app compare nella pagina già aperta entro l'intervallo di
      aggiornamento, senza ricaricare a mano.
- [ ] Un promemoria completato o eliminato sull'app sparisce dalla pagina entro lo stesso
      intervallo.
- [ ] Se il server diventa irraggiungibile (app in background, interruttore spento), la pagina
      lo segnala invece di continuare a mostrare dati vecchi come se fossero freschi.
- [ ] L'aggiornamento periodico non accumula richieste sovrapposte se la rete è lenta.

### US-6 — Leggere bene su qualunque schermo
- [ ] La pagina è leggibile senza scorrimento orizzontale a 360 px di larghezza.
- [ ] Su schermo largo il contenuto non si stira a tutta larghezza in modo illeggibile.
- [ ] I quattro stati cromatici (scaduto, oggi, domani, più avanti) sono distinguibili e
      corrispondono a quelli dell'app.
- [ ] Con `prefers-color-scheme: dark` la pagina usa la variante scura.
- [ ] `manifest.json` è servito con il tipo MIME corretto e l'aggiunta alla schermata Home
      produce un'icona e un nome corretti.

### US-7 — Sapere cosa resta acceso
- [ ] Mandando l'app in background la porta smette di accettare connessioni.
- [ ] Riportando l'app in primo piano, con l'interruttore ancora acceso, la porta torna
      raggiungibile allo stesso host e alla stessa porta.
- [ ] Il token resta valido attraverso un ciclo background → primo piano (altrimenti l'utente
      dovrebbe ridigitarlo di continuo).
- [ ] Nessun ANR e nessun crash nel passaggio ripetuto fra primo piano e background.
- [ ] Terminando l'app dal gestore attività, il socket non resta appeso: la porta è di nuovo
      libera.

---

## 5. Rischi e dipendenze

### Rischi tecnici

**R1 — «Progressive» non è ottenibile su HTTP in rete locale** · *Certezza, non rischio*
Il secure context manca su `http://192.168.x.y`: niente cache offline e niente prompt di
installazione. *Precisazione emersa in realizzazione:* non è il service worker mancante a
impedire l'installazione — Chrome non lo richiede più — ma proprio il contesto non sicuro. *Mitigazione:* scelta già presa — si
consegna una web app responsive con manifest, e il documento lo dichiara apertamente invece di
promettere una PWA che non può funzionare. Il nome della feature non deve indurre ad aspettarsi
il funzionamento offline.

**R2 — Il token nell'URL lascia tracce** · *Medio*
Un token in query string finisce nella cronologia del browser, nei log di eventuali proxy e —
senza precauzioni — nell'header `Referer` verso siti terzi. *Mitigazione:* `Referrer-Policy:
no-referrer` su ogni risposta, token rigenerato a ogni accensione, nessun link uscente nella
pagina. Da valutare in Fase 2 se scambiare il token con un cookie di sessione al primo accesso
riuscito, ripulendo la query string con `history.replaceState`.

**R3 — Reti che isolano i client** · *Medio, non mitigabile*
Molte reti Wi-Fi ospiti e alcune reti aziendali applicano *AP isolation*: due dispositivi sullo
stesso SSID non si vedono. È lo stesso limite già noto alla sincronizzazione. *Mitigazione:*
nessuna tecnica possibile; la documentazione deve dirlo, e il messaggio d'errore lato app non
deve far sospettare un difetto dell'app.

**R4 — L'indirizzo IP cambia** · *Basso*
Con DHCP il telefono può cambiare indirizzo fra una sessione e l'altra, e cambia di sicuro
passando da Wi-Fi a hotspot o attivando una VPN. *Mitigazione:* l'app mostra l'indirizzo
corrente e lo aggiorna; nessun indirizzo viene mai memorizzato.

**R5 — Scelta dell'interfaccia su cui annunciarsi** · *Basso*
Con più interfacce attive (Wi-Fi, hotspot, VPN, `rmnet`) l'indirizzo «giusto» da mostrare è
ambiguo. *Mitigazione:* riuso della logica già scritta in `LocalAddress`, che il progetto ha
già dovuto affrontare per la sincronizzazione; se ne emergono limiti, si estende lì una volta
sola per entrambi i servizi.

**R6 — Un server HTTP scritto a mano è codice di sicurezza** · *Medio*
Parsing di richieste da rete, gestione dei percorsi, servizio di file: sono esattamente i punti
dove nascono path traversal, header injection e crash da input malformato. *Mitigazione:*
superficie ridotta al minimo (solo `GET`, nessun upload, elenco chiuso di percorsi serviti
invece di una mappatura libera sul filesystem), limiti espliciti su lunghezza della riga di
richiesta e numero di header, test dedicati agli input malformati. Il progetto ha già
precedenti in questo stile (HKDF, ODS, protocollo di sync) ed è la ragione per cui la scelta è
sostenibile — ma va trattata con lo stesso rigore.

**R7 — ANR e lavoro di rete sul thread sbagliato** · *Medio*
Il progetto ha già preso questa botta con la sincronizzazione: le chiamate bloccanti devono
stare su `Dispatchers.IO`. Un `accept()` o una query Room sul main thread chiude l'app.
*Mitigazione:* stessa struttura di `SyncServer` (scope dedicato, `Dispatchers.IO`), e nessun
percorso che parta da `viewModelScope` senza `withContext`.

**R8 — Doppio accesso concorrente al database** · *Basso*
Il server legge dal `EventDao` mentre l'app scrive e mentre la sincronizzazione applica lotti
remoti. *Mitigazione:* sole letture, Room serializza da sé; nessuna transazione lunga.

**R9 — Conflitto di porta** · *Basso*
La porta scelta potrebbe essere occupata da un'altra app. *Mitigazione:* l'errore va mostrato
all'utente in italiano e non fatto risalire come eccezione, coerentemente con `SyncOutcome`.

**R10 — Verificabilità** · *Medio*
Non c'è un modo comodo di provare a mano una porta esposta da un telefono, e l'ambiente di
verifica ha già i suoi attriti noti. *Mitigazione:* il grosso della logica (parsing, routing,
token, serializzazione) va scritto in modo da essere provabile in `jvmSharedTest` con il server
su porta effimera; sul dispositivo resta da verificare solo l'aggancio al ciclo di vita.
`adb forward` consente la prova dall'host senza dipendere dalla topologia della rete.

**R11 — Crescita dell'APK e nuove dipendenze** · *Nullo per costruzione*
La scelta «server a mano + asset statici» non aggiunge librerie. È un rischio solo se in corsa
si cede alla tentazione di introdurre Ktor o un framework JS: sarebbe un cambio di decisione,
non un dettaglio implementativo.

### Dipendenze

- **Nessuna dipendenza esterna nuova.** `kotlinx-serialization-json` è già in progetto e copre
  l'endpoint JSON; `java.net` copre il server.
- **Permesso `INTERNET`**: già dichiarato in
  [shared/src/androidMain/AndroidManifest.xml](shared/src/androidMain/AndroidManifest.xml) per la
  sincronizzazione. Da verificare in Fase 2 se serva altro per l'ascolto (atteso: no).
- **Dipendenza da codice esistente:** `EventDao.getActiveSortedAsc()`, `AppSettings`,
  `LocalAddress`, e il pattern di ciclo di vita di `SyncService`.
- **Dipendenza di prodotto:** la definizione di «vista principale» è quella di
  [EventListScreen.kt](shared/src/commonMain/kotlin/it/agoldoni/reminder/ui/list/EventListScreen.kt);
  se cambia lì, la pagina web va allineata a mano — non c'è condivisione di codice di
  presentazione.

---

## 6. Stima effort

Unità: giorni/uomo per uno sviluppatore che conosce la codebase.

| Area | Attività | Stima |
|---|---|---|
| **BE — server** | HTTP/1.1 minimale su `ServerSocket`: parsing riga di richiesta e header, limiti, routing, risposte, header di sicurezza, gestione errori, arresto pulito | 1,5 |
| **BE — accesso** | Generazione e ciclo di vita del token, confronto a tempo costante, limitazione dei tentativi, `403` uniformi | 0,5 |
| **BE — dati** | Endpoint JSON degli eventi aperti: modello di risposta, serializzazione, formattazione delle date lato server o lato client | 0,5 |
| **BE — asset** | Servizio degli asset statici dall'APK con elenco chiuso di percorsi e tipi MIME corretti | 0,5 |
| **FE** | HTML/CSS/JS a mano: layout responsive, quattro stati cromatici, tema chiaro/scuro, polling con gestione degli errori, `manifest.json`, icone | 1,5 |
| **Integrazione Android** | Interruttore in `AppSettings`, controller e aggancio al ciclo di vita del primo piano, punto di comando e stato nell'interfaccia (indirizzo, porta, token), messaggi d'errore in italiano | 1,5 |
| **Test** | Unitari in `jvmSharedTest` (routing, token, malformati, path traversal, serializzazione); verifica sul dispositivo del ciclo di vita e dell'accesso reale da un altro computer | 1,5 |
| **Documentazione** | `CLAUDE.md` (nuova area + trappole), documenti di feature, nota sui limiti PWA | 0,5 |
| | **Totale** | **8,0** |

**Intervallo realistico: 7–10 giorni/uomo.** Le voci più esposte a slittamento sono il server
scritto a mano (R6: la parte facile è farlo funzionare, quella lenta è renderlo robusto agli
input malformati) e la verifica sul dispositivo (R10).

Riferimento di taratura: la feature `001-desktop-linux`, molto più ampia, ha richiesto la
costruzione da zero di crittografia, protocollo e packaging. Questa è di un ordine di
grandezza inferiore e riusa quei pattern invece di inventarli.

---

## 7. Milestones

**M1 — Fondamenta del server (prerequisito di tutto)**
Server HTTP minimale in `jvmSharedMain`, avviabile su porta arbitraria, che risponde a un
percorso di prova. Ciclo di `accept()` su scope proprio, arresto pulito, una connessione
malformata che non abbatte il ciclo. Test su porta effimera.
*Fatto quando:* i test in `jvmSharedTest` passano e il server si avvia e si ferma cento volte
di fila senza lasciare socket appesi.

**M2 — Controllo dell'accesso**
Token, `403` uniformi, limitazione dei tentativi, header di sicurezza, `405` sui metodi non
previsti. Test dedicati, compresi gli input costruiti male.
*Fatto quando:* nessuna richiesta senza token valido ottiene un byte di contenuto.

**M3 — Endpoint dei dati**
Lettura da `EventDao`, modello di risposta, serializzazione JSON. Nessuna interfaccia utente
ancora: si verifica con `curl`.
*Fatto quando:* il JSON contiene esattamente i promemoria aperti, nell'ordine giusto, senza
tombstone né completati.

**M4 — Pagina web**
HTML/CSS/JS statici, layout responsive, stati cromatici, tema scuro, polling, stato vuoto,
segnalazione di server irraggiungibile. `manifest.json` e icone. Serviti dagli asset.
*Fatto quando:* aperta da un browser desktop puntato al server di prova, la pagina è
indistinguibile per contenuto dalla vista dell'app.

**M5 — Integrazione Android**
Interruttore persistente spento di default, avvio e arresto legati al primo piano, punto
nell'interfaccia da cui accendere e leggere l'indirizzo completo, errori in italiano.
*Fatto quando:* l'intero percorso funziona sul dispositivo, dall'accensione alla lettura da un
altro computer, e lo spegnimento chiude davvero la porta.

**M6 — Verifica sul campo**
Prova reale su rete domestica da un secondo dispositivo: apertura, aggiornamento automatico,
background/primo piano, token scaduto, rete cambiata. Verifica che sincronizzazione, allarmi ed
export non abbiano subito regressioni.
*Fatto quando:* tutti i criteri di §4 sono spuntati su dispositivo reale.

**M7 — Documentazione e chiusura**
`CLAUDE.md` aggiornato con l'area nuova e le sue trappole (secure context, token nell'URL,
primo piano soltanto, porta distinta sia da 47700 sia da 47653). Stato del piano chiuso.
*Fatto quando:* chi riprende il progetto fra sei mesi capisce dai documenti perché non c'è il
service worker senza doverlo riscoprire.

---

## 8. Note per le fasi successive

Decisioni rinviate alla Fase 2 e **chiuse il 2026-08-21** (dettaglio e conseguenze in
[phase-3-implementation-plan.md §3](phase-3-implementation-plan.md)):

- **Numero di porta** → **9888**, distinta da 47700 e 47653.
- **Dove sta l'interruttore nell'interfaccia** → **sezione dentro la schermata Sincronizzazione**;
  la barra della vista principale non si tocca.
- **Token in query string o cookie di sessione** → resta in query string per questa iterazione;
  da riaprire quando arriverà la scrittura (vedi R2 e l'ultimo punto qui sotto).
- **Formattazione delle date lato server o lato client** → **lato client**. Il costo è che orari
  e fasce cromatiche seguono il fuso del browser; il beneficio è che la fascia si aggiorna al
  passare dell'ora senza chiedere niente al server.
- **Intervallo di polling** → **si aggiorna solo quando qualcosa è cambiato**: richiesta
  condizionale con impronta del contenuto e `304`, controllo ogni 30 s sospeso a scheda nascosta.
- **Collocazione degli asset** → risorse di `:shared` (`androidMain/resources`), lette dal
  classloader, subordinatamente alla verifica bloccante T-03.
- **La sola lettura è un traguardo o un primo passo?** → **primo passo**: il modello di scambio
  porta già un identificatore stabile e un numero di versione, e lo smistamento per metodo è
  strutturato per accogliere la scrittura senza riscritture.

Idee esplicitamente rimandate a feature future, annotate qui per non perderle:

- Codice QR per l'URL, così non si digita nulla.
- **Scrittura dal browser** (completare un promemoria, crearne uno) — non un forse ma un
  seguito previsto, vedi la decisione D-07.
- PWA piena, se e quando esisterà un modo praticabile di ottenere un secure context in rete
  locale.
- Attivazione della stessa porta sul desktop, dove il vincolo del primo piano non esiste.
