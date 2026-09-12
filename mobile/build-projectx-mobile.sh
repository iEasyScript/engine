#!/usr/bin/env bash
# Build a self-contained "Project X Mobile" APK for an UNROOTED device.
#
#   ./build-projectx-mobile.sh [BASE_APK] [--host HOST:PORT] [--release]
#
# Two build modes:
#   default (capture) : frida-gadget in LISTEN mode (:27042, on_load=wait). The app freezes at load
#                       until ./mobile/rs3-capture.py --gadget attaches from a PC. Used for
#                       debugging/packet capture. Launcher = MainActivity.
#   --release         : STANDALONE per-user login, NO PC. Adds a LoginActivity (username/password
#                       prompt) as the LAUNCHER; on submit it writes creds to an app-private file and
#                       starts MainActivity with the launchurl deeplink. The gadget runs in SCRIPT mode
#                       and auto-loads a bundled login script that reads those creds and calls the
#                       client's native BeginLobbyLogin at the login screen. Requires --host.
#
# Both modes bake in: RSA moduli swap, content-port patch, System.loadLibrary("gadget"), baked host.
#
# Capture-mode usage (default build):
#   private server : ./mobile/rs3-capture.py --gadget          live: --live      override: --host H:P
#   launch plain   : adb shell am start -n com.jagex.runescape.android/com.jagex.android.MainActivity
# Release-mode usage:
#   adb install -r; adb shell am start -n com.jagex.runescape.android/com.jagex.projectx.LoginActivity
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
ANDROID="$ROOT/data/client/android"
export PATH="$HOME/.local/bin:$PATH"

BASE_APK="$ANDROID/base.apk"
HOST=""
RELEASE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --host) HOST="$2"; shift 2;;
    --release) RELEASE=1; shift;;
    -*) echo "unknown flag: $1"; exit 2;;
    *) BASE_APK="$1"; shift;;
  esac
done

if [ "$RELEASE" -eq 1 ] && [ -z "$HOST" ]; then
  echo "--release requires --host HOST:PORT (baked into the login deeplink)"; exit 2
fi
LOGIN_SRC="$HERE/projectx-login"
# separate outputs so the standalone-login (release) build never clobbers the capture (listen) build
if [ "$RELEASE" -eq 1 ]; then OUT="$ANDROID/projectx-mobile-release.apk"; else OUT="$ANDROID/projectx-mobile.apk"; fi

GADGET_DIR="$ANDROID/gadget"          # libgadget.so (fetched by setup.sh)
GADGET_CFG="$HERE/gadget"             # libgadget.config.so (source, in mobile/)
WORK="$ANDROID/projectx-mobile-work"
KEYSTORE="$ANDROID/debug.keystore"
MODULI="$ANDROID/moduli.json"
ABI="lib/arm64-v8a"
SO="$ABI/liblibs.hal.system.rs2client.so"

for t in apktool apksigner zipalign; do command -v $t >/dev/null || { echo "missing $t"; exit 1; }; done
[ -f "$GADGET_DIR/libgadget.so" ] || { echo "missing gadget .so — run ./mobile/setup.sh"; exit 1; }
[ -f "$MODULI" ] || { echo "missing $MODULI — run ./mobile/import-apk.py first"; exit 1; }
[ -f "$BASE_APK" ] || { echo "base APK not found: $BASE_APK — run ./mobile/import-apk.py"; exit 1; }

ANDROID_JAR=""
if [ "$RELEASE" -eq 1 ]; then
  for t in javac d8 zip; do command -v $t >/dev/null || { echo "missing $t (needed for --release)"; exit 1; }; done
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "/opt/android-sdk"; do
    [ -n "$d" ] || continue
    j="$(ls "$d"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
    [ -n "$j" ] && ANDROID_JAR="$j" && break
  done
  [ -f "$ANDROID_JAR" ] || { echo "no android.jar found (set ANDROID_HOME to an SDK with a platform)"; exit 1; }
  echo "   android.jar: $ANDROID_JAR"
fi

