# Feature: L'indirizzo diventa fisso — Requisiti (Fase 1)

**Slug:** `indirizzo-fisso-web-app`
**Data:** 2026-08-23
**Stato:** Bozza in attesa di approvazione
**Progetto:** Promemoria (`it.agoldoni.reminder`) — Compose Multiplatform, Android + Desktop JVM, Kotlin 2.3.21 / Room KMP / MVVM
**Team:** 1 sviluppatore (Alberto Goldoni)
**Piattaforma interessata:** solo Android, come la 002, la 003, la 004 e la 005 (il codice resta in `jvmSharedMain` e provabile su desktop)
**Estende:** [002-web-app-android](../002-web-app-android/) (la porta e il token), [003-https-web-app](../003-https-web-app/) (il filo cifrato), [004-scrittura-web-app](../004-scrittura-web-app/) (i due token), [005-aggiornamento-mirato-web-app](../005-aggiornamento-mirato-web-app/) (l'attesa lunga)

**Decisioni già prese dall'utente (input di questa fase):**

| Decisione | Scelta | Conseguenza principale |
|---|---|---|
| Dove vive il permesso | **In un JWT conservato dal browser**, non nell'URL | L'indirizzo diventa `https://IP:9888/` e si può mettere fra i segnalibri |
| Formato del segreto | **JWT HS256** firmato con una chiave persistita | La scadenza sta dentro al token; in cambio, un verificatore scritto a mano |
| Durata | **30 giorni, secchi** | Un indirizzo consegnato va riconsegnato una volta al mese; niente rinnovo silenzioso |
| Revoca | **Globale**, un solo comando | Rotazione della chiave di firma: cade tutto insieme, senza liste di `jti` negati |

---

## 1. Obiettivo e motivazione

Oggi il permesso di aprire la web app sta **dentro l'indirizzo**:

```
https://192.168.1.42:9888/?t=k7mq3wzp
```

Otto caratteri in coda all'URL, due token diversi — uno che guarda, uno che comanda — coniati in
`AccessToken.rigenera()` a ogni `enable()` e buttati via in `disable()`. Il modello regge, e regge
per una ragione dichiarata: quaranta bit di segreto contro dieci tentativi al minuto per indirizzo
IP. Ma paga tre prezzi che si vedono tutti nell'uso quotidiano.

**Primo: l'indirizzo non è un segnalibro.** Il token muore a ogni spegnimento dell'interruttore, e
non solo: muore anche quando Android uccide il processo e l'app riparte, perché `apri()` conia una
coppia nuova se non ne trova una in memoria — e in memoria non c'è niente che sopravviva al
processo. Chi ha messo la pagina fra i preferiti sul tablet in cucina se la ritrova rotta senza aver
fatto niente, e per rimetterla in riga deve prendere il telefono, aprire la schermata
Sincronizzazione, copiare l'indirizzo nuovo e riportarlo di là. Il testo dell'app oggi lo dice
apertamente: «Il codice cambia ogni volta che riaccendi».

**Secondo: il segreto viaggia nella riga di richiesta.** Finisce nella cronologia del browser, resta
in bella vista nella barra dell'indirizzo mentre qualcuno guarda lo schermo da dietro, comparirebbe
in qualunque log di qualunque intermediario. Il progetto ne è consapevole e ha già speso una difesa
per la falla peggiore — `Referrer-Policy: no-referrer`, senza il quale il token uscirebbe
nell'header `Referer` di ogni richiesta verso l'esterno — ma è una toppa su una scelta, non la
scelta giusta.

**Terzo: non esiste la revoca.** Ho dato l'indirizzo di sola lettura a qualcuno e me ne pento:
l'unica cosa che posso fare è spegnere l'interruttore, che chiude la porta **a tutti**, me compreso,
e mi costringe a riconsegnare l'indirizzo a ogni dispositivo che lo aveva. Non c'è nessun gesto che
significhi «quello che ho dato non vale più, quello che uso io sì».

I tre prezzi hanno la stessa radice: **l'indirizzo è la credenziale**. Finché è così, o l'indirizzo
è stabile e la credenziale non scade mai, o la credenziale ruota e l'indirizzo si rompe. Non se ne
esce restando dentro l'URL.

### La forma della soluzione

Si separa **dove si va** da **che cosa si è autorizzati a fare**:

```
indirizzo    https://192.168.1.42:9888/          ← fisso, va nei segnalibri
credenziale  Authorization: Bearer eyJhbGci…     ← conservata dal browser, mai nell'URL
```

Il token si consegna **una volta sola**, nel frammento dell'indirizzo che l'app mostra
(`https://IP:9888/#a=<jwt>`). Il frammento non viene mai spedito al server: la pagina lo legge, lo
mette in `localStorage`, ripulisce la barra dell'indirizzo, e da lì in poi l'URL è quello nudo.

**Perché un JWT e non un token opaco più lungo.** Un segreto casuale da 160 bit scritto su file
avrebbe dato lo stesso URL fisso con meno codice, ed è stato considerato. La scelta è caduta sul
JWT per due cose che porta con sé: la **scadenza dentro al token** (il server non deve tenere una
data per ogni segreto emesso) e lo **spazio per claim futuri** — un token per dispositivo, con
un'etichetta, è la naturale continuazione di questa feature e con un token opaco andrebbe costruita
da zero. Il costo va dichiarato e non nascosto: un verificatore JWT è **codice di sicurezza scritto
a mano**, con le sue trappole note, ed è per questo che §4 gli dedica dei criteri di accettazione
suoi.

### Perché ha senso adesso

La 005 ha appena reso la pagina qualcosa che **si lascia aperta**: non interroga più a intervalli,
aspetta, e una modifica fatta sul telefono compare in un decimo di secondo. Una pagina fatta per
restare aperta e un indirizzo che si rompe a ogni riavvio del processo sono due cose che si
contraddicono, e la contraddizione si nota tanto di più ora che la pagina funziona bene.

### Il vincolo che decide la forma

**Il browser che apre `https://IP:9888/` non può mandare un header prima di aver caricato il
JavaScript.** È il vincolo da cui discende tutto il resto: la pagina `/` deve diventare servibile
**senza credenziali**, perché è il guscio che poi si autentica. Non è una rinuncia: `index.html` non
contiene nessun promemoria, è markup vuoto, e la 002 aveva già accettato e dichiarato lo stesso
prezzo per `/app.css` — «una richiesta senza token a `/app.css` rivela che il servizio è acceso».
Il controllo d'accesso resta intero **dove stanno i dati**, cioè su `/api/eventi`.

---

## 2. Scope

### Incluso

**Lato telefono**

1. Emissione di JWT firmati HS256 con `p` (permessi: lettura o scrittura), `iat`, `exp` a 30 giorni
   e una versione di formato.
2. Una chiave di firma da 32 byte **persistita su disco**, che nasce una volta sola e sopravvive al
   riavvio del processo e allo spegnimento dell'interruttore.
3. Verifica del token da `Authorization: Bearer`, con la regola «firma prima, payload poi»
   (§4, AC-JWT).
4. Distinzione fra `401` (non autenticato: assente, malformato, firma sbagliata, scaduto, bloccato)
   e `403` (autenticato ma di sola lettura su una scrittura).
5. `/` servita senza credenziali; `/api/eventi` protetta come e più di prima.
6. Un comando **«Revoca gli accessi consegnati»** nella schermata, che ruota la chiave di firma.
7. `disable()` **non invalida più** i token: chiude la porta e basta.
8. Gli indirizzi mostrati dall'app diventano `https://host:porta/#a=<jwt>`, coniati freschi a ogni
   apertura della porta.

**Lato browser**

9. Raccolta del token dal frammento, conservazione in `localStorage`, pulizia della barra
   dell'indirizzo con `history.replaceState`.
10. Invio del token come header su ogni richiesta — letture (compresa l'attesa lunga della 005) e
    scritture.
11. Stato «non ho nessun token»: messaggio che rimanda al telefono, **e nessun giro di rete**.
12. Stato «il mio token non vale più» (`401`): token conservato cancellato, messaggio esplicito,
    **ciclo fermato**.

### Escluso (out of scope)

| Fuori | Perché |
|---|---|
| **Rinnovo silenzioso a scorrimento** (token fresco in un header di risposta a metà vita) | Renderebbe la scadenza invisibile a chi usa la pagina spesso, cioè trasformerebbe «30 giorni» in «30 giorni di inattività». Decisione esplicita dell'utente: la scadenza deve essere secca. Resta l'estensione più ovvia se il rinnovo mensile darà fastidio |
| **Un token per dispositivo, con etichetta e revoca singola** | È la continuazione naturale — e il `jti` nel payload le lascia la porta aperta — ma vuole una lista di token emessi, una schermata che li elenca e una consegna per ogni dispositivo. Feature a sé |
| **Chiavi asimmetriche (RS256/ES256)** | Emittente e verificatore sono lo stesso processo sullo stesso telefono: una chiave pubblica non ha nessuno a cui servire |
| **Una libreria JWT** | Coerente con la linea 002-005: ODS a mano, HKDF a mano, DER a mano. Le primitive ci sono già in `Crypto.kt` e una dipendenza in più sull'APK non si giustifica per centocinquanta righe |
| **Compatibilità all'indietro su `?t=`** | I token della 002/004 muoiono già a ogni riaccensione: non c'è nessun client vivo da non rompere, e due modi di presentare la stessa credenziale sono uno di troppo |
| **Cookie invece di `localStorage`** | Un cookie viaggia da solo su ogni richiesta, e questo riapre il CSRF che l'header chiude gratis |
| **Nome mDNS al posto dell'indirizzo IP** | L'indirizzo resta fisso solo finché il telefono tiene lo stesso IP. È un limite vero (§5, R6) ma è un'altra feature |
| **Installazione come PWA** | Il vincolo che la blocca è il certificato non fidato (003), e questa feature non lo tocca. Ne toglie *uno* dei due ostacoli, non quello decisivo |
| **Cancellazione dal browser** | Resta esclusa come nella 004 e nella 005 |
| **Desktop** | La web app è cablata solo da Android |

---

## 3. User Stories

**US-1 — L'indirizzo si mette fra i segnalibri**
> Come utente con un tablet fisso in cucina, voglio salvare l'indirizzo della pagina una volta sola e
> ritrovarlo funzionante domani, per non dover riprendere il telefono ogni volta che l'app si è
> riavviata.

**US-2 — Spegnere e riaccendere non rompe niente**
> Come utente che spegne la porta la notte e la riaccende la mattina, voglio che i browser a cui ho
> già dato l'indirizzo continuino a funzionare, perché l'interruttore serve a chiudere la porta, non
> a cambiare le serrature.

**US-3 — Posso togliere un accesso che ho dato**
> Come utente che ha passato l'indirizzo di sola lettura a qualcuno e se n'è pentito, voglio un
> comando che lo invalidi, per non dover scegliere fra tenerlo e spegnere la web app anche a me.

**US-4 — Il codice non è più in bella vista**
> Come utente che apre la pagina davanti ad altre persone, voglio che nella barra dell'indirizzo non
> ci sia niente da rubare, per non dover coprire lo schermo o rigenerare il token dopo.

**US-5 — Quando l'accesso non vale più, lo capisco**
> Come utente che apre un segnalibro vecchio di due mesi, voglio leggere che l'indirizzo è scaduto e
> che va riaperto dal telefono, invece di vedere una pagina vuota o un errore numerico.

**US-6 — Chi guarda e chi comanda restano due**
> Come utente che presta la pagina a qualcun altro, voglio continuare ad avere due indirizzi
> distinti, uno che guarda e uno che comanda, perché è la sola cosa che rende prestabile il primo.

**US-7 — Un token contraffatto non entra**
> Come sviluppatore, voglio che il verificatore controlli la firma **prima** di guardare qualunque
> cosa ci sia scritta dentro al token, per non aggiungere all'app le trappole classiche di JWT
> (`alg: none`, confusione fra algoritmi) proprio mentre si chiude un'altra debolezza.

---

## 4. Criteri di accettazione

### US-1 — Segnalibro

- [ ] Aperto l'indirizzo con il frammento, la barra mostra `https://IP:9888/` — senza `#`, senza `?`.
- [ ] Ricaricando `https://IP:9888/` a mano (nessun frammento) la lista compare.
- [ ] Chiuso il browser e riaperto il segnalibro, la lista compare.
- [ ] Ucciso il processo dell'app (`adb shell am force-stop`) e riaperta l'app, il segnalibro
      continua a funzionare.

### US-2 — L'interruttore non revoca

- [ ] `disable()` seguito da `enable()`: il token consegnato prima continua a valere.
- [ ] Un test dichiara esplicitamente che spegnere non invalida.

### US-3 — Revoca

- [ ] Nella schermata esiste un comando di revoca, con conferma.
- [ ] Dopo la revoca, ogni token consegnato prima riceve `401`.
- [ ] Dopo la revoca la schermata mostra **subito** gli indirizzi nuovi, senza dover spegnere e
      riaccendere.
- [ ] Il testo della schermata dice che spegnere non revoca, e che la revoca è questo comando.

### US-4 — Niente segreto nell'URL

- [ ] Il token non compare mai in una riga di richiesta HTTP: né in `path`, né in `query`.
- [ ] `?t=<qualunque cosa>` non autentica più: `401`.

### US-5 — Scadenza leggibile

- [ ] Con un token scaduto la pagina mostra un messaggio in italiano che rimanda al telefono.
- [ ] La pagina **smette di chiedere** invece di ritentare ogni cinque secondi.
- [ ] Il token conservato viene cancellato, così un ricaricamento non ripete lo stesso errore.
- [ ] La schermata dell'app dichiara fino a quando vale l'indirizzo che sta mostrando.

### US-6 — Due indirizzi

- [ ] L'indirizzo di sola lettura non mostra i comandi e riceve `403` su una scrittura.
- [ ] Il `403` di sola lettura e il `401` di token invalido restano **due risposte diverse**, e la
      pagina dice due cose diverse.
- [ ] L'indirizzo che comanda resta dietro un tocco in più nella schermata, come oggi.

### AC-JWT — Il verificatore (US-7)

- [ ] La firma si ricalcola sui **byte grezzi** dei primi due segmenti e si confronta a tempo
      costante.
- [ ] L'header del token non viene **mai** letto: nessun codice sceglie l'algoritmo da lì.
- [ ] Un token con `{"alg":"none"}` e firma vuota è rifiutato.
- [ ] Un payload manomesso, una firma manomessa, un segmento mancante, un segmento non base64url,
      un token firmato con un'altra chiave, una `v` sconosciuta e un token scaduto di un millisecondo
      danno tutti «non valido» — e **nessuno** di essi lancia un'eccezione.
- [ ] La soglia dei dieci tentativi al minuto per indirizzo IP resta in piedi.
- [ ] Un token valido ma insufficiente non consuma tentativi (regola della 004, da non perdere).

---

## 5. Rischi e dipendenze

| # | Rischio | Impatto | Mitigazione proposta |
|---|---|---|---|
| **R1** | **Il verificatore JWT è codice di sicurezza scritto a mano.** `alg: none`, confusione fra algoritmi, padding base64url, segmenti di lunghezza inattesa: sono trappole vecchie e documentate, e le si sta reintroducendo in un progetto che finora aveva scritto a mano solo HKDF (verificato contro i vettori RFC) e DER (dichiarato «codice ordinario, non codice di sicurezza») | **Alto** | Un'unica regola strutturale — *firma prima, payload poi, header mai* — scritta nel sorgente come invariante, non come commento di cortesia. Test avversariali dedicati (AC-JWT). Nessun ramo che dipenda dal contenuto dell'header |
| **R2** | **`/` diventa pubblica.** Chi bussa alla porta senza credenziali ora riceve markup invece di un `403` | Basso | È il vincolo di §1 e non è aggirabile. `index.html` non contiene dati; il prezzo è lo stesso già dichiarato per `/app.css`. Da scrivere in `CLAUDE.md`, non da lasciare implicito |
| **R3** | **Il token vive più a lungo.** Prima moriva a ogni riaccensione, ora dura trenta giorni. Un dispositivo prestato o rubato resta autorizzato | Medio | È il compromesso richiesto, non un difetto: la revoca globale (US-3) è la contropartita, e `exp` mette comunque una fine. Il testo della schermata deve dirlo, altrimenti l'utente crede ancora che spegnere basti |
| **R4** | **`localStorage` può non esserci** o lanciare: modalità privata, dati del sito bloccati, contesti particolari | Medio | Ogni accesso in `try/catch`, con ripiego su una variabile in memoria: la pagina deve funzionare almeno per la sessione corrente, e il frammento è appena stato letto |
| **R5** | **Rottura dichiarata dei client esistenti.** `?t=` smette di funzionare | Basso | Non c'è nessun client vivo: i token attuali muoiono già a ogni riaccensione. Va comunque scritto nel documento come rottura voluta, non scoperta |
| **R6** | **«Fisso» è vero solo finché l'IP è fisso.** Cambiata la rete, cambia l'origine, e con essa il `localStorage`: il token conservato resta ma sotto l'origine vecchia | Medio | Fuori scope risolverlo (serve mDNS), dentro scope **non peggiorarlo**: il token non deve essere legato all'indirizzo, così una riconsegna sulla rete nuova basta e avanza. Da dichiarare all'utente |
| **R7** | **`java.util.Base64` è esattamente al `minSdk`.** Esiste dall'API 26, che è il minimo di questo progetto: sulla carta va bene, ma è lo stesso genere di confine su cui il progetto si è già bruciato due volte (`KeyStore.getDefaultType()` nella 003, `readNBytes` nella 004) | Medio | Presidio strumentale sul dispositivo, nello spirito di `CorpoPiattaformaTest`: coniare e verificare un JWT su emulatore. `HmacSHA256` è già provato dalla sincronizzazione |
| **R8** | **Il `401` fa girare a vuoto il ciclo della 005.** Il client oggi, su un rifiuto in lettura, mostra un avviso e continua: con l'attesa lunga vuol dire una richiesta ogni cinque secondi contro una soglia di dieci al minuto | Medio | Sul `401` il ciclo si **ferma**. È un ramo nuovo in `app.js`, va scritto e provato: senza, l'utente vedrebbe l'avviso cambiare da «scaduto» a nulla e ritorno |
| **R9** | **La chiave di firma rigenerata per sbaglio** butta fuori ogni browser senza che nessuno abbia toccato niente — ed è esattamente il difetto che `CertificateStore` documenta per il certificato, con la stessa causa (`resume()` a ogni rotazione dello schermo) | **Alto** | Stessa forma della soluzione già collaudata: guardia in **un punto solo**, cache in memoria, e l'unica condizione che fa nascere una chiave nuova è che non ce ne sia già una (o una revoca esplicita) |
| **R10** | **Il `403` e il `401` raccontano qualcosa a chi sonda?** Il progetto ha una regola esplicita: `NEGATO` e `BLOCCATO` devono dare la stessa risposta sul filo | Basso | La regola resta rispettata: le quattro condizioni «non autenticato» restano indistinguibili fra loro. Il `403` compare **solo** a chi ha già un token valido, e non gli dice niente che non sappia già |
| **R11** | **Gli indirizzi diventano lunghi** (~200 caratteri contro ~40) e più difficili da leggere a occhio nella schermata | Basso | Si copiano, non si leggono. In compenso i due indirizzi ora differiscono per **tutta** la coda invece che per otto caratteri, il che rende meno probabile lo scambio che la 004 aveva mitigato con il tocco in più |

**Dipendenze**

- **Nessuna dipendenza nuova.** `Hkdf.hmac`, `constantTimeEquals` e `randomBytes` esistono già in
  `sync/Crypto.kt`; `kotlinx.serialization` è già in uso in `WebPayload.kt`.
- Verifica finale sul **dispositivo reale**, non solo sull'emulatore, per US-1 (sopravvivenza al
  `force-stop`) e R7.

---

## 6. Stima effort

| Area | Giorni/uomo | Che cosa comprende |
|---|---|---|
| Backend (Kotlin, `jvmSharedMain`) | **1,5** | `Jwt.kt` (firma, verifica, base64url); `ChiaveFirma.kt` con guardia e revoca; riscrittura di `AccessToken`; `Router` su header e `401`/`403`; `WebService` con coniatura e `revoke()` |
| Frontend (`app.js`) | **1,0** | Raccolta dal frammento e pulizia dell'URL; `localStorage` difeso; header su tutte le richieste; i due stati nuovi (nessun token, token morto); ciclo fermato sul `401` |
| Interfaccia (Compose) | **0,5** | `revoke()` sul controller e sul ViewModel; comando di revoca con conferma; `validoFinoA`; tre testi da riscrivere |
| Test | **1,5** | `JwtTest` avversariale; `ChiaveFirmaTest`; `AccessTokenTest` riscritto; `RouterTest` esteso; presidio strumentale su dispositivo |
| Verifica sul dispositivo | **0,5** | US-1 con `force-stop`, US-2, US-3, R7 |
| Documentazione | **0,5** | `CLAUDE.md` (la sezione `web/` va corretta in profondità, non estesa) + `status.md` |
| **Totale** | **≈ 5,5** | |

Margine consigliato: **+0,5 giorni** su R1. Un test avversariale che scopre un buco nel verificatore
è tempo speso bene; scoprirlo dopo, no.

---

## 7. Milestones

| # | Milestone | Esito verificabile | Blocca |
|---|---|---|---|
| **M1** | `Jwt.kt` e `JwtTest` | Il verificatore regge i casi avversariali di AC-JWT, **prima** che qualcuno gli affidi una porta | tutto |
| **M2** | `ChiaveFirma.kt` e il suo test | La chiave nasce una volta, sopravvive alla riapertura, cambia solo su revoca | M3 |
| **M3** | `AccessToken` e `Router` sul nuovo schema, `/` pubblica, `401`/`403` | `curl` senza header → `401`; con Bearer di lettura → `200`; `PUT` con quello di lettura → `403`; `?t=` → `401` | M4 |
| **M4** | `WebService` + `WebStatus` + coniatura degli indirizzi | La schermata mostra due indirizzi con il frammento; spegnere e riaccendere non li invalida | M5 |
| **M5** | `app.js`: raccolta, conservazione, header, i due stati nuovi | US-1 verde nel browser: l'indirizzo nudo funziona dopo un ricaricamento | M6 |
| **M6** | Revoca in interfaccia e testi | US-3 verde; nessun testo dell'app dichiara più il comportamento vecchio | M7 |
| **M7** | Verifica sul dispositivo e documentazione | `force-stop` superato; `CLAUDE.md` corretto; `status.md` chiuso | — |

**M1 per prima, e non è ordine di comodo.** Tutto il resto della feature dà per scontato che un
token contraffatto non entri. Se il verificatore va rifatto, va rifatto prima che quattro file lo
chiamino.

---

## Domande aperte per la Fase 2

1. ~~**Dove sta la chiave di firma?**~~ → **Deciso: accanto al certificato in `filesDir/web-tls/`.**
   È già la cartella dei segreti del server web, protetti dai permessi del file e non da una
   cifratura a riposo; un secondo posto per un secondo segreto della stessa natura sarebbe un posto
   in più da ricordarsi di cancellare.
2. ~~**Il payload minimo.**~~ → **Deciso: `v`, `p`, `iat`, `exp`. Niente `jti`.**
   La revoca è globale (rotazione della chiave) e non ha nulla da cui distinguere un token
   dall'altro: un identificatore che nessuno legge sarebbe peso morto in ogni indirizzo consegnato.
   Il giorno della revoca per dispositivo si aggiunge insieme alla lista che lo userebbe, e il campo
   `v` è lì apposta per non doverlo fare in silenzio.
3. **`tokenRichiesto` resta o sparisce?** Con `/` pubblica, tutti gli asset lo sono: un campo sempre
   `false` è un meccanismo che documenta una decisione, o una bugia in attesa?
4. **`WebStatus` porta il token o l'indirizzo già composto?** Oggi porta i due token e compone gli
   indirizzi in `commonMain`. Con un JWT dentro, i campi `tokenLettura`/`tokenScrittura` cambiano di
   natura, e il nome va rivisto con essi.
5. **`validoFinoA`: una data nello stato, o solo «30 giorni» nel testo?** La prima è più utile e
   costa un campo; da pesare contro il rumore nella schermata.
6. **R8: dove si ferma esattamente il ciclo?** `fermato = true` è la variabile della 005 usata dalla
   visibilità della scheda: riusarla o affiancarne una seconda va deciso leggendo il file.
7. **Il presidio strumentale (R7) va in un test nuovo o dentro `CorpoPiattaformaTest`?**
