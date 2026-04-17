#!/bin/bash
# Installa l'APK debug su tutti i dispositivi connessi
# Uso: ./install-all.sh [--build]
#   --build  compila prima di installare

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"

# Build opzionale
if [ "$1" = "--build" ]; then
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
echo "=== Done ==="
