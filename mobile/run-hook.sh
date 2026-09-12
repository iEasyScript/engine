#!/usr/bin/env bash
# Install (if needed), launch, and Frida-hook the Project X Mobile client, pointed at the local
# Project X server. One-shot wrapper over adb + rs3-capture.py for the unrooted frida-gadget APK.
#
#   ./mobile/run-hook.sh                  # redirect host from .env (LOBBY_HOST:PROJECTX_HTTP_PORT), isolated
#   ./mobile/run-hook.sh 10.69.69.50:8829 # explicit redirect host:port
#   ./mobile/run-hook.sh --live           # no redirect — reach the real game (comparison dumps)
#   ./mobile/run-hook.sh --no-isolate     # allow outbound analytics/crash reports through
#   ./mobile/run-hook.sh --device SERIAL  # target a specific adb device
# Extra args after the known flags pass straight to rs3-capture.py (e.g. --raw, --quiet).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID="$ROOT/data/client/android"
APK="${APK:-$ANDROID/projectx-mobile.apk}"
PKG="com.jagex.runescape.android"
ACTIVITY="com.jagex.android.MainActivity"
CAPTURE="$ROOT/mobile/rs3-capture.py"

HOST=""
LIVE=0
ISOLATE=1
DEVICE=""
REINSTALL=0
EXTRA=()

while [ $# -gt 0 ]; do
  case "$1" in
    --live) LIVE=1; shift;;
    --no-isolate) ISOLATE=0; shift;;
    --reinstall) REINSTALL=1; shift;;
    --device) DEVICE="${2:?}"; shift 2;;
    --apk) APK="${2:?}"; shift 2;;
    --host) HOST="${2:?}"; shift 2;;
    -h|--help) sed -n '2,11p' "$0"; exit 0;;
    *:*) HOST="$1"; shift;;              # bare HOST:PORT positional
    *) EXTRA+=("$1"); shift;;            # forwarded to rs3-capture.py
  esac
done

adb() { command adb ${DEVICE:+-s "$DEVICE"} "$@"; }

# --- resolve the redirect host from .env when not given (config server = lobby host : http port) ---
env_get() {
  grep -E "^$1=" "$ROOT/.env" 2>/dev/null | tail -1 | cut -d= -f2- | sed 's/[[:space:]]*#.*//' | tr -d '[:space:]\r'
}
HTTP_PORT="$(env_get PROJECTX_HTTP_PORT)"; HTTP_PORT="${HTTP_PORT:-8829}"
if [ "$LIVE" -eq 0 ] && [ -z "$HOST" ]; then
  h="$(env_get LOBBY_HOST)"; [ -z "$h" ] && h="$(env_get PUBLIC_HOST)"
  [ -n "$h" ] && HOST="$h:$HTTP_PORT"
fi

# Under --isolate, allow every Project X host through (lobby AND world may be on different IPs).
ALLOW_HOSTS=()
for key in LOBBY_HOST WORLD_HOST WORLD_PUBLIC_HOST PUBLIC_HOST; do
  v="$(env_get "$key")"
  [ -n "$v" ] && [[ " ${ALLOW_HOSTS[*]-} " != *" $v "* ]] && ALLOW_HOSTS+=("$v")
done

# --- preflight ---
command -v adb >/dev/null || { echo "[!] adb not on PATH"; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "[!] no adb device — check 'adb devices'"; exit 1; }
[ -f "$CAPTURE" ] || { echo "[!] missing $CAPTURE"; exit 1; }

# --- ensure the frida venv exists; first run bootstraps it via setup.sh ---
VENV="$ROOT/mobile/.venv"
PY="$VENV/bin/python3"
if [ ! -x "$PY" ] || ! "$PY" -c 'import frida' >/dev/null 2>&1; then
  echo "[+] frida venv missing — bootstrapping via mobile/setup.sh (first run)"
  "$ROOT/mobile/setup.sh"
  PY="$VENV/bin/python3"
