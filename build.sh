#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

# --- Configurazione ---
ANDROID_SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_HOME="$ANDROID_SDK"
BUILD_TYPE="${1:-debug}"   # debug | release | desktop

# La versione del prodotto sta in gradle.properties, unica per Android e desktop. Prima si
# leggeva con sed dai file .gradle.kts, dove era scritta a mano: quando è passata a una
# proprietà condivisa quell'estrazione ha smesso di trovarla, in silenzio, e gli artefatti sono
# usciti chiamati "Promemoria--x86_64.AppImage".
version_prodotto() {
    sed -nE 's/^promemoriaVersion=(.*)$/\1/p' gradle.properties | head -1
}

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
        ARTIFACT="androidApp/build/outputs/apk/debug/androidApp-debug.apk"
        ;;
    release)
        # Verifica che le credenziali di firma siano disponibili
        KEYSTORE_FILE="${KEYSTORE_FILE:-$HOME/.android/release-key.jks}"
        if [ ! -f "$KEYSTORE_FILE" ]; then
            echo "[ERRORE] Keystore non trovato: $KEYSTORE_FILE"
            echo "         Imposta KEYSTORE_FILE per un percorso diverso."
            exit 1
        fi
        if [ -z "${KEYSTORE_PASSWORD:-}" ]; then
            echo "[ERRORE] Per il build release servono le variabili d'ambiente:"
            echo "         export KEYSTORE_PASSWORD=<password>"
            echo "         export KEY_ALIAS=<alias>        (default: release)"
            echo "         export KEY_PASSWORD=<password>   (default: KEYSTORE_PASSWORD)"
            exit 1
        fi
        echo "[INFO] Avvio build release..."
        ./gradlew assembleRelease
        VERSION_NAME="$(version_prodotto)"
        ARTIFACT="androidApp/build/outputs/apk/release/reminder-${VERSION_NAME}.apk"
        ;;
    desktop)
        # AppImage: jpackage produce una directory autoconsistente, appimagetool la impacchetta
        APPIMAGETOOL="${APPIMAGETOOL:-$(command -v appimagetool || true)}"
        if [ -z "$APPIMAGETOOL" ]; then
            echo "[ERRORE] appimagetool non trovato."
            echo "         Scaricalo da https://github.com/AppImage/appimagetool/releases"
            echo "         e mettilo nel PATH, oppure indicalo con APPIMAGETOOL=/percorso/appimagetool"
            exit 1
        fi

        echo "[INFO] Avvio build desktop..."
        ./gradlew :desktopApp:createDistributable

        DIST="desktopApp/build/compose/binaries/main/app/Promemoria"
        APPDIR="desktopApp/build/appimage/Promemoria.AppDir"
        VERSION_NAME="$(version_prodotto)"
        ARTIFACT="desktopApp/build/appimage/Promemoria-${VERSION_NAME}-x86_64.AppImage"

        echo "[INFO] Preparazione AppDir..."
        rm -rf "$APPDIR" "$ARTIFACT"
        mkdir -p "$APPDIR/usr"
        cp -r "$DIST"/. "$APPDIR/usr/"
        cp desktopApp/src/main/resources/icon.png "$APPDIR/promemoria.png"
        cp desktopApp/src/main/resources/icon.png "$APPDIR/.DirIcon"

        cat > "$APPDIR/promemoria.desktop" <<'DESKTOP_ENTRY'
[Desktop Entry]
Type=Application
Name=Promemoria
Comment=Promemoria e scadenze
Exec=Promemoria
Icon=promemoria
Categories=Utility;
Terminal=false
DESKTOP_ENTRY

        cat > "$APPDIR/AppRun" <<'APPRUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/bin/Promemoria" "$@"
APPRUN
        chmod +x "$APPDIR/AppRun"

        echo "[INFO] Creazione AppImage..."
        APPIMAGE_EXTRACT_AND_RUN=1 "$APPIMAGETOOL" "$APPDIR" "$ARTIFACT" >/dev/null
        ;;
    clean)
        echo "[INFO] Pulizia progetto..."
        ./gradlew clean
        echo "[OK] Clean completato."
        exit 0
        ;;
    *)
        echo "[ERRORE] Build type non valido: '$BUILD_TYPE'"
        echo "         Uso: $0 [debug|release|desktop|clean]"
        exit 1
        ;;
esac

if [ -f "$ARTIFACT" ]; then
    echo ""
    echo "[OK] Build completata con successo!"
    echo "     Artefatto: $PROJECT_DIR/$ARTIFACT"
else
    echo "[ERRORE] Artefatto non trovato. Controlla i log sopra."
    exit 1
fi
