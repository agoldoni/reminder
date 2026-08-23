# Feature 006 — Analisi tecnica della codebase (Fase 2)

**Data:** 2026-08-23
**Piano di riferimento:** [phase-1-requirements.md](phase-1-requirements.md)
**Base di codice esaminata:** `main` a `0d2de02` (versione 2.4.2)

**Decisioni prese dall'utente fra la Fase 1 e questa:**

| # | Domanda della Fase 1 | Scelta |
|---|---|---|
| 1 | Dove sta la chiave di firma | **Accanto al certificato, in `filesDir/web-tls/`** |
| 2 | Che cosa c'è nel payload | **Il minimo: `v`, `p`, `iat`, `exp`. Niente `jti`** |

Le domande 3-7 erano da chiudere leggendo il codice: le risposte, con le evidenze, sono in §G.

---

## A. File coinvolti

### Nuovi

| File | Perché |
|---|---|
| `shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/Jwt.kt` | Firma e verifica. Sta in `jvmSharedMain` come tutto il resto della web app e come `sync/Crypto.kt`: `javax.crypto` c'è su entrambi i target, quindi niente `expect`/`actual` |
| `shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/ChiaveFirma.kt` | Il segreto persistito e la sua unica guardia. Fratello di `CertificateStore.kt`, stessa cartella su disco e stessa forma in memoria |

### Modificati

| File | Modifica | Motivazione |
|---|---|---|
| `web/AccessToken.kt` | Riscrittura di `verifica`; via `rigenera`/`invalida`/`CoppiaToken`; entra `conia(permessi)` | È il file che incarna il modello vecchio: alfabeto di 32 simboli, otto caratteri, coppia in memoria |
| `web/Router.kt` | Credenziale dall'header; `401` accanto a `403`; `PARAMETRO_TOKEN` sparisce | Riga 72: `token.verifica(request.query[PARAMETRO_TOKEN], provenienza)` è l'unico punto d'ingresso del controllo d'accesso — un punto solo da cambiare |
| `web/StaticAssets.kt` | `/` passa a `tokenRichiesto = false` | Riga 34. È il vincolo di §1 della Fase 1 |
| `web/WebService.kt` | Costruisce `ChiaveFirma`; `enable`/`apri` coniano invece di rigenerare; `disable` non invalida; nuovo `revoke()` | Righe 103, 116, 152, 179-180: sono i quattro punti che oggi legano il token al ciclo di vita dell'interruttore |
| `commonMain/web/WebServerController.kt` | `revoke()` sull'interfaccia; `indirizzo()` compone il frammento; nuovo `validoFinoA` | Righe 76-81: la composizione dell'URL vive qui, in un punto solo, e va cambiata qui |
| `webAssets/web/app.js` | Raccolta dal frammento, `localStorage`, header su ogni `fetch`, due stati nuovi, ciclo fermato sul `401` | Righe 64, 500, 613: le tre occorrenze del token, tutte come parametro di query |
| `commonMain/ui/web/WebViewModel.kt` | `revoca()` | Riespone il controller, come già fa per `accendi`/`spegni` |
| `commonMain/ui/web/SezioneWebApp.kt` | Comando di revoca con conferma; tre testi da riscrivere | Righe 102-103 («Il codice cambia ogni volta che riaccendi»), 84 e 147-148 dichiarano il comportamento vecchio |
| `androidMain/web/WebServerService.kt` | Solo il commento su `START_NOT_STICKY` | Vedi §E, R12: la **motivazione** scritta nel sorgente smette di essere vera, la scelta no |
| `androidApp/.../ReminderApp.kt` | Solo il commento alle righe 37-40 | Dice «ogni rotazione dello schermo rigenererebbe il token»: dopo questa feature non è più quello il rischio. **Nessuna modifica al cablaggio** (vedi §F) |
| `CLAUDE.md` | Sezione `web/`, correzione in profondità | Sei paragrafi descrivono un meccanismo che non esisterà più |

---

## B. Contratti e interfacce da modificare

### B.1 — Il contratto HTTP (rottura dichiarata)

| | Oggi | Dopo |
|---|---|---|
| Dove sta la credenziale | `?t=<8 caratteri>` | `Authorization: Bearer <jwt>` |
| `GET /` senza credenziale | `403` | **`200`** (guscio vuoto) |
| `GET /api/eventi` senza credenziale | `403` | **`401`** |
| Token sbagliato / scaduto / bloccato | `403` | **`401`** |
| Token di lettura su `POST`/`PUT` | `403` | `403` (invariato) |
| `?t=<qualunque cosa>` | autentica | **ignorato** → `401` |