echo "== 1/7  decode =="
rm -rf "$WORK"
# capture build keeps resources raw (-r, fast/safe); release build fully decodes so the manifest is
# text-editable (LoginActivity launcher wiring) — the resource round-trip is verified to rebuild cleanly.
if [ "$RELEASE" -eq 1 ]; then
  apktool d -f -o "$WORK" "$BASE_APK"
else
  apktool d -r -f -o "$WORK" "$BASE_APK"
fi

echo "== 2/7  patch RSA moduli in the .so =="
python3 "$HERE/patch-so-rsa.py" "$WORK/$SO" --moduli "$MODULI"

echo "== 2b/7  patch on-demand content HTTP port (80 -> config port) in the .so =="
# The client hardcodes port 80 for its /ms content fetch; port 80 isn't served, so rewrite it to the
# server's config port (from --host HOST:PORT, default 8829) where our /ms handler already lives.
CONTENT_PORT="${HOST##*:}"
[ "$CONTENT_PORT" = "$HOST" ] && CONTENT_PORT=""   # HOST had no :PORT
python3 "$HERE/patch-so-content-port.py" "$WORK/$SO" --port "${CONTENT_PORT:-8829}"

echo "== 3/7  inject System.loadLibrary(\"gadget\") =="
ACT="$WORK/smali/com/jagex/android/MainActivity.smali"
[ -f "$ACT" ] || ACT="$(grep -rl 'com/jagex/android/MainActivity' "$WORK"/smali*/ | head -1)"
if grep -q 'const-string v0, "gadget"' "$ACT"; then
  echo "   already injected"
else
  python3 - "$ACT" <<'PY'
import re, sys
p = sys.argv[1]; s = open(p).read()
inj = ('    const-string v0, "gadget"\n'
       '    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V\n')
def repl(m):
    head = m.group(0); loc = re.search(r'\.locals (\d+)', head)
    if loc and int(loc.group(1)) < 1: head = head.replace(loc.group(0), '.locals 1')
    return head + '\n' + inj
if re.search(r'\.method static constructor <clinit>\(\)V', s):
    s = re.sub(r'\.method static constructor <clinit>\(\)V\n(?:\s*\.locals \d+\n)?', repl, s, count=1)
else:
    clinit = ('\n.method static constructor <clinit>()V\n    .locals 1\n' + inj + '    return-void\n.end method\n')
    s = re.sub(r'(\.super [^\n]+\n)', r'\1' + clinit, s, count=1)
open(p,'w').write(s)
PY
  echo "   injected"
fi

if [ "$RELEASE" -eq 1 ]; then
  echo "== 3b/7  manifest: LoginActivity launcher (release) =="
  python3 - "$WORK/AndroidManifest.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
A = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", A)
p = sys.argv[1]; t = ET.parse(p); r = t.getroot()
app = r.find("application")
app.set(f"{{{A}}}extractNativeLibs", "true")
name = f"{{{A}}}name"
for act in app.findall("activity"):
    if act.get(name) == "com.jagex.android.MainActivity":
        for f in list(act.findall("intent-filter")):
            acts = {a.get(name) for a in f.findall("action")}
            cats = {c.get(name) for c in f.findall("category")}
            if "android.intent.action.MAIN" in acts and "android.intent.category.LAUNCHER" in cats:
                act.remove(f)