fi
"$PY" -c 'import frida' >/dev/null 2>&1 || { echo "[!] frida still unavailable in $VENV — run ./mobile/setup.sh manually"; exit 1; }

# --- ensure the RSA-PATCHED Project X gadget APK is installed. A pristine/gadget-only repack keeps
#     Jagex's RSA moduli and crashes at the JS5 master-index signature verify, so the login APK MUST be
#     the build-projectx-mobile output (RSA swapped to Project X's keys). Build it if missing; (re)install
#     when absent or when --reinstall is passed (needed to swap out a stale/unpatched build). ---
ensure_apk() {
  [ -f "$APK" ] && return 0
  echo "[+] $APK missing — building it via build-projectx-mobile.sh --host ${HOST:-<HOST:PORT>}"
  "$ROOT/mobile/build-projectx-mobile.sh" ${HOST:+--host "$HOST"}
  [ -f "$APK" ] || { echo "[!] build did not produce $APK"; exit 1; }
}
installed=0
adb shell pm list packages 2>/dev/null | tr -d '\r' | grep -qx "package:$PKG" && installed=1
if [ "$REINSTALL" -eq 1 ] || [ "$installed" -eq 0 ]; then
  [ "$REINSTALL" -eq 1 ] && rm -f "$APK"   # force a fresh build so the .so patches (RSA + content port) reapply
  ensure_apk
  if [ "$installed" -eq 1 ]; then
    echo "[+] uninstalling existing $PKG (signature/RSA-patch swap)"
    adb uninstall "$PKG" >/dev/null 2>&1 || true
  fi
  echo "[+] installing $APK"
  adb install -r "$APK"
else
  echo "[=] $PKG already installed — pass --reinstall if it isn't the RSA-patched build (JS5 crash)"
fi

# --- launch cleanly; the embedded gadget holds the app at .so load until the hook attaches.
#     The config redirect goes through the launchurl deeplink (the in-process Java-bridge redirect is
#     unavailable under the gadget), so point the client at our server via a VIEW intent whose URI
#     carries ?launchurl=HOST — the client fetches http://HOST/jav_config.ws?binaryType=7 from it. ---
echo "[+] launching $PKG/$ACTIVITY"
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
if [ "$LIVE" -eq 0 ] && [ -n "$HOST" ]; then
  DEEPLINK="https://secure.runescape.com/playnow/rs?launchurl=$HOST"
  echo "    deeplink: launchurl=$HOST"
  adb shell am start -a android.intent.action.VIEW -d "'$DEEPLINK'" -n "$PKG/$ACTIVITY" >/dev/null
else
  adb shell am start -n "$PKG/$ACTIVITY" >/dev/null
fi

# --- assemble the capture/hook command ---
CAP_ARGS=(--gadget)
[ -n "$DEVICE" ] && CAP_ARGS+=(--device "$DEVICE")
if [ "$LIVE" -eq 1 ]; then
  CAP_ARGS+=(--live)
  echo "[+] LIVE mode — no redirect"
else
  [ -n "$HOST" ] && CAP_ARGS+=(--host "$HOST") && echo "[+] redirecting config to $HOST"
  if [ "$ISOLATE" -eq 1 ] && [ -n "$HOST" ]; then
    CAP_ARGS+=(--isolate)
    if [ "${#ALLOW_HOSTS[@]}" -gt 0 ]; then
      for a in "${ALLOW_HOSTS[@]}"; do CAP_ARGS+=(--allow "$a"); done
      echo "[+] isolation allow-list: ${ALLOW_HOSTS[*]}"
    fi
  fi
fi
[ "${#EXTRA[@]}" -gt 0 ] && CAP_ARGS+=("${EXTRA[@]}")

echo "[+] hooking: $CAPTURE ${CAP_ARGS[*]}"
exec "$PY" "$CAPTURE" "${CAP_ARGS[@]}"
