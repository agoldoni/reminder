# Stato del lavoro — port desktop Linux

**Aggiornato:** 2026-08-20
**Branch:** `feature/desktop-linux` (9 commit, non ancora unito in `main`)
**Ultimo commit:** `b971848`

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

**Avanzamento:** 27,2 gg completati su 46,0 stimati. Restano **18,8 gg**, quasi tutti di tranche 2.

**25 test automatici** (prima non ce n'erano): export ODS, formattazione date, scheduler desktop,
autostart, istanza singola, riprogrammazione al boot (strumentato).

## Come si lavora

```bash
./build.sh debug            # APK Android
./build.sh desktop          # AppImage (serve appimagetool nel PATH o in $APPIMAGETOOL)
./gradlew :desktopApp:run   # avvia l'app desktop
./gradlew :shared:desktopTest :desktopApp:test                    # test JVM
ANDROID_SERIAL=emulator-5554 ./gradlew :shared:connectedDebugAndroidTest   # test strumentati
```

## Prossimo passo

**T-17 — schema v3 e migrazione 2→3**: identità globale (`uuid`), `updatedAt`, tombstone,
soft-delete nel DAO. È il prerequisito di tutta la tranche 2 (discovery, pairing, replica).
Ordine previsto: T-17 → T-18 (discovery mDNS) → T-19 (pairing) → T-20 (motore di replica) →
T-21 (integrazione) → T-22 (UI stato sync), con i test T-25, T-26, T-29 e T-30 a seguire.

Il dettaglio task per task è in [phase-3-implementation-plan.md](phase-3-implementation-plan.md).

## Punti aperti

1. **Riprogrammazione al boot su device reale**: `BOOT_COMPLETED` è un broadcast protetto e non
   si può simulare da adb. La logica è coperta da un test strumentato; il cablaggio
   receiver + manifest si verifica solo riavviando il telefono.
2. **0,3 gg di documentazione** (T-32) sui requisiti di rete: ha senso scriverla insieme alla sync.
3. **Test di migrazione (T-26)**: possibile solo dalla v3 in poi — lo schema v1 non è mai stato
   esportato, quindi la migrazione 1→2 resta non testabile.

## Vincoli d'ambiente da ricordare

- Il telefono di test (Redmi Note 7, Android 10) chiede conferma **a ogni** installazione via ADB
  ("Installa tramite USB", si auto-nega dopo 8 secondi): le run strumentate automatiche vanno
  fatte su emulatore.
- Emulatore utilizzabile: **`Emulator_x86_64`**. `Medium_Phone_API_33` è ARM e non parte più.
- `sqlite3` non è eseguibile via `run-as` su quel telefono: per manipolare i dati di test si passa
  dalla UI.
- Senza `xdotool` non si possono simulare click sull'app desktop: le schermate si verificano
  passando una rotta iniziale all'avvio.