la = ET.SubElement(app, "activity")
la.set(name, "com.jagex.projectx.LoginActivity")
la.set(f"{{{A}}}exported", "true")
la.set(f"{{{A}}}theme", "@android:style/Theme.Material.NoActionBar")
la.set(f"{{{A}}}windowSoftInputMode", "stateVisible|adjustResize")
f = ET.SubElement(la, "intent-filter")
ET.SubElement(f, "action").set(name, "android.intent.action.MAIN")
ET.SubElement(f, "category").set(name, "android.intent.category.LAUNCHER")
t.write(p, xml_declaration=True, encoding="utf-8")
PY
  echo "   LoginActivity launcher wired; extractNativeLibs=true"

  echo "== 3c/7  compile LoginActivity (Java -> classes2.dex) =="
  JW="$WORK/loginjava"
  mkdir -p "$JW/src/com/jagex/android" "$JW/cls" "$JW/dex"
  sed "s|__PROJECTX_HOST__|$HOST|g" "$LOGIN_SRC/LoginActivity.java" > "$JW/src/LoginActivity.java"
  printf 'package com.jagex.android;\npublic class MainActivity extends android.app.NativeActivity {}\n' \
    > "$JW/src/com/jagex/android/MainActivity.java"   # compile-only stub; real class ships in classes.dex
  javac --release 17 -cp "$ANDROID_JAR" -d "$JW/cls" \
    "$JW/src/com/jagex/android/MainActivity.java" "$JW/src/LoginActivity.java"
  d8 --min-api 21 --lib "$ANDROID_JAR" --output "$JW/dex" "$JW/cls/com/jagex/projectx/"*.class
  mv "$JW/dex/classes.dex" "$JW/classes2.dex"   # kept out of $WORK root; injected explicitly after build
  echo "   LoginActivity -> classes2.dex"
fi

echo "== 4/7  drop gadget + config, bake host =="
mkdir -p "$WORK/$ABI"
cp "$GADGET_DIR/libgadget.so" "$WORK/$ABI/libgadget.so"
if [ "$RELEASE" -eq 1 ]; then
  # script-mode gadget with an ABSOLUTE path. frida-gadget resolves relative script paths against the
  # process CWD (/), not the lib dir, so the script is bundled as an asset and LoginActivity copies it to
  # getFilesDir()/loginscript.js before starting MainActivity (which is when the gadget loads it).
  mkdir -p "$WORK/assets"
  cp "$LOGIN_SRC/login-gadget.js" "$WORK/assets/loginscript.js"
  printf '{"interaction":{"type":"script","path":"/data/data/com.jagex.runescape.android/files/loginscript.js"}}' \
    > "$WORK/$ABI/libgadget.config.so"
  echo "   script-mode gadget (asset-copied absolute script)"
else
  cp "$GADGET_CFG/libgadget.config.so" "$WORK/$ABI/libgadget.config.so"
  echo "   listen-mode gadget (capture)"
fi
# record the build config (host, patched moduli) as an asset the tooling can read
mkdir -p "$WORK/assets"
python3 - "$WORK/assets/projectx-mobile.json" "$HOST" <<'PY'
import json, sys
json.dump({"host": sys.argv[2] or None, "rsa_patched": True, "gadget_port": 27042}, open(sys.argv[1], "w"))
PY
[ -n "$HOST" ] && echo "   baked default host: $HOST" || echo "   no default host baked (pass --host at capture time)"

echo "== 5/7  build =="
UNSIGNED="$ANDROID/projectx-mobile.unsigned.apk"
apktool b "$WORK" -o "$UNSIGNED"
if [ "$RELEASE" -eq 1 ]; then
  ( cd "$JW" && zip -q "$UNSIGNED" classes2.dex )   # add the LoginActivity dex (multidex; ART loads it)
  echo "   injected classes2.dex"
fi

echo "== 6/7  zipalign =="
ALIGNED="$ANDROID/projectx-mobile.aligned.apk"
zipalign -f -p 4 "$UNSIGNED" "$ALIGNED"

echo "== 7/7  sign =="
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias rs3 -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname "CN=rs3-re, OU=re, O=re, L=x, S=x, C=US" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android --out "$OUT" "$ALIGNED"
apksigner verify "$OUT" >/dev/null 2>&1 && echo "   signature OK"
rm -f "$UNSIGNED" "$ALIGNED"
rm -rf "$WORK"

echo
echo "DONE -> $OUT"
echo "Install: adb uninstall com.jagex.runescape.android; adb install -r \"$OUT\""
if [ "$RELEASE" -eq 1 ]; then
  echo "Mode: RELEASE (standalone login). Launch: adb shell am start -n com.jagex.runescape.android/com.jagex.projectx.LoginActivity"
  echo "      Type creds -> logs into $HOST. Logcat: adb logcat | grep -i projectx-login"
else
  echo "Mode: CAPTURE (listen gadget). Attach: ./mobile/rs3-capture.py --gadget"
fi
