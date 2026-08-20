#!/bin/bash
# Installa l'APK debug su tutti i dispositivi connessi
# Uso: ./install-all.sh [--build] [--run] [--info]
#   --build  compila prima di installare
#   --run    avvia l'app dopo l'installazione
#   --info   mostra autore, versione e data di build dell'APK ed esce

AUTHOR="Alberto Goldoni"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK="$PROJECT_DIR/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
PACKAGE="it.agoldoni.reminder.debug"
GRADLE_FILE="$PROJECT_DIR/app/build.gradle.kts"

DO_BUILD=false
DO_RUN=false
DO_INFO=false
for ARG in "$@"; do
    case "$ARG" in
        --build) DO_BUILD=true ;;
        --run)   DO_RUN=true ;;
        --info)  DO_INFO=true ;;
        *) echo "Argomento sconosciuto: $ARG"; exit 1 ;;
    esac
done

if [ "$DO_INFO" = true ]; then
    VERSION_NAME=$(grep -E '^\s*versionName\s*=' "$GRADLE_FILE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
    VERSION_CODE=$(grep -E '^\s*versionCode\s*=' "$GRADLE_FILE" | head -1 | sed -E 's/.*=\s*([0-9]+).*/\1/')
    SUFFIX=$(grep -E 'versionNameSuffix\s*=' "$GRADLE_FILE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
    if [ -f "$APK" ]; then
        BUILD_DATE=$(date -r "$APK" "+%Y-%m-%d %H:%M:%S")
    else
        BUILD_DATE="(APK non ancora compilato)"
    fi
    echo "Autore:    $AUTHOR"
    echo "Versione:  ${VERSION_NAME}${SUFFIX} (code $VERSION_CODE)"
    echo "Build:     $BUILD_DATE"
    exit 0
fi

if [ "$DO_BUILD" = true ]; then
    echo "=== Build debug ==="
    cd "$PROJECT_DIR" && ./gradlew assembleDebug
    if [ $? -ne 0 ]; then
        echo "Build fallita!"
        exit 1
    fi
fi

if [ ! -f "$APK" ]; then
    echo "APK non trovato. Esegui prima: ./gradlew assembleDebug"
    exit 1
fi

# Trova tutti i dispositivi connessi
DEVICES=$(adb devices 2>/dev/null | grep -E "device$" | awk '{print $1}')

if [ -z "$DEVICES" ]; then
    echo "Nessun dispositivo connesso."
    exit 1
fi

# Installa su ciascun dispositivo in parallelo
for DEVICE in $DEVICES; do
    echo "=== Installazione su $DEVICE ==="
    adb -s "$DEVICE" install -r "$APK" &
done

wait

if [ "$DO_RUN" = true ]; then
    for DEVICE in $DEVICES; do
        echo "=== Avvio app su $DEVICE ==="
        adb -s "$DEVICE" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null
    done
fi

echo "=== Done ==="
