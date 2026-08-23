# 006 — L'indirizzo diventa fisso · Stato

**Aggiornato:** 2026-08-23
**Piano:** [phase-3-implementation-plan.md](phase-3-implementation-plan.md) v1.2

## Stato dei task

| ID | Task | Stato |
|---|---|---|
| T-01 | `Jwt.kt` | ✅ |
| T-02 | `JwtTest` — batteria avversariale (TC-02) | ✅ 23 casi verdi |
| T-03 | `ChiaveFirma.kt` | ✅ |
| T-04 | `ChiaveFirmaTest` | ✅ 14 casi verdi |
| T-05 | `AccessToken` riscritto | ✅ |
| T-06 | `AccessTokenTest` riscritto | ✅ 21 casi verdi |
| T-07 | `Router` + `/` pubblica | ✅ |
| T-08 | `RouterTest` esteso + `AttesaLungaTest` adattato | ✅ `RouterTest` da 34 a 40 casi |
| T-09 | `WebService`: chiave, coniatura, `revoke()`, guasti | ✅ |
| T-10 | `WebServerController` / `WebStatus` | ✅ |
| T-11 | `WebServiceTest` — le due asserzioni rovesciate | ✅ 18 casi (erano 15) |
| T-12 | `app.js`: frammento, `localStorage`, header | ✅ |
| T-13 | `app.js`: i due stati nuovi e la bandiera | ✅ |
| T-14 | `SezioneWebApp` + `WebViewModel`: revoca, testi | ✅ |
| T-15 | `JwtPiattaformaTest` (strumentale) | ✅ 3 casi verdi **su API 26** |
| T-16 | Commenti che dichiaravano il comportamento vecchio | ✅ |
| T-17 | Checklist manuale nel browser (TC-15…TC-20) | ✅ 6 su 6 — **e ha trovato un difetto**, vedi sotto |
| T-18 | Verifica sul dispositivo | ✅ M1, M2, M3, M4 chiuse con i numeri |
| T-19 | `CLAUDE.md` + questo file | ✅ |

**Verifiche automatiche:** `:shared:desktopTest` + `:desktopApp:test` = **379 test, 0 fallimenti**.
`:shared:connectedDebugAndroidTest` (pacchetto `web`) = **10 test verdi su API 26**.
`:androidApp:assembleDebug` verde.

**Test preesistenti modificati, e la modifica è dichiarata** — non si poteva evitarlo, perché la
feature rovescia due comportamenti:

| Dove | Che cosa è cambiato |
|---|---|
| `AccessTokenTest` | Riscritto. «Spegnere invalida entrambi i token» → **«coniare non revoca, revocare sì»**. Spariti i casi su alfabeto e digitabilità: un token non si digita più |
| `WebServiceTest` | «Spegnere chiude la porta, congeda il custode e **invalida il token**» → **«…ma non revoca niente»**, con la prova sul filo che il token di prima riceve ancora `200`. L'URL atteso passa da `?t=` a `#access=`. Aggiunti quattro casi: revoca, revoca a interruttore spento, sopravvivenza a un servizio nuovo, `?t=` che non autentica |
| `WebServiceTest` (guasto della cartella) | Il messaggio ora nomina **la chiave**, non il certificato: `apri()` prepara la chiave per prima, e con una cartella inutilizzabile è lei a fallire per prima. L'asserzione verifica la proprietà che contava — «il messaggio nomina la causa vera, non la porta» — non più la parola «certificato» |
| `RouterTest`, `AttesaLungaTest` | Solo il modo di costruire la richiesta: la credenziale passa dalla query all'header |

---

## La prova nel browser (T-17), e il difetto che ha trovato

Emulatore **API 26** (`Api26_x86_64`), APK di debug, `adb forward tcp:9888`, Chromium in una
sessione X separata (`tools/sessione-x.sh`).

| # | Che cosa | Esito |
|---|---|---|
| TC-15 | Aperto `…/#access=<jwt>`: il frammento sparisce dalla barra, resta `https://127.0.0.1:9888` | ✅ |
| TC-15 | **Ricaricato l'indirizzo nudo**: la lista compare | ✅ **è la feature** |
| TC-16 | Spento e riacceso l'interruttore: il segnalibro funziona ancora | ✅ |
| TC-17 | `am force-stop` + riapertura: il segnalibro funziona ancora | ✅ |
| TC-18 | Revocato dal telefono: la pagina mostra «indirizzo non più valido», e un ricaricamento mostra «apri dal telefono» — quindi il token conservato era stato cancellato | ✅ |
| TC-19 | Indirizzo di sola lettura: nessun comando; `POST` a mano → `403`; database intatto | ✅ |
| TC-20 | Nessun token in `path` o `query` in tutta la sessione | ✅ |
| — | Indirizzo che comanda: «Nuovo», «Fatto», «Modifica»; un «Fatto» dal browser toglie la riga e apre la barra dell'annullamento | ✅ |

