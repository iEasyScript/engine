#!/usr/bin/env bash
#
# project-x — ALL-IN-ONE LAUNCHER
# ========================================
# One entry point that drives BOTH mechanisms against the same rs2client process:
#   1. Project X patch + launch  — LD_PRELOAD libprojectx_patcher.so, point the client at the
#                                 local Project X lobby/world (RSA + server-URL patch).
#   2. Project X engine inject   — GDB-dlopen libprojectxbootstrap.so once the client is up
#                                 (funchook hooks, ImGui overlay, in-process MCP :7882, and the
#                                 TcpIn network sniffer — which REPLACES the deprecated proxy).
#
# Usage:
#   launch/run-projectx.sh                 # patch + launch + inject  (default)
#   launch/run-projectx.sh --no-engine     # patch + launch only (server testing, no injection)
#   launch/run-projectx.sh --no-patch      # inject into an already-running rs2client only
#
# Env knobs:
#   INJECT_DELAY=<sec>   seconds to wait after the client process appears before injecting (default 8)
#   CONFIG_URI=<url>     jav_config URL handed to the client
#                        (default http://localhost:${PROJECTX_HTTP_PORT:-8829}/jav_config.ws)
#
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE_DIR="$PROJECT_DIR/client-plugin-engine"
ENGINE_SO="$ENGINE_DIR/build/libs/libprojectxbootstrap.so"
INJECT_DELAY="${INJECT_DELAY:-8}"

CLIENT_DIR="$PROJECT_DIR/data/client/linux"
LAUNCHER_BIN="$CLIENT_DIR/rs3linux"
CLIENT_BIN="$CLIENT_DIR/rs2client"
PATCHER_SO="$CLIENT_DIR/libprojectx_patcher.so"
PROJECTX_DIR="$HOME/.projectx"
# Snapshotted before .env is loaded so an explicit shell value always outranks the file.
CONFIG_URI_OVERRIDE="${CONFIG_URI-}"

DO_PATCH=1
DO_ENGINE=1
for arg in "$@"; do
    case "$arg" in
        --no-patch)  DO_PATCH=0 ;;
        --no-engine) DO_ENGINE=0 ;;
        -h|--help)   sed -n '2,21p' "$0"; exit 0 ;;
        *) echo "Unknown option: $arg" >&2; exit 2 ;;
    esac
done

log() { echo -e "\033[1;36m[projectx-launch]\033[0m $*"; }
err() { echo -e "\033[1;31m[projectx-launch] ERROR:\033[0m $*" >&2; }

# --- locate a live rs2client pid that is NOT already injected -----------------------------------
find_client_pid() {
    local newest_pid="" newest_start=0
    for pid_dir in /proc/[0-9]*; do
        local pid; pid=$(basename "$pid_dir")
        grep -qa 'rs2client' "$pid_dir/cmdline" 2>/dev/null || continue
        [[ -r "/proc/$pid/maps" ]] || continue
        # skip if our bootstrap is already mapped in
        grep -qa 'libprojectxbootstrap.so' "/proc/$pid/maps" 2>/dev/null && continue
        local start; start=$(stat -c %Y "/proc/$pid" 2>/dev/null || echo 0)
        if (( start >= newest_start )); then newest_start=$start; newest_pid=$pid; fi
    done
    [[ -n "$newest_pid" ]] && echo "$newest_pid"
}

inject_engine() {
    local pid="$1"
    if [[ ! -f "$ENGINE_SO" ]]; then
        err "engine bootstrap not built: $ENGINE_SO"
        err "build it first:  ./gradlew :client-plugin-engine:buildNativeBootstrap   (and ./gradlew :client-plugin-engine:build for the jar)"
        return 1
    fi
    [[ -d "/proc/$pid" ]] || { err "process $pid is gone"; return 1; }

    : "${JAVA_HOME:?JAVA_HOME must be set to a JDK 25 home for engine injection}"
    export PROJECTX_HOME_DIR="${PROJECTX_HOME_DIR:-$(realpath "$ENGINE_DIR/build/libs")}"

    log "injecting $ENGINE_SO into rs2client pid $pid (PROJECTX_HOME_DIR=$PROJECTX_HOME_DIR)"
    # Lift RLIMIT_CORE so a JVM crash actually dumps a core (best effort).
    sudo prlimit --pid "$pid" --core=unlimited:unlimited 2>/dev/null \
        || log "warn: prlimit failed; no core dump on crash"
    sudo gdb -p "$pid" -batch \
        -ex "call (int) setenv(\"JAVA_HOME\", \"$JAVA_HOME\", 1)" \
        -ex "call (int) setenv(\"PROJECTX_HOME_DIR\", \"$PROJECTX_HOME_DIR\", 1)" \
        -ex "call (void*) dlopen(\"$ENGINE_SO\", 4362)" \
        -ex "call (char*) dlerror()" \
        -ex detach -ex quit
}

