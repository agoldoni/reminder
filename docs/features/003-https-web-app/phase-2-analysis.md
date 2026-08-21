# Traffico cifrato per la web app locale — Analisi tecnica (Fase 2)

**Data:** 2026-08-21
**Riferimento:** [phase-1-requirements.md](phase-1-requirements.md)
**Codice esaminato:** `shared/src/{commonMain,jvmSharedMain,androidMain,jvmSharedTest}`, `androidApp/src/main`

> **In breve.** La 002 ha lasciato il codice nella forma giusta: il ciclo di ascolto è già isolato
> in una classe sua, il frontend non nomina mai lo schema, e i guasti diventano già messaggi in
> italiano invece di eccezioni. TLS entra in **tre file esistenti** e ne aggiunge **quattro nuovi**,
> senza toccare database, migrazioni, permessi, dipendenze e asset. Il lavoro vero è il codificatore
> DER; il resto è cablaggio. Due punti da verificare prima di scrivere, uno dei quali bloccante
> (§F): che l'archivio chiavi in memoria funzioni **su Android**, non solo sul desktop dove girano
> i test.

---

## A. File coinvolti

### Nuovi

| Percorso | Perché |
|---|---|
| `shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/Der.kt` | Codificatore ASN.1/DER minimale: `sequence`, `integer`, `bitString`, `octetString`, `oid`, `utcTime`, `boolean`, tag di contesto, e `raw` per infilare byte già codificati. Sta in `jvmSharedMain` perché usa `java.math.BigInteger`, presente su entrambi i target — la stessa constatazione che tiene lì [Crypto.kt](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/sync/Crypto.kt) |
| `.../web/SelfSignedCertificate.kt` | Costruisce il `TBSCertificate`, lo firma e restituisce chiave privata + `X509Certificate`. Genera la coppia P-256 con lo stesso `ECGenParameterSpec("secp256r1")` già usato da `EphemeralKeyPair` |
| `.../web/TlsIdentity.kt` | Ciò che serve al server: chiave, certificato, **impronta SHA-256** già formattata, e l'`SSLContext` costruito da un archivio chiavi in memoria |
| `.../web/CertificateStore.kt` | Persistenza e **idempotenza**: `carica()` restituisce l'identità esistente, `caricaOCrea()` la genera solo se non c'è. È il presidio del rischio R1 della Fase 1 |

Tutti e quattro in `jvmSharedMain`, quindi provabili da `:shared:desktopTest`. Nessuno di essi ha
bisogno di un `Context`.

### Modificati

| Percorso | Modifica | Perché |
|---|---|---|
| [HttpServer.kt](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/HttpServer.kt) | Il `ServerSocket` diventa iniettabile; i protocolli si impostano sul socket d'ascolto | È l'unico punto in cui si crea il socket (riga 40, `ServerSocket(requestedPort)`). Il ciclo, i timeout e la cattura degli errori restano com'erano |
| [WebService.kt](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/WebService.kt) | Nuovo parametro per l'archivio del certificato; `apri()` carica l'identità prima di legarsi e mette l'impronta nello stato | È il posto dove già si decide che cosa finisce in `WebStatus` e dove i guasti diventano `lastMessage` |
| [WebServerController.kt](shared/src/commonMain/kotlin/it/agoldoni/reminder/web/WebServerController.kt) | `WebStatus.url` passa a `https://`; nuovo campo `impronta: String?` | L'URL si compone **in un punto solo** per scelta già documentata lì: è quello il punto |
| [SezioneWebApp.kt](shared/src/commonMain/kotlin/it/agoldoni/reminder/ui/web/SezioneWebApp.kt) | Riga che anticipa l'avviso del browser; impronta mostrata | Senza, l'utente pensa che si sia rotto qualcosa |
| [ReminderApp.kt](androidApp/src/main/kotlin/it/agoldoni/reminder/ReminderApp.kt) | Passa a `WebService` la cartella dove custodire chiave e certificato | È già il posto dove `WebService` viene costruito, e l'unico che ha un `Context` |
| `CLAUDE.md` | Sezione `web/`: TLS, la regola del certificato generato una volta, e la revisione del paragrafo sul *secure context* | Vedi V-03: quel paragrafo va riscritto sulla base di ciò che si osserva, non di ciò che si suppone |
| [002/status.md](../002-web-app-android/status.md) | Una riga di rimando | La 002 resta chiusa, ma chi la legge deve sapere che la porta non è più in chiaro |

