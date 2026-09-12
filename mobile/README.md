# mobile/ — RS3 Android client tooling

Capture + patch tooling for the **RuneScape 3 Android client** (`liblibs.hal.system.rs2client.so`,
AArch64 — the same NXT engine as desktop `rs2client`, recompiled). Points the mobile client at a local
Project X server, captures the protocol in-process via Frida, and bakes a self-contained "Project X Mobile" APK.

- **RE knowledge**: `re-resources/docs/mobile/` (browsable at `docs/mobile/`) — start at its `README.md`.
- **Agent**: `mobile-reverse-engineer` (`.claude/agents/`) owns the Ghidra DB + those docs.
- **Binaries + build metadata**: `data/client/android/` (gitignored) — APK, extracted `.so`/dex,
  `build-info.json`, generated `moduli.json`, the frida-gadget, built APKs, captures.

## Quickstart

```bash
./mobile/setup.sh                                   # venv + version-matched frida-gadget
source mobile/.venv/bin/activate
./mobile/import-apk.py --from-device                # pull the installed APK, extract, record build info
./mobile/build-projectx-mobile.sh --host 10.0.0.5:8829   # bake Project X Mobile APK (RSA keys swapped in)

adb uninstall com.jagex.runescape.android
adb install -r data/client/android/projectx-mobile.apk
adb shell am start -n com.jagex.runescape.android/com.jagex.android.MainActivity

./mobile/rs3-capture.py --gadget --host 10.0.0.5:8829 --isolate   # private, no phone-home
./mobile/rs3-capture.py --gadget --live                           # real game (comparison dumps)
```

## Files

| File | Purpose |
|---|---|
| `import-apk.py` | Acquire APK (adb pull / `--apk` / `--url`) → `data/client/android/` + build-info + manifest entry + `moduli.json` |
| `setup.sh` | Create venv, install frida-tools, fetch the matching `frida-gadget` |
| `build-projectx-mobile.sh` | Self-contained patched APK: RSA moduli baked in + gadget + `loadLibrary` inject |
| `repack-gadget.sh` | Gadget-only repack (RSA untouched) — for clean **live** login comparison dumps |
| `patch-so-rsa.py` | In-place `.so` RSA modulus swap (used by the build; idempotent) |
| `rs3-capture.py` / `rs3-capture.js` | Frida capture driver + agent — decoded packets, ISAAC keys, isolation kill-switch |
| `rva.json` | Hook/patch RVAs for the 949 build (regenerate per build via the RE agent) |
| `gadget/libgadget.config.so` | Frida-gadget config (JSON; listen `:27042`, `on_load=wait`) |

## Capture flags

`--host H:P` redirect config to Project X · `--live` no redirect (real game) · `--isolate` block every
outbound connection except the server + loopback (stops crash reports / analytics reaching Jagex &
third parties; each blocked attempt logs `[BLOCKED]`) · `--patch-rsa` runtime modulus swap (usually too
late for an embedded gadget — prefer the baked APK) · `--raw` dump raw socket bytes.

## RSA keys — bake, don't runtime-patch

The client parses its embedded RSA moduli once at `.so` static-init (at `dlopen`), before any Frida hook
can fire, so a runtime string patch is inert. `build-projectx-mobile.sh` swaps them in the `.so` at build
time (verified: static-init reads the patched `.rodata`). See `docs/mobile/binary/rsa-key-patching.md`.
