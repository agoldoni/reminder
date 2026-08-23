# 005 — La pagina si aggiorna subito, e solo dove serve · Stato

**Aggiornato:** 2026-08-23
**Piano:** [phase-3-implementation-plan.md](phase-3-implementation-plan.md) v1.1

## Stato dei task

| ID | Task | Stato |
|---|---|---|
| T-01 | Misura Doze a schermo spento (D-06) | ✅ **Misurata** — vedi §Misure |
| T-02 | `createFlow` con `AndroidSQLiteDriver` | ✅ **Funziona** sul dispositivo |
| T-03 | `Cambiamenti.kt` | ✅ |
| T-04 | `CambiamentiTest` — 8 casi | ✅ verdi |
| T-05 | Ramo di attesa in `Router.eventi()` | ✅ |
| T-06 | `AttesaLungaTest` — 8 casi | ✅ verdi |
| T-07 | `HttpServerTest`: una connessione appesa non blocca le altre | ✅ verde |
| T-08 | Cablaggio `WebService` + `ReminderApp` | ✅ |
| T-09 | Dati sul nodo invece che nella closure (R-11) | ✅ |
| T-10 | `riconcilia` pura + `creaScheda`/`aggiornaScheda` | ✅ |
| T-11 | Applicazione al DOM con riordino | ✅ |
| T-12 | `aggiornaColori()` senza ricostruzione | ✅ |
| T-12b | Evidenziazione della scheda cambiata (D-09) | ✅ |
| T-13 | Ritmo nuovo: ciclo auto-schedulato, `AbortController`, backoff, guardia | ✅ |
| T-14 | Commenti che dichiaravano il comportamento vecchio | ✅ |
| T-15 | Checklist manuale nel browser | ✅ **8 su 10** — due non eseguibili, vedi sotto |
| T-16 | Misure sul dispositivo | ✅ |
| T-17 | `CLAUDE.md` + questo file | ✅ |

**Verifiche automatiche:** `:shared:desktopTest`, `:desktopApp:test` e `:androidApp:assembleDebug` verdi.
Nessun file di test preesistente è stato modificato — **M-4 rispettata**.

**Ambiente della prova sul campo:** Redmi Note 7, Android 10, APK di debug installato il
2026-08-23; interruttore della web app già acceso dall'utente (lasciato com'era). I promemoria
creati per la prova sono stati eliminati alla fine: **0 residui, 19 promemoria reali intatti**.

---

## Misure (T-01, T-02, T-16)

### D-06 è chiusa: il long-poll regge

Telefono in **Doze forzato** (`dumpsys battery unplug` + `dumpsys deviceidle force-idle`,
`mState=IDLE`), schermo spento, `MainActivity` fermata, processo tenuto vivo dal solo
`WebServerService` (`isForeground=true` verificato).

| | Domanda | Previsione | **Misura** |
|---|---|---|---|
| a | La connessione appesa sopravvive 30 s? | Sì | ✅ **Sì** — `304` a 25,06 s, cioè è arrivata intera alla scadenza |
| b | All'arrivo di una scrittura, in quanto parte la risposta? | Sotto il secondo | ✅ **Mediana 70 ms** su 10 prove (min 58, max 156) |
| c | La scadenza slitta a CPU sospesa? | **Slitta** | ❌ **No**: 25,06 s in Doze contro 25,07 s da sveglio |
| d | Con **otto** attese appese il telefono resta in Doze? | Sì | ✅ **Sì**, `mState=IDLE` e **zero wake lock** dell'app |

**La previsione (c) era sbagliata, e il perché conta più della misura.** Vedi il limite qui sotto:
con l'USB attaccato la CPU non entra in sospensione profonda, quindi la domanda «`delay()` scade a
CPU sospesa?» **non è stata davvero posta**. La risposta onesta è «non slitta in queste
condizioni», non «non slitta mai». Resta che, se slittasse, sarebbe innocuo — è ciò che il piano
diceva, e nessuna misura lo ha contraddetto.

**L'unico esito che avrebbe cambiato il piano non si verifica**: otto connessioni appese non
impediscono il Doze e non costano un wake lock.

### Il limite della prova, che va conosciuto

Il telefono è su `192.168.1.0/24`, questa macchina su `192.168.86.0/24`: **sottoreti diverse**,
quindi l'HTTP è passato da `adb forward`, cioè da **USB e non dal Wi-Fi**. Due conseguenze:

1. **La radio Wi-Fi non è stata provata.** Che una connessione appesa sopravviva al risparmio
   energetico del Wi-Fi con lo schermo spento resta da verificare su una rete dove i due
   dispositivi si vedono.
2. **L'USB attaccato tiene probabilmente sveglia la CPU**, ed è la spiegazione più semplice della
   riga (c).

Ciò che la prova dimostra comunque, e non è poco: il processo non viene congelato, la coroutine
sopravvive, il socket resta aperto, il segnale di Room arriva e la risposta parte — in Doze.

### Le altre misure