# --- patch + launch the client --------------------------------------------------------------------
load_env() {
    [[ -f "$PROJECT_DIR/.env" ]] || return 0
    while IFS= read -r line; do
        line="${line%$'\r'}"
        [[ "$line" =~ ^[[:space:]]*# ]] && continue
        [[ -z "${line// }" ]] && continue
        export "$line"
    done < "$PROJECT_DIR/.env"
}

patch_and_launch() {
    load_env
    local config_uri="${CONFIG_URI_OVERRIDE:-http://localhost:${PROJECTX_HTTP_PORT:-8829}/jav_config.ws}"

    mkdir -p "$PROJECTX_DIR"
    # HOME is redirected into ~/.projectx so a custom-server run never shares cache or user data with
    # a live install; preferences.cfg is what points the client's own folders at it. Seeded only when
    # absent, matching the launcher, so local edits survive.
    [[ -f "$PROJECTX_DIR/preferences.cfg" ]] || printf 'cache_folder=%s\nLanguage=0\nuser_folder=%s\n' \
        "$PROJECTX_DIR" "$PROJECTX_DIR" > "$PROJECTX_DIR/preferences.cfg"

    # rs3linux's own download+save path fails on this setup: it builds a user-namespace/pivot_root
    # sandbox and exits 13 ("Error saving file") before writing the binary. Pre-seeding the exact
    # binary the ConfigServer advertises a CRC for makes it accept the cache and skip the download.
    if [[ -f "$CLIENT_BIN" ]]; then
        local launcher_cache="$PROJECTX_DIR/Jagex/launcher"
        mkdir -p "$launcher_cache"
        cp -f "$CLIENT_BIN" "$launcher_cache/rs2client"
        chmod 700 "$launcher_cache/rs2client"
        rm -f "$launcher_cache/instance.lock"
    fi

    log "launching $LAUNCHER_BIN --configURI $config_uri (HOME=$PROJECTX_DIR)"
    cd "$PROJECTX_DIR"
    local ec=0
    env HOME="$PROJECTX_DIR" \
        LD_PRELOAD="$PATCHER_SO" \
        SDL_VIDEODRIVER=x11 \
        SDL_VIDEO_X11_WMCLASS=RuneScape \
        "$LAUNCHER_BIN" --configURI "$config_uri" || ec=$?
    if (( ec > 128 )); then
        log "client killed by signal $(( ec - 128 ))"
    elif (( ec != 0 )); then
        log "client exited with code $ec"
    fi
}

# --- 1. patch + launch the client (background) --------------------------------------------------
CLIENT_BG_PID=""
if (( DO_PATCH )); then
    [[ -f "$LAUNCHER_BIN" ]] || { err "client bootstrapper not found at $LAUNCHER_BIN"; exit 1; }
    [[ -f "$PATCHER_SO" ]] || {
        err "patcher not found at $PATCHER_SO"
        err "build + deploy it first:  client/launcher/patcher/build.sh"
        exit 1
    }
    log "patch + launch (LD_PRELOAD libprojectx_patcher.so)"
    patch_and_launch &
    CLIENT_BG_PID=$!
else
    log "--no-patch: skipping launch; will inject into an already-running rs2client"
fi

# --- 2. wait for the client, then inject the engine ---------------------------------------------
if (( DO_ENGINE )); then
    log "waiting for rs2client process..."
    pid=""
    for _ in $(seq 1 120); do
        pid="$(find_client_pid || true)"
        [[ -n "$pid" ]] && break
        # if we launched the client and it already died, bail
        if (( DO_PATCH )) && ! kill -0 "$CLIENT_BG_PID" 2>/dev/null && [[ -z "$pid" ]]; then
            err "client process exited before rs2client appeared"; exit 1
        fi
        sleep 1
    done
    [[ -z "$pid" ]] && { err "timed out waiting for rs2client"; exit 1; }

    log "rs2client up (pid $pid); waiting ${INJECT_DELAY}s for it to initialize before injecting"
    sleep "$INJECT_DELAY"
    inject_engine "$pid" && log "engine injected — overlay + MCP (:7882) + TcpIn sniffer active" \
        || { err "engine injection failed"; }
else
    log "--no-engine: client launched without engine injection"
fi

# Keep the foreground tied to the client when we launched it.
if [[ -n "$CLIENT_BG_PID" ]]; then
    wait "$CLIENT_BG_PID" 2>/dev/null || true
fi
