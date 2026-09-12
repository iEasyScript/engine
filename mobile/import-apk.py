#!/usr/bin/env python3
"""Acquire the RS3 Android APK and populate data/client/android/ with build metadata.

The android client is Play-Store-only (Jagex hosts no APK: content.runescape.com/downloads/android/
404s). So this is the android analogue of the desktop clientupdater: it gets the APK, extracts the
native lib + dex + manifest, records build info into the shared data/client/clients.manifest.json
trail, and generates data/client/android/moduli.json (client embedded RSA moduli -> Project X keys) for
the patch/build tooling.

Acquisition (in order of preference):
  --from-device   adb pull the installed APK (auth-free, exactly the build the user runs) [default]
  --apk PATH      import a local APK
  --url URL       download from a mirror
"""

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile
from pathlib import Path

PACKAGE = "com.jagex.runescape.android"
SO_NAME = "liblibs.hal.system.rs2client.so"
SO_PATH_IN_APK = f"lib/arm64-v8a/{SO_NAME}"

ROOT = Path(__file__).resolve().parent.parent
ANDROID = ROOT / "data" / "client" / "android"
MANIFEST = ROOT / "data" / "client" / "clients.manifest.json"
ENV = ROOT / ".env"

# Where the embedded RSA moduli sit, as ASCII hex strings in .rodata. Located by length (a 1024-bit
# login modulus = 256 hex chars, a 4096-bit JS5 modulus = 1024 hex chars), so this stays build-agnostic.
LOGIN_HEXLEN = 256
JS5_HEXLEN = 1024


def sh(cmd):
    return subprocess.run(cmd, capture_output=True, text=True).stdout.strip()


def acquire_from_device(dst):
    paths = [l.split("package:", 1)[1] for l in sh(["adb", "shell", "pm", "path", PACKAGE]).splitlines()
             if l.startswith("package:")]
    if not paths:
        sys.exit(f"{PACKAGE} not installed on the device (adb devices to check)")
    print(f"[+] device has {len(paths)} APK(s): {', '.join(Path(p).name for p in paths)}")
    pulled = []
    for p in paths:
        local = dst / Path(p).name
        subprocess.run(["adb", "pull", p, str(local)], check=True, stdout=subprocess.DEVNULL)
        pulled.append(local)
    # the APK containing the arm64 .so is the binary source; base.apk carries dex+manifest
    for p in pulled:
        with zipfile.ZipFile(p) as z:
            if SO_PATH_IN_APK in z.namelist():
                if p.name != "base.apk":
                    print(f"[!] .so lives in split {p.name}, not base.apk — build tooling expects a "
                          f"monolithic APK; repacking split installs needs extra handling")
                return p, pulled
    sys.exit(f"none of the pulled APKs contain {SO_PATH_IN_APK}")


def acquire_url(url, dst):
    out = dst / "base.apk"
    print(f"[+] downloading {url}")
    urllib.request.urlretrieve(url, out)
    return out, [out]


def read_env_hex(key):
    if not ENV.exists():
        sys.exit(f"{ENV} not found — needed for the RSA replacement moduli ({key})")
    for line in ENV.read_text().splitlines():
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip()
    sys.exit(f"{key} not in {ENV}")


def find_modulus(data, hexlen):
    """Find a unique hex-ASCII run of exactly hexlen chars that isn't the sequential test table."""
    exact = []
    for m in re.finditer(rb'[0-9a-fA-F]+', data):
        s = m.group()
        if len(s) == hexlen and not s.startswith(b"000102030405"):
            exact.append(s.decode())
    exact = list(dict.fromkeys(exact))
    if len(exact) == 1:
        return exact[0]
    if not exact:
        sys.exit(f"no {hexlen}-hexchar modulus found in the .so — build may differ; patch offsets by hand")
    sys.exit(f"ambiguous: {len(exact)} candidate {hexlen}-hexchar moduli found; disambiguate by hand")