| Che cosa | Misura |
|---|---|
| **T-02 — `createFlow` con `AndroidSQLiteDriver`** | ✅ **Funziona.** Ogni risveglio misurato nasce dall'invalidazione di Room: se non funzionasse, l'attesa scadrebbe sempre in `304` |
| **US-006 sul dispositivo** — scrittura che **non** passa da `ScrittureWeb` | ✅ Cancellazione fatta **dall'app**: attesa svegliata in **560 ms** |
| **Tetto delle attese** — la nona richiesta con otto aperte | ✅ `304` in **0,07 s** invece di aspettare: degrada al polling, non a un errore |
| **R-03 — costo di un handshake TLS** (niente keep-alive) | 13–32 ms, **mediana ~17 ms**. Un'attesa scaduta ogni 25 s costa quanto il polling di prima ogni 30 s |
| **M-1 — ritardo** | **70 ms** mediano, contro un obiettivo di **2 000 ms** |
| **M-2 — nodi toccati** | 1, verificato sia nel banco JS sia a schermo |

---

## Checklist manuale (T-15) — 8 su 10

Eseguita con `tools/sessione-x.sh browser`, Chromium su `DISPLAY=:2`, contro il telefono vero.

| | Che cosa | Esito |
|---|---|---|
| **V-01** | Modifica dal telefono: cambia una scheda sola | ✅ solo quella, le altre immobili |
| **V-02** | Elenco scorso in fondo, modifica in arrivo: `scrollY` fermo | ✅ scatti prima/dopo **pixel per pixel identici** tranne la scheda cambiata |
| **V-03** | Selezione di testo che sopravvive | ⚪ **non eseguita** — vedi «Limiti del pilotaggio». È un corollario del riuso dei nodi, che V-01 dimostra |
| **V-04** | Data spostata: la scheda cambia posto e non se ne creano due | ✅ spostata fra le due schede giuste, **una sola volta** |
| **V-05** | **R-11:** modifica dal telefono, poi «Fatto» sulla stessa scheda | ✅ **nessun conflitto.** È la prova che i dati vivono sul nodo: senza T-09 sarebbe stato un `409` |
| **V-06** | «Fatto» dal browser: sparisce una scheda, barra, «Annulla» | ✅ e la scheda ripristinata torna **evidenziata**, che è giusto: è un reinserimento |
| **V-07** | Modifica dal telefono mentre il modulo è aperto | ✅ modulo intatto, e dietro la scheda mostra ancora il **titolo vecchio**. Alla chiusura la modifica compare — **senza evidenziazione**, il secondo caso di R-18 |
| **V-08** | Scheda nascosta: nessuna attesa appesa | ✅ connessioni sulla porta 9888 sul telefono: **1 → 0 → 1** fra visibile, nascosta e di nuovo davanti |
| **V-09** | Lettore di schermo | ⚪ **non eseguita** — non disponibile in questo ambiente |
| **V-10** | **D-09:** evidenziazione che compare e svanisce; niente al ricaricamento | ✅ contorno presente a 0,7 s, sparito a 3,2 s; nessuna evidenziazione al primo caricamento né alla chiusura del modulo |

### Limiti del pilotaggio, da sapere per la prossima volta

`tools/Pilota.java` guida bene il **mouse** in Xephyr, ma **non riesce a scrivere nei campi di
input di Chromium**: `type` passa dagli appunti (che in quel display non arrivano al browser) e
anche i `key` singoli finiscono al documento invece che al campo — `key END` scorreva la pagina
mentre il cursore lampeggiava nel titolo. È la ragione per cui V-03 non è stata eseguita e per cui
V-07 è stata verificata sul comportamento del modulo invece che sul testo digitato.

---

## Scostamenti dal piano

**Una difesa in più, non prevista: `INTERVALLO_MINIMO` = 5 s.** Il piano dava per scontato che un
ciclo che si riprogramma alla fine di ogni giro degradasse «al polling» quando il server risponde
subito. Non è vero: senza un pavimento fra due richieste degraderebbe in un **ciclo stretto** che
martella la porta, e i due casi in cui il server risponde subito sono entrambi reali — una versione
precedente che `attendi` non lo conosce, e il tetto delle attese superato, che è proprio il caso in
cui si vorrebbe *meno* traffico e non di più.

**Una precisazione su `riconcilia`.** Il piano (§5.8) parlava di operazioni `inserita`/`aggiornata`/
`spostata`. I tipi implementati sono `inserita`/`aggiornata`/**`invariata`**: una scheda che si
sposta senza cambiare *è* invariata, perché il riordino nasce dal fatto che un'altra ha cambiato
data. La posizione la sistema chi applica le operazioni, non chi le calcola.

## Che cosa resta

- **V-03 e V-09**, per i motivi sopra: non sono difetti noti, sono verifiche non eseguite.
- **La prova su Wi-Fi vero**, quando telefono e computer si trovano sulla stessa sottorete. È
  l'unica parte di D-06 che questa sessione non ha potuto chiudere.
