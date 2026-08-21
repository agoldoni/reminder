# Stato del lavoro — port desktop Linux

**Aggiornato:** 2026-08-21
**Branch:** `feature/desktop-linux` (10 commit, non ancora unito in `main`)

---

## Dove siamo

**Tranche 1 — app desktop: completa.** L'app gira su Android e su Linux dalla stessa codebase
Compose Multiplatform, ed è distribuibile come AppImage.

| | Android | Desktop Linux |
|---|---|---|
| Lista, editor, completati | ✅ verificato su device | ✅ verificato con dati reali |
| Allarmi | ✅ `AlarmManager`, verificato in `dumpsys` | ✅ coroutine per evento + recupero all'avvio |
| Notifiche con azioni | ✅ snooze +5 min verificato a runtime | ✅ `notify-send` con azioni, verificato su Cinnamon |
| Export ODS | ✅ share intent, file aperto in LibreOffice | ✅ dialog di salvataggio nativo |
| Tray / autostart | — | ✅ tray, chiusura-a-tray, istanza singola, avvio al login |
| Distribuzione | APK debug 12,2 MB | AppImage 69,6 MB |

**Tranche 2 — sincronizzazione: iniziata.** Lo schema del database è pronto a ospitarla.

| | Stato |
|---|---|
| Schema v3 e migrazione 2→3 (T-17) | ✅ fatto, verificato anche su una copia del database reale |
| Test di migrazione (T-26) | ✅ 4 test in `desktopTest`, senza emulatore |
| Discovery, pairing, replica, UI (T-18…T-22) | ⏳ da fare |

**Avanzamento:** 29,2 gg completati su 46,0 stimati. Restano **16,8 gg**, tutti di tranche 2.

**29 test automatici** (prima non ce n'erano): export ODS, formattazione date, scheduler desktop,
autostart, istanza singola, riprogrammazione al boot (strumentato), migrazione 2→3.

## Come si lavora

```bash
./build.sh debug            # APK Android
./build.sh desktop          # AppImage (serve appimagetool nel PATH o in $APPIMAGETOOL)
./gradlew :desktopApp:run   # avvia l'app desktop
./gradlew :shared:desktopTest :desktopApp:test                    # test JVM
ANDROID_SERIAL=emulator-5554 ./gradlew :shared:connectedDebugAndroidTest   # test strumentati
```

## Che cosa è cambiato con lo schema v3

- `EventEntity` porta `uuid` (identità globale, indice unico), `updatedAt`, `deleted`/`deletedAt`
  e `origin`. `id` resta la chiave primaria: è il requestCode dei `PendingIntent`.
- **La cancellazione è logica.** `EventDao.delete()` non esiste più: al suo posto
  `softDelete(id, nowMillis)`, che lascia un tombstone. Tutte le letture dell'app filtrano
  `deleted = 0`; `getByUuid()` e `changedSince()` no, perché la sincronizzazione deve poter
  propagare le cancellazioni.
- **Ogni mutazione riceve l'istante da scrivere in `updatedAt`.** Il clock resta del chiamante,
  come già per `getFutureEvents`/`getOverdueEvents`, così i test lavorano a tempo virtuale.
- **`origin` è il dispositivo di nascita dell'evento, non l'ultimo che l'ha scritto.** Non cambia
  mai dopo l'inserimento, quindi non va aggiornato dalle mutazioni. L'identità del dispositivo è
  persistita per piattaforma da `localDeviceId()`: file `device-id` accanto al database su
  desktop, `SharedPreferences` su Android.
- **Modificare un evento parte sempre dalla riga esistente** (`EventEditViewModel` la rilegge e ci
  fa `copy()`): ricostruire l'entità da zero rigenererebbe l'`uuid` e romperebbe l'identità vista
  dagli altri dispositivi.

## Prossimo passo

**T-18 — discovery mDNS**: `NsdManager` su Android (con multicast lock), `jmdns` su desktop,
fallback manuale host/porta. Poi T-19 (pairing) → T-20 (motore di replica, con `upsertFromRemote`
e la regola LWW) → T-21 (integrazione) → T-22 (UI stato sync), con i test T-25, T-29 e T-30
a seguire.

Il dettaglio task per task è in [phase-3-implementation-plan.md](phase-3-implementation-plan.md).

## Punti aperti

1. **Il database reale non è ancora stato migrato.** La v3 non è reversibile: prima di avviare per
   la prima volta la nuova versione va copiato `~/.local/share/promemoria/reminder.db` (e il file
   `-wal`) e fatto un export ODS dal telefono. La migrazione è stata provata su una copia del
   database reale, che è rimasto alla v2.
2. **`upsertFromRemote()` non c'è.** Il piano la elencava fra le modifiche al DAO di T-17, ma le
   sue semantiche *sono* la regola di merge: nasce in T-20 insieme ai test che la definiscono.
3. **Riprogrammazione al boot su device reale**: `BOOT_COMPLETED` è un broadcast protetto e non
   si può simulare da adb. La logica è coperta da un test strumentato; il cablaggio
   receiver + manifest si verifica solo riavviando il telefono.
4. **0,3 gg di documentazione** (T-32) sui requisiti di rete: ha senso scriverla insieme alla sync.

## Vincoli d'ambiente da ricordare

- Il telefono di test (Redmi Note 7, Android 10) chiede conferma **a ogni** installazione via ADB
  ("Installa tramite USB", si auto-nega dopo 8 secondi): le run strumentate automatiche vanno
  fatte su emulatore.
- Emulatore utilizzabile: **`Emulator_x86_64`**. `Medium_Phone_API_33` è ARM e non parte più.
- `sqlite3` non è eseguibile via `run-as` su quel telefono: per manipolare i dati di test si passa
  dalla UI.
- Senza `xdotool` non si possono simulare click sull'app desktop: le schermate si verificano
  passando una rotta iniziale all'avvio.