def main():
    ap = argparse.ArgumentParser(description="Import the RS3 Android APK + build metadata")
    src = ap.add_mutually_exclusive_group()
    src.add_argument("--from-device", action="store_true", help="adb pull the installed APK (default)")
    src.add_argument("--apk", metavar="PATH", help="import a local APK")
    src.add_argument("--url", metavar="URL", help="download from a mirror")
    ap.add_argument("--jadx", action="store_true", help="also decompile the dex with jadx")
    args = ap.parse_args()

    ANDROID.mkdir(parents=True, exist_ok=True)

    if args.apk:
        base = ANDROID / "base.apk"
        shutil.copy(args.apk, base)
        sources = [base]
        binary_apk = base
    elif args.url:
        binary_apk, sources = acquire_url(args.url, ANDROID)
    else:
        binary_apk, sources = acquire_from_device(ANDROID)

    # normalize the binary-carrying APK to base.apk for the build tooling
    base = ANDROID / "base.apk"
    if binary_apk != base:
        shutil.copy(binary_apk, base)

    print("[+] extracting native lib + dex + manifest")
    extracted = ANDROID / "extracted"
    (extracted / "lib").mkdir(parents=True, exist_ok=True)
    (extracted / "dex").mkdir(parents=True, exist_ok=True)
    so_out = extracted / "lib" / SO_NAME
    with zipfile.ZipFile(base) as z:
        names = z.namelist()
        so_out.write_bytes(z.read(SO_PATH_IN_APK))
        for n in ("classes.dex", "AndroidManifest.xml"):
            if n in names:
                (extracted / "dex" / n).write_bytes(z.read(n))

    so_bytes = so_out.read_bytes()
    # build from the engine version string in the .so (RS2Engine-<build>-NXT-<minor>), like desktop;
    # this is reliable even for device pulls (whose APKs are just named base.apk)
    eng = re.search(rb'RS2Engine-(\d+)-NXT-(\d+)', so_bytes)
    if eng:
        build, engine_version = eng.group(1).decode(), f"{eng.group(1).decode()}-{eng.group(2).decode()}"
    else:
        fn = re.search(r'-(\d{3,4})-', str(args.apk or "")) or re.search(r'(\d{3,4})', Path(binary_apk).name)
        build, engine_version = (fn.group(1) if fn else "unknown"), "unknown"
    sha256 = hashlib.sha256(so_bytes).hexdigest()
    build_id = ""
    bid = re.search(rb'BuildID\[sha1\]=([0-9a-f]+)', sh(["file", str(so_out)]).encode())
    if bid:
        build_id = bid.group(1).decode()

    print("[+] locating embedded RSA moduli")
    login_find = find_modulus(so_bytes, LOGIN_HEXLEN)
    js5_find = find_modulus(so_bytes, JS5_HEXLEN)
    login_replace = read_env_hex("PROJECTX_RSA_MODULUS")
    js5_replace = read_env_hex("PROJECTX_JS5_RSA_MODULUS")
    for name, f, r in [("login", login_find, login_replace), ("js5", js5_find, js5_replace)]:
        if len(f) != len(r):
            sys.exit(f"{name} modulus length mismatch: client {len(f)} vs Project X {len(r)} hexchars")

    moduli = [
        {"name": "login-rsa-1024", "bits": 1024, "find": login_find, "replace": login_replace},
        {"name": "js5-rsa-4096", "bits": 4096, "find": js5_find, "replace": js5_replace},
    ]
    (ANDROID / "moduli.json").write_text(json.dumps(moduli, indent=2))

    build_info = {
        "package": PACKAGE,
        "build": build,
        "engineVersion": engine_version,
        "apkName": Path(binary_apk).name,
        "splits": [p.name for p in sources],
        "so": {"name": SO_NAME, "sha256": sha256, "sizeBytes": len(so_bytes), "buildId": build_id,
               "arch": "arm64-v8a"},
        "loginModulusHexLen": len(login_find),
        "js5ModulusHexLen": len(js5_find),
        "importedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    (ANDROID / "build-info.json").write_text(json.dumps(build_info, indent=2))

    # merge an "android" entry into the shared client manifest (same schema as desktop entries)
    manifest = json.loads(MANIFEST.read_text()) if MANIFEST.exists() else {"clients": {}}
    manifest.setdefault("clients", {})["android"] = {
        "binaryType": 7,
        "label": "Android arm64-v8a",
        "path": f"android/extracted/lib/{SO_NAME}",
        "serverVersion": build,
        "engineVersion": engine_version,
        "sizeBytes": len(so_bytes),
        "sha256": sha256,
        "buildId": build_id,
        "downloadedAt": build_info["importedAt"],
    }
    manifest["updatedAt"] = build_info["importedAt"]
    MANIFEST.write_text(json.dumps(manifest, indent=4))

    if args.jadx:
        if shutil.which("jadx"):
            print("[+] jadx decompiling (this takes a minute)")
            subprocess.run(["jadx", "-d", str(extracted / "jadx"), "--no-res",
                            str(extracted / "dex" / "classes.dex")],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            print("[!] jadx not installed — skipping decompile")

    print(f"\n[+] build {build}  so sha256 {sha256[:16]}…  ({len(so_bytes)} bytes)")
    print(f"[+] base APK      -> {base}")
    print(f"[+] extracted     -> {extracted}/")
    print(f"[+] moduli.json   -> {ANDROID / 'moduli.json'}")
    print(f"[+] build-info    -> {ANDROID / 'build-info.json'}")
    print(f"[+] manifest      -> android entry in {MANIFEST}")


if __name__ == "__main__":
    main()