### Il difetto: un permesso che arriva a pagina già aperta

**Trovato dalla prova manuale, e non si sarebbe visto altrove.** Incollando l'indirizzo che comanda
in una scheda che stava già su `https://IP:9888/`, la barra mostrava il frammento nuovo e la pagina
non cambiava: navigare da `/` a `/#access=…` è una navigazione **nello stesso documento**, quindi
il browser non ricarica e lo script non riparte.

Non è un caso limite: succede tutte le volte che si consegna un indirizzo dopo una revoca, o si
passa da quello di lettura a quello che comanda. Ed è il genere di guasto peggiore, perché **non si
vede**: l'utente ha fatto la cosa giusta e non succede niente.

Chiuso con un ascoltatore di `hashchange` (D-16 del piano) che adotta il token nuovo, azzera
l'`ETag` — i permessi stanno dentro il corpo, quindi l'impronta cambia — e **interrompe la richiesta
in corso**, perché una risposta `401` in volo cancellerebbe il permesso appena arrivato.
Riverificato: la pagina passa da sola alla vista con i comandi, senza ricaricare.

---

## Misure (T-18)

Emulatore API 26, `adb forward`, `curl`:

| Richiesta | Codice |
|---|---|
| `GET /` senza credenziali | **200** (guscio) |
| `GET /app.js` senza credenziali | 200 |
| `GET /api/eventi` senza credenziali | **401** |
| `GET /api/eventi` con `Bearer` di lettura | 200 |
| `GET /api/eventi?t=<jwt valido>` | **401** (la query non autentica più) |
| `authorization: bearer <jwt>` (minuscolo) | 200 (RFC 7235) |
| `Authorization: Bearer` (senza token) | 401 |
| `POST` con token di lettura | **403**, database intatto |
| `POST` senza token | **401** |
| `POST` con `{"alg":"none"}` | **401** |
| `POST`/`PUT` con token di scrittura | 201 / 200 |

**M1 — sopravvivenza al `force-stop`.** Porta morta dopo il kill (`000`); riaperta l'app, **lo
stesso token di prima** → `200`. L'indirizzo mostrato dall'app è invece diverso (buono di consegna
fresco) e funziona anch'esso: coniare non revoca.

**M2 — spegni/riaccendi.** Porta chiusa (`000`), riaperta: il token di **due riavvii prima** → `200`.

**M3 — niente segreto sul filo.** Zero occorrenze del token in `path` o `query`.

**M4 — revoca.** Subito dopo la conferma: vecchia lettura `401`, vecchia scrittura `401`, porta
ancora aperta (`200` su `/`), indirizzo nuovo già mostrato dalla schermata e già valido (`200`).

**Scadenza.** Coniata il 23/08/2026, la schermata dichiara «Vale fino al 22/09/2026» — trenta giorni
esatti. Payload verificato decodificando il segmento: `{"v":1,"p":"lettura","iat":…,"exp":…}`.

**R-07 chiuso.** `java.util.Base64` e `HmacSHA256` funzionano su **API 26**, che è il `minSdk`: il
token si conia e si verifica sul dispositivo, e la chiave sopravvive in `filesDir`.

---

## Limiti della prova, da sapere

- **Emulatore, non telefono vero.** `force-stop` è una morte del processo comandata, non quella che
  Android infligge sotto pressione di memoria. La differenza non tocca ciò che si voleva provare —
  la chiave sta su disco e la si rilegge — ma va detto.
- **Loopback via `adb forward`, non Wi-Fi.** Stessa limitazione dichiarata dalla 005. Qui pesa meno,
  perché non si misurava nessun tempo.
- **La scadenza a trenta giorni non è stata provata sul dispositivo**, solo nei test a tempo
  virtuale (TC-03, TC-07): provarla davvero vorrebbe dire spostare l'orologio del telefono.

## Seguiti naturali, non fatti qui

1. **`START_STICKY` per `WebServerService`** (R-12): adesso è possibile, ma vuole che sia il servizio
   ad aprire la porta invece dell'Activity.
2. **Un token per dispositivo, con etichetta e revoca singola.** `v` nel payload le lascia la porta
   aperta; serve una lista di token emessi e una schermata che li mostri.
3. **Rinnovo a scorrimento**, se la riconsegna mensile darà fastidio. Escluso di proposito (D-02).
