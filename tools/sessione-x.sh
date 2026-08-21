#!/usr/bin/env bash
#
# Esegue Promemoria desktop in un server X separato (Xephyr) e permette di pilotarne la UI
# senza toccare la sessione grafica dell'utente.
#
#   tools/sessione-x.sh avvia [--dati-reali]   apre server X, window manager e app
#   tools/sessione-x.sh browser URL            apre un browser sull'URL nel display separato
#   tools/sessione-x.sh pilota                 esegue da stdin i comandi di Pilota.java
#   tools/sessione-x.sh scatto FILE            screenshot del display separato
#   tools/sessione-x.sh chiudi                 spegne tutto
#
# Variabili: DISP (:2), GEOM (1100x760).
#
# Perché Xephyr e non Xvfb o un VNC: è un server X vero, con il suo display e il suo albero di
# finestre, ma è annidato in una finestra della sessione reale, quindi la prova si guarda mentre
# succede. Per l'accesso remoto basta aggiungerci sopra `x11vnc -display "$DISP"`.
set -uo pipefail

DISP=${DISP:-:2}
GEOM=${GEOM:-1100x760}
RADICE=$(cd "$(dirname "$0")/.." && pwd)
LAVORO="$RADICE/desktopApp/build/sessione-x"
SANDBOX="$LAVORO/sandbox"

