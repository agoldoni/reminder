# Traffico cifrato per la web app locale — Stato

**Aggiornato:** 2026-08-21
**Stato:** implementata e verificata su emulatore e telefono. **Resta la prova su rete reale**, che
in questo ambiente non è eseguibile: PC e telefono sono su due sottoreti diverse (vedi in fondo).
**Piano:** [phase-3-implementation-plan.md](phase-3-implementation-plan.md)

---

## Task

| ID | Task | Stato |
|---|---|---|
| T-01 | **V-01 bloccante:** archivio chiavi, `SSLContext`, `SSLServerSocket` sul dispositivo | ✅ su API 26, 29 e 33 — vedi sotto, con due scoperte |
| T-02 | **V-02:** protocolli offerti da Android, intersezione con {1.2, 1.3} | ✅ e cambia il codice, non solo la documentazione |
| T-03 | `Der.kt` + `DerTest` | ✅ 16 test |
| T-04 | `SelfSignedCertificate.kt` + test | ✅ 10 test, più la controprova con `openssl` |
| T-05 | `TlsIdentity.kt` | ✅ |
| T-06 | `CertificateStore.kt` + test (presidio di R1) | ✅ 10 test |
| T-07 | `HttpServer`: socket iniettabile, chiamata posizionale corretta | ✅ |
| T-08 | `TlsHandshakeTest`, la prova centrale | ✅ 8 test |
| T-09 | `WebService`: archivio, impronta nello stato, messaggi | ✅ + 4 test nuovi |
| T-10 | `WebStatus`: schema `https`, campo `impronta` | ✅ |
| T-11 | `ReminderApp`: `filesDir/web-tls` | ✅ |
| T-12 | `SezioneWebApp`: avviso anticipato, impronta a scomparsa | ✅ |
| T-13 | **Misura di R12** → chiude D-03 | ✅ e ha prodotto una correzione al codice |
| T-14 | **Misura di R5** (apertura pagina senza keep-alive) | ✅ 77–115 ms contro un criterio di 2 s |
| T-15 | **V-03:** che cosa fa il browser con un autofirmato scavalcato | ✅ e smentisce in parte ciò che era scritto |
| T-16 | Verifica sul campo | ✅ su emulatore e telefono via `adb`; ⏳ su rete reale |
| T-17 | `CLAUDE.md`, documenti, questo file | ✅ |

**Suite:** `./gradlew :shared:desktopTest :desktopApp:test` → **236 test, 0 falliti** (erano 188).

---

## V-01 e V-02: due scoperte che hanno cambiato il codice

La verifica bloccante è passata su **API 26, 29 e 33** — handshake vero, non solo classi che
esistono. Ma nel passarla ha detto due cose che nessun test su desktop avrebbe potuto dire:

**1. Su Android il tipo di archivio chiavi predefinito è `BKS`, non `PKCS12`.** Il codice lo chiede
per nome, e ora si sa perché: con `KeyStore.getDefaultType()` la catena si sarebbe rotta **solo sul
dispositivo**, cioè esattamente dove i test di questo progetto non arrivano. Era il rischio R13 del
piano, e si è rivelato reale.

**2. L'elenco dei protocolli abilitati di default comprende TLSv1 e TLSv1.1** — su tutte e tre le
versioni provate. Due protocolli ritirati, accesi senza che nessuno li chieda. Restringere non era
cosmesi: `TlsIdentity.apriSocket` abilita l'**intersezione** fra {TLS 1.2, 1.3} e ciò che la
piattaforma offre, perché su API 26 il 1.3 non esiste — l'elenco lì si ferma a `TLSv1.2` — e
pretenderlo avrebbe fatto fallire l'apertura del socket con un errore che non nomina Android.

| | API 26 | API 29 (telefono) | API 33 |
|---|---|---|---|
| Archivio predefinito | BKS | BKS | BKS |
| Protocolli supportati | fino a TLSv1.2 | fino a TLSv1.3 | fino a TLSv1.3 |
| Negoziato con la nostra chiave P-256 | TLSv1.2 · `ECDHE_ECDSA_AES_128_GCM` | TLSv1.3 | TLSv1.3 |

---

## Verifiche eseguite

| TC | Esito |
|---|---|
| TC-01…TC-15 (automatici) | ✅ 236 test verdi |
| Controprova esterna del certificato | ✅ `openssl x509 -text` lo stampa per intero: v3, `ecdsa-with-SHA256`, `notBefore` retrodatato di un giorno, `CA:FALSE` critica, `Digital Signature` critica, `TLS Web Server Authentication`, `IP Address:127.0.0.1, IP Address:192.168.1.42`. `openssl verify` sulla catena autofirmata: `OK` |
| Impronta | ✅ quella mostrata dall'app, quella calcolata da `openssl` e quella vista dal client coincidono carattere per carattere |
| TC-16 · traffico non in chiaro | ⚠️ **parziale**: verificato che la porta parla solo TLS (un client in chiaro non ottiene nulla) e che il giro completo è cifrato; la **cattura del traffico su Wi-Fi** resta da fare su rete reale |
| TC-17 · l'avviso una volta sola | ✅ dopo `am force-stop` e riavvio dell'app, l'impronta è **identica**. Il token invece cambia, com'è previsto |
| TC-18 · cambio di rete | ✅ per costruzione e per test (`CertificateStoreTest`); ⏳ non osservato su rete vera |
| TC-19 · impronta confrontabile | ✅ letta dallo schermo dell'app e confrontata con `openssl s_client` |
| TC-20 · non-regressione della 002 | ✅ suite intera verde; pagina, `/api/eventi` e asset serviti sopra TLS; `403` senza token |
| TC-21 · misure | ✅ vedi sotto |
| TC-22 · V-03 sul browser | ✅ vedi sotto |
| TC-23 · V-01 su dispositivo | ✅ API 26, 29, 33 |

