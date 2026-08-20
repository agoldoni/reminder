# T-01 — Validazione della toolchain (esito)

**Data:** 2026-08-20
**Esito:** ✅ **superato** — la terna Kotlin / AGP / Compose Multiplatform con Room KMP compila su
Android e desktop, e apre un database reale.
**Prototipo:** throw-away, costruito fuori dal repository (scratchpad di sessione), non committato.

---

## 1. Stack validato

| Componente | Versione | Note |
|---|---|---|
| Gradle | **8.14.5** | ultima 8.x; nessun bisogno di Gradle 9 |
| Android Gradle Plugin | **8.13.2** | ultima 8.x; **niente AGP 9** |
| Kotlin | **2.3.21** | fissata da KSP, vedi §3.1 |
| KSP | **2.3.11** | ultima pubblicata |
| Compose Multiplatform | **1.11.1** | ultima stabile |
| Room | **2.8.4** | artefatti KMP `room-runtime-android` / `-jvm` |
| androidx.sqlite | **2.7.0** | `sqlite-bundled` solo su desktop, vedi §3.4 |
| JDK | 21 (Temurin, SDKMAN) | `jvmTarget` 17 su entrambi i target |
| compileSdk / minSdk | 36 / 26 | minSdk invariato rispetto all'app attuale |

## 2. Cosa è stato verificato

| Verifica | Esito |
|---|---|
| Struttura `:shared` (`androidTarget()` + `jvm("desktop")`) + `:androidApp` + `:desktopApp` | ✅ |
| Room KMP con `@ConstructedBy` + `expect object … : RoomDatabaseConstructor<…>` | ✅ codegen su `ksp/android` e `ksp/desktop` |
| Apertura reale del DB su desktop: `INSERT` + `SELECT` | ✅ 1 riga riletta, file SQLite da 4.096 byte |
| DAO con `suspend` **e** `Flow` | ✅ compilati su entrambi i target |
| Source set intermedio `jvmSharedMain` con `actual` che usa `java.text.SimpleDateFormat` | ✅ condiviso da Android e desktop, nessuna riscrittura su kotlinx-datetime |
| Stessa `@Composable` Material3 compilata per Android e desktop | ✅ |
| API Compose Desktop (`application { Window { … } }`) | ✅ compila e linka |
| Export dello schema Room (`room { schemaDirectory(...) }`) | ✅ `schemas/proto.ProtoDb/1.json` generato |
| APK debug prodotto | ✅ 16.549.757 byte, con le librerie native attese |

## 3. Decisioni tecniche emerse dal prototipo

### 3.1 È KSP a fissare la versione di Kotlin, non Compose

L'ultima KSP pubblicata è la **2.3.11**: non esiste una KSP per Kotlin 2.4 (`404` su Maven
Central). Poiché Room genera il codice via KSP, **Kotlin 2.4.10 non è utilizzabile** e la scelta
si ferma a **Kotlin 2.3.21**. Da rivalutare quando uscirà la KSP per la linea 2.4.

### 3.2 AGP 9 non serve: **T-03 si può togliere dal piano**

Con AGP **8.13.2** l'intero stack funziona. `applicationVariants` — usata per rinominare l'APK
di release in `reminder-<versionName>.apk` — resta valida, quindi la riscrittura con la Variant
API (T-03, 0,5 gg) non è più necessaria. Torna in gioco solo il giorno in cui si passerà ad AGP 9.

### 3.3 La gerarchia dei source set va dichiarata con il template, non con `dependsOn`

Il `dependsOn` manuale (`androidMain.dependsOn(jvmSharedMain)`) viene rifiutato quando è attivo
il template di default. La forma corretta è:

```kotlin
applyDefaultHierarchyTemplate {
    common {
        group("jvmShared") {
            withAndroidTarget()
            withJvm()
        }
    }
}
```

Il source set `jvmSharedMain` viene creato dal template ed è il posto dove finiranno
`OdsExporter` e le formattazioni di data dell'app reale.

### 3.4 Driver SQLite diverso per piattaforma: 1,15 MiB di APK risparmiati

Misura sullo stesso prototipo, unica differenza il driver su Android:

| Driver su Android | APK debug |
|---|---|
| `BundledSQLiteDriver` (SQLite compilato dentro l'app, 4 ABI) | 17.753.889 byte |
| **`AndroidSQLiteDriver`** (SQLite di sistema) | **16.549.757 byte** |

Differenza: **−1.204.132 byte (−1,15 MiB)**. Il driver di sistema è anche quello che l'app usa
già oggi, quindi non cambia il comportamento su Android. Scelta adottata: `AndroidSQLiteDriver`
su Android, `BundledSQLiteDriver` su desktop (dove SQLite non è garantito dal sistema).
Conseguenza: `androidx.sqlite:sqlite-bundled` va dichiarata **solo** in `desktopMain`.

### 3.5 Warning da mettere a tacere

Room genera l'`actual object` del costruttore del database, e gli `expect`/`actual` object sono
ancora in Beta: ogni build produce due warning. Si silenziano con

```kotlin
compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
```

## 4. Conseguenze sul piano

- **T-01** chiuso.
- **T-03** rimosso (−0,5 gg): il piano passa da 46,5 a **46,0 gg**.
- Le versioni della tabella in [phase-2-analysis.md](phase-2-analysis.md) §B.6 non sono più
  "proposte" ma **validate**.
- **T-08** eredita due vincoli concreti: driver per piattaforma (§3.4) e forma della gerarchia (§3.3).

---

*Il prototipo è stato eliminato dopo la verifica: il suo valore è questo documento.*
