#!/usr/bin/env bash
# Repack the RS3 APK with frida-gadget so it runs on an UNROOTED device.
#
# Injects System.loadLibrary("gadget") into MainActivity's static initializer and ships
# libgadget.so + its config in lib/arm64-v8a/. The gadget listens on 127.0.0.1:27042 and
# (on_load=wait) holds the app at startup until rs3-capture.py --gadget attaches — so hooks
# are installed before any network traffic, including the login handshake.
#
# Requires: apktool, apksigner, zipalign, keytool (all present on this box).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
ANDROID="$ROOT/data/client/android"
export PATH="$HOME/.local/bin:$PATH"

APK="${1:-$ANDROID/base.apk}"
GADGET_DIR="$ANDROID/gadget"          # libgadget.so (fetched by setup.sh)
GADGET_CFG="$HERE/gadget"             # libgadget.config.so (source, in mobile/)
WORK="$ANDROID/repack-work"
OUT="$ANDROID/rs3-gadget.apk"
KEYSTORE="$ANDROID/debug.keystore"
ABI="lib/arm64-v8a"

command -v apktool  >/dev/null || { echo "missing apktool";  exit 1; }
command -v apksigner>/dev/null || { echo "missing apksigner";exit 1; }
command -v zipalign >/dev/null || { echo "missing zipalign"; exit 1; }
[ -f "$GADGET_DIR/libgadget.so" ]  || { echo "missing gadget .so — run ./mobile/setup.sh"; exit 1; }
[ -f "$GADGET_CFG/libgadget.config.so" ] || { echo "missing gadget config"; exit 1; }
[ -f "$APK" ] || { echo "APK not found: $APK — run ./mobile/import-apk.py"; exit 1; }

echo "== 1/6  decode (resources kept raw: -r) =="
rm -rf "$WORK"
apktool d -r -f -o "$WORK" "$APK"

ACT="$WORK/smali/com/jagex/android/MainActivity.smali"
[ -f "$ACT" ] || ACT="$(grep -rl 'com/jagex/android/MainActivity' "$WORK"/smali*/ | head -1)"
[ -f "$ACT" ] || { echo "MainActivity.smali not found"; exit 1; }
echo "   activity: ${ACT#$WORK/}"

echo "== 2/6  inject System.loadLibrary(\"gadget\") into static init =="
if grep -q 'const-string v0, "gadget"' "$ACT"; then
  echo "   already injected"
elif grep -q '\.method static constructor <clinit>()V' "$ACT"; then
  # prepend load into the existing static initializer, right after .locals
  python3 - "$ACT" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p).read()
inject = ('    const-string v0, "gadget"\n'
          '    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V\n')
def repl(m):
    head = m.group(0)
    loc = re.search(r'\.locals (\d+)', head)
    if loc and int(loc.group(1)) < 1:
        head = head.replace(loc.group(0), '.locals 1')
    return head + '\n' + inject
s = re.sub(r'\.method static constructor <clinit>\(\)V\n(?:\s*\.locals \d+\n)?', repl, s, count=1)
open(p,'w').write(s)
print("   patched existing <clinit>")
PY
else
  # no static initializer — add one after the .super line
  python3 - "$ACT" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p).read()
clinit = ('\n.method static constructor <clinit>()V\n'
          '    .locals 1\n'
          '    const-string v0, "gadget"\n'
          '    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V\n'
          '    return-void\n'
          '.end method\n')
s = re.sub(r'(\.super [^\n]+\n)', r'\1' + clinit, s, count=1)
open(p,'w').write(s)
print("   added new <clinit>")
PY
fi
grep -n 'const-string v0, "gadget"' "$ACT" | head -1

echo "== 3/6  drop gadget + config into $ABI =="
mkdir -p "$WORK/$ABI"
cp "$GADGET_DIR/libgadget.so"        "$WORK/$ABI/libgadget.so"
cp "$GADGET_CFG/libgadget.config.so" "$WORK/$ABI/libgadget.config.so"

echo "== 4/6  build =="
UNSIGNED="$HERE/rs3-gadget.unsigned.apk"
apktool b "$WORK" -o "$UNSIGNED"

echo "== 5/6  zipalign =="
ALIGNED="$HERE/rs3-gadget.aligned.apk"
zipalign -f -p 4 "$UNSIGNED" "$ALIGNED"

echo "== 6/6  sign =="
if [ ! -f "$KEYSTORE" ]; then
  echo "   generating debug keystore"
  keytool -genkeypair -keystore "$KEYSTORE" -alias rs3 -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname "CN=rs3-re, OU=re, O=re, L=x, S=x, C=US" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT" "$ALIGNED"
apksigner verify --print-certs "$OUT" >/dev/null && echo "   signature OK"

rm -f "$UNSIGNED" "$ALIGNED"
echo
echo "DONE -> $OUT"
echo "Install: adb install -r \"$OUT\""
echo "Note: uninstall the Play Store build first (different signer): adb uninstall com.jagex.runescape.android"