**È una rottura, e va dichiarata invece che scoperta.** Non ha però nessun client vivo da rompere:
`WebService.apri()` alla riga 152 fa `if (token.coppia == null) token.rigenera()`, e `corrente` in
`AccessToken` è un campo `@Volatile` in memoria — quindi **ogni riavvio del processo conia già oggi
una coppia nuova**. Nessun indirizzo consegnato con la 004 sopravvive abbastanza da poter essere
rotto da qui.

`WEB_PAYLOAD_VERSION` **non cambia**: il corpo di `/api/eventi` resta identico, campo per campo. Ciò
che cambia è chi ha il diritto di riceverlo.

### B.2 — `WebStatus` (commonMain)

```kotlin
val tokenLettura: String?     // stesso nome, contenuto diverso: un JWT invece di 8 caratteri
val tokenScrittura: String?
val validoFinoA: Long?        // NUOVO — `exp` del token mostrato, in millisecondi

// e la composizione, riga 78:
"https://$host:$port/?t=$token"   →   "https://$host:$port/#a=$token"
```

I nomi dei due campi **restano**: contengono ancora un token, e rinominarli muoverebbe una decina di
righe di test senza cambiare niente. `validoFinoA` è **uno solo** per i due token, perché i due si
coniano nello stesso istante e con la stessa durata: due campi suggerirebbero una differenza che non
c'è.

### B.3 — `WebServerController`

```kotlin
fun revoke()   // NUOVO — no-op in WebServerNonDisponibile, come enable/disable/resume
```

### B.4 — Il formato del token

```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ2IjoxLCJwIjoic2NyaXR0dXJhIiwiaWF0IjoxNzU1OTAwMDAwLCJleHAiOjE3NTg0OTIwMDB9.<43 caratteri>
```

- `p` è `"lettura"` o `"scrittura"`: **gli stessi due letterali** di `PermessiWeb`
  (`@SerialName("lettura")` / `@SerialName("scrittura")` in `WebPayload.kt`), così il valore che
  entra dal token e quello che esce nel payload non possono divergere.
- `iat` ed `exp` in **secondi**, come vuole la RFC 7519. Tutto il resto dell'app lavora in
  millisecondi: la conversione avviene **dentro `Jwt` e in nessun altro posto**, e nessun secondo
  esce da quel file. È il genere di unità mista che, lasciata girare, produce un token che scade fra
  cinquant'anni o fra mezz'ora.
- Lunghezza attesa: **~180 caratteri**, contro gli 8 di oggi. Conseguenza sugli header: `MAX_LINEA`
  in `HttpMessages.kt` è 8 KiB e `MAX_HEADER` è 50 — margine di due ordini di grandezza, nessuna
  modifica.

---

## C. Pattern da rispettare

**C.1 — La guardia in un punto solo (`CertificateStore`).** È il pattern più importante da copiare,
e la sua motivazione è scritta per esteso nel sorgente: `apri()` è chiamata da `enable()` **e** da
`resume()`, e `resume()` arriva da `MainActivity.onStart()`, cioè a ogni rotazione dello schermo.
`ChiaveFirma.caricaOCrea()` deve avere la stessa struttura — cache `inMemoria`, lettura da disco,
creazione solo se non c'è niente — e la stessa avvertenza in testa. La differenza è che
`CertificateStore` non ha una `revoca()`: quella è l'**unica** porta da cui una chiave nuova può
entrare, e va scritto lì che è l'unica.

**C.2 — I permessi del file (`soloPerNoi`).** `CertificateStore.salva()` toglie i permessi a tutti e
li ridà al proprietario, perché il codice gira anche su desktop dove la `umask` decide.
`ChiaveFirma` deve fare lo stesso: la chiave di firma è più delicata della chiave TLS, non meno.

**C.3 — Le primitive esistono già.** `sync/Crypto.kt` espone `Hkdf.hmac(key, message)`,
`constantTimeEquals(a, b)` e `randomBytes(size)`, tutte pubbliche e raggiungibili dal package `web`.
Nessuna primitiva nuova, nessuna dipendenza nuova — coerente con la linea 002-005 (ODS a mano, HKDF
a mano, DER a mano).

**C.4 — `kotlinx.serialization` come in `WebPayload.kt`.** Stessa libreria, stessa forma:
`@Serializable data class` + `Json { … }` privato al file. Per il payload del token serve
`ignoreUnknownKeys = true`: un campo sconosciuto dentro un payload **firmato** non è un attacco, e un
decoder severo renderebbe impossibile aggiungere un claim senza rompere i token in circolazione.

