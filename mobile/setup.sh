#!/usr/bin/env bash
# One-time setup for the mobile capture/build tooling.
#   1. create a Python venv and install frida-tools (pinned in requirements.txt)
#   2. fetch the frida-gadget that matches the installed frida core version, into
#      data/client/android/gadget/libgadget.so
# The gadget version MUST equal `frida --version`, or the baked APK aborts on load — so we derive it.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
ANDROID="$ROOT/data/client/android"
VENV="$HERE/.venv"
GADGET_DIR="$ANDROID/gadget"

echo "== 1/2  python venv + frida-tools =="
[ -d "$VENV" ] || python3 -m venv "$VENV"
"$VENV/bin/pip" install --quiet --upgrade pip
"$VENV/bin/pip" install --quiet -r "$HERE/requirements.txt"
FRIDA_VER="$("$VENV/bin/frida" --version)"
echo "   frida core version: $FRIDA_VER"

echo "== 2/2  fetch matching frida-gadget (android-arm64) =="
mkdir -p "$GADGET_DIR"
GADGET="$GADGET_DIR/libgadget.so"
if [ -f "$GADGET" ] && "$VENV/bin/python" - "$GADGET" "$FRIDA_VER" <<'PY'
import sys, subprocess
# treat as up-to-date only if the existing gadget's build matches (best-effort: presence + version file)
sys.exit(0 if __import__("pathlib").Path(sys.argv[1] + ".version").read_text().strip() == sys.argv[2] else 1)
PY
then
  echo "   gadget already matches $FRIDA_VER"
else
  URL="https://github.com/frida/frida/releases/download/${FRIDA_VER}/frida-gadget-${FRIDA_VER}-android-arm64.so.xz"
  echo "   downloading $URL"
  curl -fsSL -o "$GADGET.xz" "$URL"
  "$VENV/bin/python" -c "import lzma,sys; open(sys.argv[1],'wb').write(lzma.open(sys.argv[1]+'.xz').read())" "$GADGET"
  rm -f "$GADGET.xz"
  echo "$FRIDA_VER" > "$GADGET.version"
  echo "   -> $GADGET"
fi

echo
echo "DONE. Next:"
echo "  source mobile/.venv/bin/activate"
echo "  ./mobile/import-apk.py --from-device        # pull APK + build info into data/client/android/"
echo "  ./mobile/build-projectx-mobile.sh --host <ip:port>   # bake the Project X Mobile APK"