Protocollo verificato dall'host via `adb forward`: `https://127.0.0.1:9888/?t=…` → `200` con i dati
reali; `/api/eventi` senza token → `403`; `http://` sulla porta 9888 → nessuna risposta HTTP.

---

## T-13: la misura ha trovato uno spreco, e D-03 si chiude senza complicare niente

Misurando il costo della prima accensione sul thread principale è emerso che l'`SSLContext` veniva
**ricostruito a ogni apertura del socket** — e `apri()` è chiamato da `resume()`, cioè da
`MainActivity.onStart()`, a ogni ritorno in primo piano e a ogni rotazione. Ora si costruisce una
volta sola, con il beneficio in più che la sua cache di sessioni TLS aiuta proprio dove serve
(nessun keep-alive, sette handshake per pagina).

Sul telefono di prova (Redmi Note 7, API 29):

| | prima | dopo |
|---|---|---|
| Prima generazione + scrittura (**una volta per installazione**) | 116,8 ms | 116,8 ms |
| Rilettura da disco (ogni avvio del processo) | 2,7 ms | 2,7 ms |
| Apertura del socket TLS | 57,8 ms | **12,7 ms** |
| Riapertura (**ogni `onStart`**) | 57,8 ms | **0,3 ms** |

**D-03 si chiude su «lasciare `enable()` sul thread principale».** L'unico numero sopra la soglia
dei 50 ms è la generazione, che accade una volta sola nella vita dell'installazione, mentre
l'utente sta guardando l'interruttore che ha appena toccato. Rendere `enable()` asincrono
significherebbe cambiare il contratto del ViewModel e introdurre uno stato di attesa per un evento
da 130 ms che capita una volta: non si ripaga. Il percorso che si ripete costa 0,3 ms.

**T-14 (R5):** pagina intera — sette connessioni TLS, una per asset, senza keep-alive — in
**77–115 ms**, contro il criterio di 2 secondi ereditato dalla 002. Il keep-alive resta non
necessario.

---

## T-15 (V-03): la ragione scritta nei documenti era imprecisa, e ora si sa quale è

La 002 diceva che l'installazione PWA non è offerta perché manca il *secure context* su
`http://192.168.x.y`. Con `https://` quell'argomento cadrebbe — l'origine sarebbe un contesto
sicuro anche con un certificato non fidato. Serviva guardare, e si è guardato.

Su Chromium, con l'avviso scavalcato:

| Indirizzo | Esito |
|---|---|
| `https://127.0.0.1:9888` (via `adb forward`) | pagina servita, **«Installa» offerto** |
| `https://192.168.86.45:9899` (stesso servizio, indirizzo di rete) | pagina servita, **«Installa» NON offerto** |

La differenza non è lo schema: è che il loopback è un contesto sicuro *comunque*, mentre a un
indirizzo di rete conta il certificato — e Chrome nega l'installabilità a una pagina servita con un
errore di certificato. **La conclusione della 002 resta vera, la sua motivazione no**, ed è stata
corretta in `CLAUDE.md`.

Per isolare la variabile è servito un relay TCP temporaneo da `192.168.86.45:9899` verso il
`127.0.0.1:9888` di `adb forward`: senza, l'unico indirizzo raggiungibile sarebbe stato il
loopback, che è proprio quello che non distingue i due casi.

---

## Una decisione del piano rivista in corsa

**D-02 (`WebServiceTest`: TLS o chiaro) è diventata irrilevante.** Il piano prevedeva di scegliere
fra convertire i dieci test esistenti a client TLS o lasciarli in chiaro iniettando il socket
semplice. Guardando il codice si è visto che quei test **non fanno richieste HTTP**: provano
interruttore, custode e sorte del token, e l'unico contatto con la rete è un `connect` per vedere
se la porta accetta — che sopra TLS funziona identico, perché l'handshake avviene alla prima
lettura. Non è servito convertire niente. In compenso è stato aggiunto un test che mancava e che
nessuna delle due opzioni copriva: **un giro completo sopra TLS attraverso il servizio vero** —
pagina, `/api/eventi`, e `403` senza token — invece che con un gestore finto.

---

## Resta da fare

1. **Prova su rete reale** fra telefono e computer sullo stesso Wi-Fi, con **cattura del traffico**
   (`tcpdump`/Wireshark) a confermare che token e titoli non passino in chiaro. In questo ambiente
   non è eseguibile: il telefono di prova è su `192.168.1.0/24` e il PC su `192.168.86.0/24`, due
   reti diverse. Tutto ciò che si poteva provare passando da `adb forward` è stato provato.
2. **TC-17 completo:** l'impronta è stata verificata identica dopo il riavvio del *processo*; resta
   da vederla identica dopo il riavvio del **telefono**.
3. **Firefox:** l'eccezione permanente non è stata provata (la prova è stata fatta su Chromium).
4. **Nulla di bloccante:** la feature è completa e coerente con il piano.

Idee rimandate, già annotate: CA locale (e con essa secure context e PWA), mTLS, keep-alive,
togliere il token dalla query string, redirect da `http://` riconoscendo il primo byte.