**C.5 — Un guasto è un esito, non un'eccezione.** `RichiestaLetta`, `CorpoLetto`, `EsitoScrittura`,
`SyncOutcome`: in questo progetto ciò che può andare storto torna come valore. `Jwt.verifica` deve
restituire `Payload?` e non lanciare mai — nemmeno su base64 storto, che `java.util.Base64` segnala
con `IllegalArgumentException`.

**C.6 — Le costanti che i test devono poter piegare.** `Cambiamenti` prende `attesaMillis` e
`massimo` come parametri «per la ragione di sempre in questo progetto: un test che aspettasse davvero
venticinque secondi non verrebbe eseguito». `Jwt` e `AccessToken` devono prendere `now: () -> Long` e
la durata come parametri, altrimenti la scadenza non è provabile.

**C.7 — Il tono dei messaggi.** Italiano, rivolti a chi legge, mai un codice numerico nudo. Il
modello è `spiegaRifiuto` in `app.js`.

**C.8 — Testi delle schermate come atti, non come etichette.** `SezioneWebApp` spiega *prima* che
il browser darà un avviso, perché «chi non se lo aspetta pensa che l'app sia rotta». Lo stesso vale
per la revoca: va detto che spegnere **non** revoca, o l'utente continuerà a credere di sì.

---

## D. Test da creare o aggiornare

### D.1 — Nuovi

| File | Tipo | Contenuto |
|---|---|---|
| `jvmSharedTest/…/web/JwtTest.kt` | unit | Giro completo lettura e scrittura; firma manomessa; payload manomesso; segmento mancante; quattro segmenti; segmento non base64url; `{"alg":"none"}` con terzo segmento vuoto; firma di un'altra chiave; `v` sconosciuta; `exp` scaduto di un secondo; `exp` mancante; token vuoto; token di soli punti. **Nessun caso deve lanciare** |
| `jvmSharedTest/…/web/ChiaveFirmaTest.kt` | unit | Nasce una volta sola; sopravvive a una seconda istanza sulla stessa cartella; `revoca()` la cambia e i token di prima non verificano più; file troncato → **si cancella, la porta non si apre e la ricrea il riavvio** (D-10 del piano: questa riga diceva «si rigenera come `CertificateStore`» ed è stata corretta il 2026-08-23, perché ricreare la chiave è una revoca di massa e non deve essere silenziosa); i permessi del file |
| `androidInstrumentedTest/…/web/JwtPiattaformaTest.kt` | strumentale | Conia e verifica sul dispositivo. Presidio per R7 |

### D.2 — Da riscrivere

**`AccessTokenTest.kt` — 17 casi, tutti da rivedere.** Il file chiama `t.rigenera()` in diciotto
punti e `t.invalida()` in uno. Sopravvivono per contenuto: la soglia per indirizzo e non globale, la
finestra che dimentica, l'accesso riuscito che azzera, il token valido ma insufficiente che non
consuma tentativi. Muoiono: «i token sono digitabili senza ambiguità» (non si digitano più),
«entrambi i token cambiano a ogni accensione» (è il difetto che si sta togliendo). Cambia di segno:
«spegnere invalida entrambi i token» diventa **«spegnere non invalida niente, revocare sì»**.

**`WebServiceTest.kt` — sei punti.** Riga 113 verifica l'URL con `?t=`; righe 153-163 verificano che
spegnere invalidi. Sono le due asserzioni che questa feature **rovescia di proposito**, e nel
documento di implementazione vanno nominate una per una: un test che cambia senza che nessuno lo
dichiari è indistinguibile da un test aggiustato per farlo passare.

### D.3 — Da adattare (solo il modo di costruire la richiesta)

`RouterTest.kt` (helper `richiesta()` alla riga 18 e `token.rigenera()` alla riga 50) e
`AttesaLungaTest.kt` (riga 31). Il grosso dei casi non cambia: cambia dove si infila la credenziale.
Spostando il token dentro `headers` nell'helper, la maggior parte dei casi resta identica.

**Casi da aggiungere a `RouterTest`:** `/` senza credenziale → `200`; `/api/eventi` senza header →
`401`; `Bearer` malformato (`"Bearer"` da solo, `"bearer x"` minuscolo — **deve funzionare**, lo
schema è case-insensitive per RFC 7235); `?t=<jwt valido>` → `401`; `PUT` con token di lettura →
`403` e nessun tentativo consumato.