### Non toccati — e vale la pena dire perché

- **[app.js](shared/src/webAssets/web/app.js) e tutti gli asset.** Verificato: non compare mai la
  stringa `http`; `fetch('/api/eventi?t=…')` e `manifest.json` (`start_url: "/"`, `scope: "/"`) sono
  relativi allo schema. **Il frontend non richiede una riga di modifica.**
- **[Router.kt](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/Router.kt),
  [AccessToken.kt](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/AccessToken.kt),
  [HttpMessages.kt](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/HttpMessages.kt),
  [StaticAssets.kt](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/StaticAssets.kt),
  [WebPayload.kt](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/WebPayload.kt).** TLS sta
  sotto: nessuno di questi vede un socket. Il token resta esattamente com'è.
- **[WebServerService.kt](shared/src/androidMain/kotlin/it/agoldoni/reminder/web/WebServerService.kt)**
  e tutto il ciclo di vita del servizio in primo piano.
- **Database, entità, migrazioni.** Nessuno schema cambia: chiave e certificato stanno su file, non
  in `peers`. Nessuna migrazione da scrivere.
- **`AndroidManifest.xml`, `libs.versions.toml`, `build.gradle.kts`.** Nessun permesso nuovo
  (`INTERNET` c'è già), nessuna dipendenza nuova, nessun source set nuovo.
- **[WebViewModel.kt](shared/src/commonMain/kotlin/it/agoldoni/reminder/ui/web/WebViewModel.kt).**
  Riespone `controller.status` e basta: il campo nuovo passa da sé.
- **[AppContainer.kt](shared/src/commonMain/kotlin/it/agoldoni/reminder/di/AppContainer.kt).**
  `web: WebServerController = WebServerNonDisponibile` non cambia forma.
- **`PorteTest` (`:desktopApp`) e `PorteWebTest`.** La porta resta 9888.

---

## B. Contratti e interfacce da modificare

**1. `HttpServer` — costruttore.** Oggi (riga 26):

```kotlin
internal class HttpServer(
    private val scope: CoroutineScope,
    private val gestisci: suspend (HttpRequest, String) -> HttpResponse
)
```

`gestisci` **deve restare l'ultimo parametro**: [WebService.kt:37](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/WebService.kt#L37)
lo passa come lambda finale (`HttpServer(scope) { richiesta, chi -> … }`). Quindi il fornitore di
socket va inserito **in mezzo**, con un default che apre un `ServerSocket` in chiaro:

```kotlin
internal class HttpServer(
    private val scope: CoroutineScope,
    private val apriSocket: (Int) -> ServerSocket = { ServerSocket(it) },
    private val gestisci: suspend (HttpRequest, String) -> HttpResponse
)
```

> ⚠️ **Conseguenza da non scoprire in compilazione:**
> [HttpServerTest.kt:33](shared/src/jvmSharedTest/kotlin/it/agoldoni/reminder/web/HttpServerTest.kt#L33)
> chiama `HttpServer(scope, gestisci)` **per posizione**. Con la firma nuova `gestisci` finirebbe
> nello slot di `apriSocket`: il compilatore lo prende, il test smette di avere senso. Va passato
> per nome. È l'unico punto del progetto dove succede, ma è il tipo di modifica che passa
> inosservata.

**2. `WebService` — costruttore.** Nuovo parametro obbligatorio per la cartella (o il file) dove
custodire l'identità TLS. Due soli chiamanti: `ReminderApp.onCreate()` e il costruttore d'aiuto in
`WebServiceTest`. Un default non va messo: un percorso predefinito sbagliato produrrebbe un
certificato rigenerato a ogni avvio, cioè esattamente R1.

**3. `WebStatus` — `data class` in `commonMain`.** Due cambiamenti:

```kotlin
val url: String? get() = … "https://$host:$port/?t=$token"   // era http
val impronta: String? = null                                  // additivo
```

Il campo è additivo e l'unico consumatore è `SezioneWebApp`. Il cambio di schema **non è additivo**
ed è la parte visibile all'utente: un segnalibro `http://` della 002 smette di funzionare (R7).

**4. `WebServerController` e `WebServerNonDisponibile`: invariati.** L'interfaccia non cresce.
Il desktop continua a non sapere niente della web app.

**Nessun altro contratto cambia.** In particolare: nessuna interfaccia di piattaforma nuova, perché
`javax.net.ssl`, `java.security` e `java.io.File` sono su entrambi i target JVM. È la stessa ragione
per cui la crittografia della sincronizzazione non ha avuto bisogno di `expect`/`actual`.

---

## C. Pattern da rispettare

**Nomi.** Il pacchetto `web/` usa **classi in inglese** (`AccessToken`, `Router`, `StaticAssets`,
`WebPayload`) e **verbi e parametri in italiano** (`gestisci`, `apri`, `chiudi`, `provenienza`,
`rigenera`, `invalida`, `servi`). I file nuovi seguono: `CertificateStore` con `carica()`,
`caricaOCrea()`, `salva()`; `TlsIdentity` con `impronta()`.

**I commenti spiegano il perché, non il cosa.** È la cifra di questo codice e non è un vezzo: sono
il posto dove vivono le decisioni. Tre meritano di essere scritte dove qualcuno le troverà —
il certificato che non viene mai riemesso, i parametri **assenti** (non `NULL`) nell'identificatore
di algoritmo ECDSA, e la ragione per cui l'impronta esiste.

**I guasti diventano messaggi, non eccezioni.** `WebService.apri()` (righe 89–115) è già scritto con
`runCatching { }.onSuccess { }.onFailure { }` e mette in `lastMessage` una frase in italiano. Il
caricamento del certificato e la costruzione dell'`SSLContext` entrano **dentro** quel
`runCatching`, non accanto.

**Un difetto che riguarda una connessione chiude una connessione.** `HttpServer.servi()` cattura
`SocketTimeoutException` e `IOException` attorno a `client.use`, e cattura i difetti del gestore
**dentro** l'ambito del socket — quest'ultima è una correzione pagata a caro prezzo nella 002 e c'è
un test che la presidia. `SSLHandshakeException` discende da `IOException`, quindi la struttura
regge senza modifiche; ma va **verificato**, non dedotto, perché l'handshake non avviene su
`accept()` ma alla prima lettura.

**Visibilità.** `HttpServer`, `Router`, `AccessToken` sono `internal`. I quattro file nuovi pure.

**Test dove girano.** `jvmSharedTest`, quindi `:shared:desktopTest`. Il target Android non esegue
questi test: da qui la verifica bloccante V-01.

**Niente dipendenze nuove.** È una decisione dichiarata in due documenti di feature e in `CLAUDE.md`.

---

## D. Test da creare o aggiornare

### Nuovi

| File | Tipo | Che cosa prova |
|---|---|---|
| `DerTest.kt` | unitario | Le lunghezze ai confini (127, 128, 255, 256, 65535) — la forma breve e la forma lunga sono il posto classico in cui DER si rompe. `INTEGER` con bit alto acceso che prende il byte di segno. Codifica degli OID noti (`1.2.840.10045.4.3.2`, `2.5.29.17`) confrontata con i byte della specifica. `UTCTime` in UTC, non nel fuso locale |
| `SelfSignedCertificateTest.kt` | unitario | Il certificato si rilegge con `CertificateFactory`; `verify(publicKey)` passa; `checkValidity()` passa; `getSubjectAlternativeNames()` contiene gli indirizzi dati; `getBasicConstraints() == -1` (non è una CA); l'uso esteso contiene `serverAuth`; l'impronta è coerente con `MessageDigest("SHA-256")` su `cert.encoded` |
| `TlsHandshakeTest.kt` | **integrazione — è la prova centrale** | Server su porta 0 con TLS; client `SSLSocket` con un `TrustManager` che si fida **solo** di quel certificato; `GET` completo e `200` con il corpo atteso. Poi: un client che non si fida riceve `SSLHandshakeException` **e il server serve ancora** il client successivo. Poi: un client in chiaro contro la porta TLS non ottiene contenuto e non abbatte il ciclo |
| `CertificateStoreTest.kt` | unitario su file temporanei | `caricaOCrea()` su cartella vuota genera; una seconda chiamata restituisce lo **stesso** certificato byte per byte; un'istanza nuova sulla stessa cartella pure — è la prova di R1. Cartella non scrivibile → errore riportato, non eccezione che sfugge |

Sul perché la prova centrale è un handshake e non un confronto di byte attesi: confrontare l'output
del proprio codificatore con byte che ha prodotto lui congela i propri errori. Un handshake JSSE
riuscito dice che il certificato è valido per definizione operativa.

### Aggiornati

| File | Modifica | Costo |
|---|---|---|
| [HttpServerTest.kt](shared/src/jvmSharedTest/kotlin/it/agoldoni/reminder/web/HttpServerTest.kt) | `HttpServer(scope, gestisci = gestisci)` per nome (§B.1). I nove test restano in chiaro, iniettando il fornitore predefinito: provano il **ciclo**, che è ortogonale a TLS, e in chiaro costano meno | 1 riga |
| [WebServiceTest.kt](shared/src/jvmSharedTest/kotlin/it/agoldoni/reminder/web/WebServiceTest.kt) | Cartella temporanea nel costruttore d'aiuto. Il metodo `parla()` apre un `Socket` in chiaro: contro un server TLS non funziona più. **Decisione da prendere** (§F, D-02): convertire tutti e dieci i test a client TLS, oppure lasciarli in chiaro iniettando il socket semplice e coprire la via vera con `TlsHandshakeTest` | 0,25 g |
| [RouterTest.kt](shared/src/jvmSharedTest/kotlin/it/agoldoni/reminder/web/RouterTest.kt) | Nessuna: il router non vede socket | — |
| `PorteTest`, `PorteWebTest` | Nessuna: la porta non cambia | — |

**Riferimento:** la suite oggi è a **188 test, 0 falliti** (`./gradlew :shared:desktopTest :desktopApp:test`).
Attesa dopo la feature: ~205–210.

### Verifica sul dispositivo (non automatizzabile)

Riesecuzione dei casi della 002 sopra TLS — **TC-16** (lista coincidente e aggiornamento), **TC-17**
(`304` su `If-None-Match`), **TC-19** (rotazione, token invariato), **TC-20** (background/primo
piano), **TC-21** (irraggiungibile a pagina aperta) — più i casi nuovi: cattura del traffico,
avviso una volta sola attraverso un riavvio del telefono, cambio di rete, impronta confrontata.

---

## E. Rischi tecnici aggiornati

Confermati dall'analisi, con l'evidenza che li fa salire o scendere rispetto alla Fase 1:

**R1 — Certificato rigenerato per sbaglio** · *Medio → confermato, ora circoscritto*
`WebService.apri()` è chiamato da `enable()` **e** da `resume()`, e `resume()` è chiamato da
`MainActivity.onStart()`, cioè a ogni rotazione dello schermo. Se la generazione stesse lì dentro
senza guardia, l'utente vedrebbe l'avviso a ogni rotazione. *Presidio:* la guardia sta in
`CertificateStore.caricaOCrea()`, un punto solo, con `CertificateStoreTest` addosso.

**R4 — DER scritto a mano** · *Basso → confermato basso, e si può abbassare ancora*
Restano da codificare a mano solo `TBSCertificate` ed estensioni: la `SubjectPublicKeyInfo` arriva
già in DER da `PublicKey.getEncoded()` e la firma ECDSA arriva già in DER da `Signature`. Due
insidie note e specifiche: (a) per ECDSA i **parametri dell'identificatore di algoritmo devono
essere assenti**, non `NULL` — è la differenza con RSA e un classico di interoperabilità; (b)
`UTCTime` va scritto in UTC, non nel fuso del telefono.

**R5 — Un handshake per connessione** · *Medio → confermato, ed è misurabile*
[HttpServer.kt:22](shared/src/jvmSharedMain/kotlin/it/agoldoni/reminder/web/HttpServer.kt#L22) lo
dice esplicitamente: «nessun keep-alive, una richiesta per connessione». La pagina tira sei asset
più `/api/eventi`, e il polling è ogni 30 s. La ripresa di sessione JSSE dovrebbe ridurre gli
handshake successivi; da **misurare** contro il criterio dei 2 secondi della 002.

**R9 — Regressione della 002** · *Medio → confermato*
Nessuna logica applicativa cambia, ma il livello sotto sì. I casi vanno rieseguiti.

### Rischi nuovi, emersi guardando il codice

**R12 — Lavoro su disco e generazione di chiavi sul thread principale** · *Medio — nuovo*
`SezioneWebApp` chiama `viewModel.accendi()` → `WebService.enable()` **direttamente dal thread
principale**. Oggi lì dentro c'è già un `ServerSocket(port)`, che è I/O; aggiungerci lettura di due
file, e la prima volta una generazione di chiave e una firma, lo appesantisce. Non è un ANR — sono
millisecondi — ma è esattamente il pattern che la sincronizzazione ha già dovuto correggere
(`syncNow` e `pair` girano su `withContext(Dispatchers.IO)`, e `CLAUDE.md` spiega perché). *Da
decidere:* portare il corpo di `enable()`/`resume()` su `Dispatchers.IO` — che però cambia la firma
da sincrona ad asincrona e tocca `WebViewModel` — oppure accettarlo e misurarlo. Propendo per
misurare prima: se la prima accensione costa meno di ~50 ms, la complicazione non si ripaga.

**R13 — L'archivio chiavi in memoria su Android** · *Da verificare — vedi V-01*
`KeyStore.getInstance("PKCS12")` con `load(null, null)` e `setKeyEntry` è la via ovvia, ma il tipo
predefinito di Android è storicamente diverso da quello del JDK e i test girano **solo** sul
desktop. Va chiesto esplicitamente `"PKCS12"` invece di affidarsi a `getDefaultType()`, e va
provato sul dispositivo.

**R14 — Il paragrafo di `CLAUDE.md` sul *secure context* diventa ambiguo** · *Basso, ma va risolto*
Oggi dice che l'installazione PWA non è offerta perché manca il contesto sicuro su
`http://192.168.x.y`. Con `https://` e un certificato non fidato la situazione non è più quella
descritta e **non è ovvia**: l'origine è formalmente sicura, ma i browser trattano a parte le pagine
con errori di certificato, e non allo stesso modo fra loro. *Risoluzione:* V-03 — si guarda e si
scrive quel che si è visto, invece di sostituire una supposizione con un'altra.

**R15 — Formato di persistenza e password fittizia** · *Basso*
PKCS#12 su file vuole una password che qui non protegge nulla, perché starebbe nel sorgente:
scriverla equivale a dichiarare una protezione che non c'è. L'alternativa è più onesta e più
semplice — due file, `PKCS#8` per la chiave (`privateKey.encoded`) e DER per il certificato
(`cert.encoded`), riletti con `KeyFactory.getInstance("EC")` e `CertificateFactory`. La protezione
è quella dei permessi: su Android `filesDir` è già privata all'app, ed è la stessa scelta già
dichiarata per il `sharedSecret` in `peers`.

---

## F. Prerequisiti e task bloccanti

### Verifiche da fare **prima** di scrivere il grosso

**V-01 — L'archivio chiavi e l'`SSLContext` funzionano su Android** · **bloccante** · 0,25 g
`KeyStore.getInstance("PKCS12")` in memoria, `setKeyEntry` con una chiave EC, `KeyManagerFactory`,
`SSLContext`, `SSLServerSocket` legato: su emulatore **API 26** (il minimo) e su un'API recente. È
il presupposto di tutta la feature e i test su desktop non lo dimostrano. Stesso spirito della T-03
della 002, che aveva già evitato di scoprire tardi una differenza fra desktop e APK.
*Se fallisce:* si prova `"BKS"`, o `KeyManagerFactory` alimentato da un `X509ExtendedKeyManager`
scritto a mano — che salta del tutto l'archivio chiavi ed è una decina di righe.

**V-02 — Quali protocolli offre davvero l'API 26** · non bloccante · 0,1 g
Leggere `supportedProtocols` sull'emulatore API 26 e 34, e abilitare l'**intersezione** con
{TLS 1.2, TLS 1.3} invece di un elenco fisso che su API 26 farebbe fallire l'apertura del socket.

**V-03 — Che cosa fa il browser con un autofirmato scavalcato** · non bloccante, ma decide un
paragrafo di `CLAUDE.md` · 0,15 g
Su Chromium e Firefox, con l'eccezione accettata: `window.isSecureContext`, presenza di
`navigator.clipboard`, esito di una registrazione di service worker di prova, e se l'installazione
PWA viene offerta. Si fa con la sessione X separata già in uso (`tools/sessione-x.sh browser`) e
`adb forward`. **Non cambia lo scope** — la PWA resta fuori — ma dice che cosa scrivere.

### Decisioni da chiudere prima di iniziare

| ID | Decisione | Proposta |
|---|---|---|
| D-01 | Formato di persistenza | Due file DER (chiave PKCS#8 + certificato), non PKCS#12: nessuna password fittizia (R15) |
| D-02 | `WebServiceTest`: TLS o chiaro | Chiaro per i dieci test esistenti (provano l'interruttore e il ciclo di vita, non il trasporto), TLS in `TlsHandshakeTest` per la via vera. Costa meno e non lascia scoperto niente |
| D-03 | `enable()` su `Dispatchers.IO` | Misurare prima (R12); complicare dopo, se la misura lo chiede |
| D-04 | `subjectAltName` | Includere gli indirizzi locali al momento della generazione più `127.0.0.1`. Nessuno li verificherà, ma tengono aperta la porta a un'installazione futura e fanno funzionare la prova via `adb forward` |
| D-05 | Redirect da HTTP a HTTPS riconoscendo il primo byte | **No** in questa iterazione: è parsing in più su una porta esposta per un beneficio di cortesia. Annotato per il seguito |
| D-06 | Dove sta la cartella su Android | `filesDir`, già privata all'app. Non `getDatabasePath()`, che è la cartella dei database e non deve ospitare altro |

### Prerequisiti di refactoring

**Nessuno.** Il codice della 002 è già nella forma che serve: il socket si crea in un punto solo, la
composizione dell'URL sta in un punto solo, i guasti sono già incanalati in `lastMessage`, e il
frontend non nomina mai lo schema. È il vantaggio di aver tenuto `HttpServer` separato da
`WebService`.

---

## Che cosa dice questa fase sulla stima

La Fase 1 stimava **3,0 giorni/uomo**, intervallo 2–4. L'analisi non la sposta, ma ne cambia la
distribuzione: meno cablaggio del previsto (il frontend è a costo zero, i contratti si toccano
appena, nessuna migrazione), un po' più di verifica (V-01, V-02, V-03 valgono insieme mezza
giornata e vanno spese all'inizio). Le voci di §6 della Fase 1 reggono; se qualcosa slitta, sarà
`Der.kt`, e il segnale sarà `TlsHandshakeTest` che non passa entro la prima mezza giornata.
