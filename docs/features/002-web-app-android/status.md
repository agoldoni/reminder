# Web app locale su Android — Stato

**Aggiornato:** 2026-08-21
**Stato:** implementata e verificata sul campo (emulatore). In attesa di prova su rete reale.
**Piano:** [phase-3-implementation-plan.md](phase-3-implementation-plan.md)

---

## Task

| ID | Task | Stato |
|---|---|---|
| T-01 | `AppSettings` con `webEnabled`, 5 implementazioni aggiornate | ✅ |
| T-02 | `WEB_PORT = 9888`, `PorteWebTest` + `PorteTest` esteso, `CLAUDE.md` corretto | ✅ |
| T-03 | Verifica che gli asset arrivino nell'artefatto | ✅ — vedi sotto, con un cambio di rotta |
| T-04 | `HttpMessages`: parsing, limiti, header di sicurezza | ✅ 21 test |
| T-05 | `HttpServer`: ciclo di ascolto, porta 0, arresto pulito | ✅ 9 test |
| T-06 | `AccessToken`: token, confronto a tempo costante, soglia | ✅ 10 test |
| T-07 | `StaticAssets`: elenco chiuso, tipi MIME, classloader | ✅ |
| T-08 | `WebPayload`: busta versionata, `id`, `notificationMillis`, impronta | ✅ |
| T-09 | `Router`: smistamento, accesso, `304`, `404`/`405` | ✅ 15 test |
| T-10 | `WebService`: interruttore, ciclo di vita, stato | ✅ 10 test |
| T-11 | Frontend: pagina, stili, script, manifest, icone | ✅ |
| T-12 | `AppContainer`, `WebViewModel`, sezione in `SyncScreen` | ✅ |
| T-13 | `ReminderApp` + `MainActivity` | ✅ |
| T-14 | Verifica sul campo | ✅ su emulatore; ⏳ su rete reale |
| T-15 | `CLAUDE.md`, documenti, questo file | ✅ |

**Suite:** `./gradlew :shared:desktopTest :desktopApp:test` → **188 test, 0 falliti**.

---

## Verifiche eseguite

Emulatore `Emulator_x86_64` (Android 14), APK debug, `adb forward tcp:9888 tcp:9888`, browser
Chromium in sessione X separata (`tools/sessione-x.sh browser`).

| TC | Esito |
|---|---|
| TC-01…TC-15 (automatici) | ✅ verdi |
| TC-16 · lista coincidente con l'app e aggiornamento automatico | ✅ quattro promemoria, stesso ordine, stesse fasce cromatiche, stesse date; la pagina si è aggiornata da sola due volte senza ricaricare |
| TC-17 · a dati fermi non si ridisegna | ✅ `304` su `If-None-Match` verificato con `curl` |
| TC-19 · rotazione: il token non cambia | ✅ verticale → orizzontale → verticale, stesso token, `200` ogni volta |
| TC-20 · background/primo piano | ✅ in background la connessione è **rifiutata**; al ritorno `200` con lo stesso token; nessun crash |
| TC-21 · irraggiungibile con pagina aperta | ✅ la pagina mostra l'avviso in rosso invece di spacciare per fresca una lista vecchia; al ritorno l'avviso sparisce da solo |
| TC-23 · resa | ✅ Chromium, tema chiaro e scuro, larghezza stretta senza scorrimento orizzontale, formato `dd/MM/yyyy HH:mm` identico all'app |
| TC-24 · non-regressione | ✅ suite completa verde; allarmi, sincronizzazione ed export non toccati |
| TC-18 · fascia che cambia al passare dell'ora senza rete | ⏳ non osservata (richiede di aspettare la scadenza di un evento) |
| TC-22 · cambio di rete a schermata aperta | ⏳ non verificabile su emulatore |

Protocollo verificato con `curl`: `403` identici per token assente e sbagliato; `405` su
POST/PUT/DELETE; `404` su percorso ignoto; `400` su `/../CLAUDE.md`, `/..%2fetc%2fpasswd`,
`/%2e%2e/x`, `//app.css`; asset senza token `200`.

---

## Due cose che la realizzazione ha cambiato rispetto al piano

**1. Gli asset non stanno in `androidMain/resources`, ma in `shared/src/webAssets/`.**
La verifica T-03 ha confermato che `androidMain/resources` finisce nell'APK (la sonda si estraeva
da `web/sonda.txt`), quindi la decisione D-03 reggeva. Ma tenendoli lì, i **test del router** non
potevano leggerli: la cartella non è sul classpath del target desktop, e la prova che ogni asset
dichiarato esiste davvero sarebbe stata impossibile da automatizzare. Sono stati spostati in una
cartella neutra dichiarata a mano a entrambi i target in `shared/build.gradle.kts`. Costa una riga
per target e in cambio `RouterTest` verifica che tutti e sei i file dell'elenco chiuso siano
servibili — il guasto che darebbe una pagina bianca si vede in `gradlew test`, non sul telefono.

**2. «Progressive» non è impedito dal service worker mancante, ma dal contesto non sicuro.**
Nel browser di prova Chromium offre **«Installa»** sulla pagina servita: manifest e icone sono
validi e Chrome non pretende più un service worker per l'installazione. Il contesto lì è sicuro
perché l'indirizzo è `127.0.0.1` (via `adb forward`). Su un indirizzo di rete locale il contesto
non è sicuro e l'installazione non viene offerta — il risultato per l'utente è quello previsto dal
piano, ma la ragione scritta nei documenti era imprecisa ed è stata corretta.

---

## Difetti trovati e corretti in corsa

- **Il `500` non partiva.** Un difetto del gestore usciva da `client.use`, che chiudeva il socket
  prima che si potesse rispondere: il browser restava davanti a una connessione morta. Ora si
  cattura dentro l'ambito del socket. Presidiato da un test.
- **Il testo arrivava a filo dell'interruttore** nella sezione dell'interfaccia. Spaziatura
  esplicita nella `Row`.

---

## Resta da fare

1. **Prova su rete reale** fra telefono e computer sullo stesso Wi-Fi: è l'unica che dice qualcosa
   su R3 (reti che isolano i client) e su TC-22.
2. **TC-18**: osservare la fascia cromatica cambiare al passare dell'ora senza traffico.
3. **Nulla di bloccante**: la feature è completa e coerente con il piano.

Idee rimandate, già annotate nel piano: codice QR per l'indirizzo, scrittura dal browser
(che richiederà di riaprire R2 — un token in query string che autorizza mutazioni è un'altra
cosa), attivazione su desktop.