### D.4 — Il JavaScript

Resta non provato automaticamente, come dalla 005. La checklist manuale nel browser va estesa con i
casi di §5 della Fase 1 (US-1, US-3, US-5) ed è la sola prova di quel pezzo: va scritta nel piano
come task, non lasciata all'improvvisazione.

---

## E. Rischi tecnici aggiornati

Rispetto alla Fase 1: **R1, R9 confermati e ora ancorati al codice; R2, R4, R5, R6, R10, R11
invariati; R3 rivisto; R7 rivisto; R8 chiarito; R12 nuovo.**

| # | Aggiornamento dopo la lettura del codice |
|---|---|
| **R1** (verificatore JWT) | **Confermato, impatto alto.** Mitigazione ancorata: la firma si ricalcola con `Hkdf.hmac` sui byte ASCII di `header + "." + payload` e si confronta con `constantTimeEquals` — entrambi già in `sync/Crypto.kt`, già usati da `SecureChannel` e già provati. Il codice nuovo è la codifica, non la crittografia. **`java.util.Base64.getUrlDecoder().decode()` lancia `IllegalArgumentException`** su input non valido: va catturata, o il difetto diventa un `500` invece di un `401` |
| **R3** (token che vive più a lungo) | **Rivisto: c'è una conseguenza in più che la Fase 1 non vedeva.** `localStorage` è per **origine**, quindi un browser conserva **un** token: aprire l'indirizzo di sola lettura su un browser che aveva quello completo lo **declassa**, e viceversa. Non è un difetto — è prevedibile e reversibile — ma va scritto, perché è l'unico modo in cui l'utente può togliersi da solo i comandi senza accorgersene |
| **R7** (`java.util.Base64` al `minSdk`) | **Rivisto: il precedente giusto non è `CorpoPiattaformaTest`.** Quello prova la lettura del corpo HTTP; accanto c'è `TlsPiattaformaTest`, che è il presidio di una scelta crittografica di piattaforma — ed è quello il fratello di questo caso. Un file nuovo, non un caso infilato in un test che parla d'altro |
| **R8** (`401` che fa girare a vuoto il ciclo) | **Chiarito, ed è più delicato di come sembrava.** In `app.js` la variabile `fermato` è **già usata** da `visibilitychange` (righe 796-812): riusarla per «token morto» vorrebbe dire che nascondere e riscoprire la scheda rimette in moto il ciclo, perché il ramo `else` fa `if (!inCorso) giro()`. Serve **una seconda bandiera**, controllata in `giro()` e in `programma()` |
| **R9** (chiave rigenerata per sbaglio) | **Confermato, impatto alto, e la forma della soluzione è già scritta nel progetto.** Da copiare `CertificateStore.caricaOCrea` riga per riga, avvertenza compresa |
| **R12** (nuovo) | **La motivazione di `START_NOT_STICKY` smette di essere vera.** `WebServerService` la dichiara così: «il token vive in memoria e ricomincerebbe diverso, quindi la notifica dichiarerebbe raggiungibile un indirizzo che nessuno conosce». Dopo questa feature il token **non** ricomincia diverso. **La scelta però resta giusta per un'altra ragione:** a processo ucciso e servizio riavviato dal sistema, nessuno chiamerebbe `resume()` — quello parte da `MainActivity.onStart()` — quindi la notifica tornerebbe senza che nessun socket sia aperto. Passare a `START_STICKY` è una cosa possibile e desiderabile (il tablet in cucina sopravvivrebbe a un OOM kill), ma vuole che sia il servizio ad aprire la porta: **fuori scope**, da annotare come seguito naturale |
| **R13** (nuovo, basso) | **Un token può scadere mentre una richiesta è appesa.** Con `attendi=1` la verifica avviene all'inizio e la risposta arriva fino a 25 s dopo (`ATTESA_MILLIS`). È accettabile e va dichiarato: l'autorizzazione vale per la richiesta, non per l'istante della risposta. Ricontrollarla al risveglio non aggiungerebbe sicurezza e aggiungerebbe un ramo |

---

## F. Prerequisiti e task bloccanti

**Nessun prerequisito bloccante. Nessun refactoring propedeutico. Nessuna dipendenza nuova.**

Tre constatazioni che valgono più di una rassicurazione generica:

