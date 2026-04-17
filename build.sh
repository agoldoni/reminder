#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

# --- Configurazione ---
ANDROID_SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_HOME="$ANDROID_SDK"
BUILD_TYPE="${1:-debug}"   # debug | release

# Verifica Android SDK
if [ ! -d "$ANDROID_SDK" ]; then
    echo "[ERRORE] Android SDK non trovato in: $ANDROID_SDK"
    echo "         Imposta la variabile ANDROID_HOME oppure installa Android Studio."
    exit 1
fi

# Installa gradlew se non presente
if [ ! -f "./gradlew" ]; then
    echo "[INFO] gradlew non trovato, lo scarico..."
    gradle wrapper --gradle-version 8.2
fi

chmod +x ./gradlew

echo "[INFO] Build type: $BUILD_TYPE"

case "$BUILD_TYPE" in
    debug)
        echo "[INFO] Avvio build debug..."
        ./gradlew assembleDebug
        APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
        ;;
    release)
        # Verifica che le credenziali di firma siano disponibili
        KEYSTORE_FILE="${KEYSTORE_FILE:-$HOME/.android/release-key.jks}"
        if [ ! -f "$KEYSTORE_FILE" ]; then
            echo "[ERRORE] Keystore non trovato: $KEYSTORE_FILE"
            echo "         Imposta KEYSTORE_FILE per un percorso diverso."
            exit 1
        fi
        if [ -z "${KEYSTORE_PASSWORD:-}" ] || [ -z "${KEY_PASSWORD:-}" ]; then
            echo "[ERRORE] Per il build release servono le variabili d'ambiente:"
            echo "         export KEYSTORE_PASSWORD=<password>"
            echo "         export KEY_ALIAS=<alias>        (default: release)"
            echo "         export KEY_PASSWORD=<password>"
            exit 1
        fi
        echo "[INFO] Avvio build release..."
        ./gradlew assembleRelease
        APK_PATH="app/build/outputs/apk/release/app-release.apk"
        ;;
    clean)
        echo "[INFO] Pulizia progetto..."
        ./gradlew clean
        echo "[OK] Clean completato."
        exit 0
        ;;
    *)
        echo "[ERRORE] Build type non valido: '$BUILD_TYPE'"
        echo "         Uso: $0 [debug|release|clean]"
        exit 1
        ;;
esac

if [ -f "$APK_PATH" ]; then
    echo ""
    echo "[OK] Build completata con successo!"
    echo "     APK: $PROJECT_DIR/$APK_PATH"
else
    echo "[ERRORE] APK non trovato. Controlla i log sopra."
    exit 1
fi