trova_appimage() {
    ls -t "$RADICE"/desktopApp/build/appimage/*.AppImage 2>/dev/null | head -1
}

attesa_socket() {
    for _ in $(seq 1 50); do
        [ -e "/tmp/.X11-unix/X${DISP#:}" ] && return 0
        sleep 0.2
    done
    return 1
}

# La finestra di Xephyr va sul desktop virtuale di VS Code, non dove la mette il window manager.
sposta_su_workspace_vscode() {
    command -v wmctrl >/dev/null || return 0
    local desk
    desk=$(wmctrl -l | grep "Visual Studio Code" | head -1 | awk '{print $2}')
    [ -z "$desk" ] && return 0
    # La finestra a volte ignora il primo -t: si ripete.
    for _ in 1 2; do
        for w in $(wmctrl -l | grep -i "Xephyr" | awk '{print $1}'); do
            wmctrl -i -r "$w" -t "$desk"
        done
        sleep 1
    done
}

# Server X e window manager, senza nulla dentro: li condividono `avvia` e `browser`.
avvia_server_x() {
    command -v Xephyr >/dev/null || { echo "Serve Xephyr (pacchetto xserver-xephyr)."; return 1; }
    if [ -e "/tmp/.X11-unix/X${DISP#:}" ]; then
        echo "Display $DISP già attivo: ci si aggiunge dentro."
        return 0
    fi
    mkdir -p "$LAVORO"
    Xephyr "$DISP" -screen "$GEOM" -resizeable -ac -br \
        -title "Xephyr $DISP — sessione separata Promemoria" \
        >"$LAVORO/xephyr.log" 2>&1 &
    echo $! > "$LAVORO/xephyr.pid"
    attesa_socket || { echo "Xephyr non è partito; vedi $LAVORO/xephyr.log"; return 1; }
    echo "Server X separato su $DISP"

    # Senza window manager la finestra resta senza decorazioni e non si può spostare.
    if command -v metacity >/dev/null; then
        DISPLAY="$DISP" metacity >"$LAVORO/wm.log" 2>&1 &
        echo $! > "$LAVORO/wm.pid"
        sleep 1
    fi
    sposta_su_workspace_vscode
}

case "${1:-avvia}" in
avvia)
    command -v Xephyr >/dev/null || { echo "Serve Xephyr (pacchetto xserver-xephyr)."; exit 1; }
    appimage=$(trova_appimage)
    [ -z "$appimage" ] && { echo "Nessun AppImage in desktopApp/build/appimage: lancia ./build.sh desktop"; exit 1; }

    # Il lock di istanza singola è una porta di loopback (SingleInstance.DEFAULT_PORT), non è
    # legata al display: con un'altra copia viva questa si chiuderebbe subito.
    if ss -ltn 2>/dev/null | grep -q ':47653 '; then
        echo "C'è già un'istanza di Promemoria in esecuzione: chiudila, tiene la porta 47653."
        exit 1
    fi
    if [ -e "/tmp/.X11-unix/X${DISP#:}" ]; then
        echo "Display $DISP occupato: usa 'chiudi' oppure cambia DISP."
        exit 1
    fi

    mkdir -p "$LAVORO"
    Xephyr "$DISP" -screen "$GEOM" -resizeable -ac -br \
        -title "Xephyr $DISP — sessione separata Promemoria" \
        >"$LAVORO/xephyr.log" 2>&1 &
    echo $! > "$LAVORO/xephyr.pid"
    attesa_socket || { echo "Xephyr non è partito; vedi $LAVORO/xephyr.log"; exit 1; }
    echo "Server X separato su $DISP"

    # Senza window manager la finestra dell'app resta senza decorazioni e non si può spostare.
    if command -v metacity >/dev/null; then
        DISPLAY="$DISP" metacity >"$LAVORO/wm.log" 2>&1 &
        echo $! > "$LAVORO/wm.pid"
        sleep 1
    fi
    sposta_su_workspace_vscode

    if [ "${2:-}" = "--dati-reali" ]; then
        echo "ATTENZIONE: la prova userà il database reale (~/.local/share/promemoria)."
    else
        mkdir -p "$SANDBOX/data" "$SANDBOX/config"
        export XDG_DATA_HOME="$SANDBOX/data" XDG_CONFIG_HOME="$SANDBOX/config"
        echo "Dati in sandbox: $SANDBOX"
        echo "Nota: la sandbox isola i FILE, non la rete. Se associ questa istanza a un altro"
        echo "      dispositivo, la sincronizzazione ci porta dentro i promemoria veri."
    fi

    DISPLAY="$DISP" "$appimage" >"$LAVORO/app.log" 2>&1 &
    echo $! > "$LAVORO/app.pid"
    echo "Promemoria avviato su $DISP ($(basename "$appimage"))"
    echo "Il messaggio «Cannot create Linux GL context» nel log è atteso: in Xephyr Skiko"
    echo "ripiega sul rendering software."
    ;;
browser)
    # Serve a guardare la web app locale servita dal telefono senza rubare finestra, fuoco e
    # appunti alla sessione dell'utente. Il profilo è nuovo e sta sotto build/: quello reale non
    # va toccato, altrimenti il browser dell'utente si troverebbe una sessione in più aperta.
    url=${2:-}
    [ -z "$url" ] && { echo "uso: $0 browser URL"; exit 1; }
    browser=$(command -v chromium || command -v chromium-browser || command -v google-chrome \
        || command -v firefox) \
        || { echo "Nessun browser trovato (chromium, google-chrome, firefox)."; exit 1; }
    avvia_server_x || exit 1

    profilo="$LAVORO/profilo-browser"
    mkdir -p "$profilo"
    case "$(basename "$browser")" in
    firefox)
        DISPLAY="$DISP" "$browser" --profile "$profilo" --no-remote --new-window "$url" \
            >"$LAVORO/browser.log" 2>&1 &
        ;;
    *)
        DISPLAY="$DISP" "$browser" --user-data-dir="$profilo" \
            --no-first-run --no-default-browser-check --disable-sync \
            --window-size="${GEOM%x*},${GEOM#*x}" --window-position=0,0 \
            "$url" >"$LAVORO/browser.log" 2>&1 &
        ;;
    esac
    echo $! > "$LAVORO/browser.pid"
    echo "$(basename "$browser") avviato su $DISP → $url"
    ;;
pilota)
    DISPLAY="$DISP" java "$RADICE/tools/Pilota.java"
    ;;
scatto)
    file=${2:-$LAVORO/schermata.png}
    printf 'shot %s\n' "$file" | DISPLAY="$DISP" java "$RADICE/tools/Pilota.java" >/dev/null
    echo "$file"
    ;;
chiudi)
    for f in app browser wm xephyr; do
        [ -f "$LAVORO/$f.pid" ] && kill "$(cat "$LAVORO/$f.pid")" 2>/dev/null
        rm -f "$LAVORO/$f.pid"
    done
    echo "Sessione $DISP chiusa."
    ;;
*)
    echo "uso: $0 {avvia [--dati-reali]|browser URL|pilota|scatto [file]|chiudi}"; exit 1 ;;
esac