1. **Il cablaggio in `ReminderApp.kt` non cambia.** `cartellaCertificato = File(filesDir, "web-tls")`
   è già la cartella in cui la decisione 1 mette la chiave di firma: `WebService` la riceve già e la
   passa a `ChiaveFirma` esattamente come a `CertificateStore`. Cambia solo un commento.
2. **Il punto d'ingresso del controllo d'accesso è uno solo.** `Router.gestisci` riga 72. Non c'è
   nessun secondo posto da cui si entra, quindi non c'è nessun secondo posto da ricordarsi.
3. **`AccessToken` non è raggiunto da nessuno se non da `Router` e `WebService`.** La ricerca su
   tutto il repository trova `rigenera`/`invalida`/`coppia` solo in quei due file e nei test.
   Riscriverlo non ha onde lunghe.

**L'ordine invece è vincolante:** `Jwt.kt` e il suo test avversariale **per primi**. Quattro file
finiranno per dipendere dal fatto che un token contraffatto non entra; scoprire che entra dopo
averceli appoggiati sopra costa la riscrittura di tutti e quattro.

---

## G. Le domande aperte della Fase 1, chiuse

**3. `tokenRichiesto` resta o sparisce?** → **Resta.** Con `/` pubblica tutti e sei gli asset hanno
`tokenRichiesto = false`, quindi il ramo `if (asset.tokenRichiesto && !accesso.puoLeggere)` in
`Router.get` non scatta mai. Ma il campo non è il controllo: è ciò che **obbliga chi aggiunge una
riga a `AMMESSI` a decidere chi può leggerla** — è scritto così nel KDoc di `Asset` e resta vero.
Toglierlo per «semplificare» vorrebbe dire che la prossima risorsa nasce senza che nessuno si sia
posto la domanda.

**4. `WebStatus` porta il token o l'indirizzo composto?** → **Come oggi: porta i token, compone in
`WebStatus.indirizzo()`.** Il KDoc alla riga 74 dà la ragione — «due punti che lo compongono per
conto proprio prima o poi lo compongono in due modi diversi» — e vale identica con il frammento. I
nomi `tokenLettura`/`tokenScrittura` restano (§B.2).

**5. `validoFinoA`: campo o solo testo?** → **Campo `Long?` in `WebStatus`.** `formatDate(millis)`
esiste già in `commonMain/platform/DateTime.kt` ed è `expect`/`actual` su entrambi i target: la
schermata può dire «vale fino al 22/09/2026» senza calcolare niente. «Trenta giorni» scritto nel
testo costringerebbe l'utente a fare la sottrazione, e la farebbe male.

**6. Dove si ferma il ciclo sul `401`?** → **Con una bandiera nuova, non riusando `fermato`.**
Evidenza in §E, R8. Nome proposto: `senzaAccesso`, controllata in testa a `giro()` e in
`programma()`; a differenza di `fermato` non viene mai rimessa a `false` da `visibilitychange`, solo
da un ricaricamento della pagina o da una nuova consegna.

**7. Il presidio strumentale, dove?** → **File nuovo, `JwtPiattaformaTest.kt`**, accanto a
`TlsPiattaformaTest` che è il precedente giusto (§E, R7), non dentro `CorpoPiattaformaTest`.

---

## Che cosa resta da decidere nella Fase 3

> Tutte chiuse il 2026-08-23. Le scelte sono in §3 del
> [piano](phase-3-implementation-plan.md), da D-06 a D-10.

1. **Il nome del parametro nel frammento.** `#a=` è corto; `#accesso=` è leggibile. L'indirizzo è
   già lungo ~180 caratteri e si copia, non si legge: propendo per `#a=`, ma è una scelta da mettere
   nero su bianco.
2. **La conferma della revoca: dialogo o doppio tocco?** `SezioneWebApp` non ha oggi nessun dialogo;
   `IndirizzoCompleto` e `Impronta` usano il pattern «testo che apre una sezione». Un `AlertDialog`
   sarebbe il primo della schermata.
3. **Che cosa fa la revoca se l'interruttore è spento?** Ruotare la chiave a porta chiusa è
   sensato (toglie l'accesso per quando si riaprirà), ma il comando va mostrato lì?
4. ~~**Se `ChiaveFirma` non riesce a scrivere su disco**~~ → **Deciso (D-09): non si apre la
   porta.** Una chiave solo in memoria sarebbe il difetto di oggi travestito da funzionamento.
   Il guasto diventa un messaggio, nella stessa forma che `WebService.apri()` usa già per il
   certificato.
5. **La numerazione dei task e la checklist manuale del browser**, sul modello della 005.
