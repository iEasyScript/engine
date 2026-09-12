#!/usr/bin/env python3
"""Patch the embedded RSA moduli in the client .so, in place.

The moduli are ASCII hex strings in .rodata; replacements are the same length, so this is a
byte-for-byte overwrite. Idempotent: re-running after a patch is a no-op (finds nothing to change).
"""

import argparse
import json
import sys
from pathlib import Path


def main():
    ap = argparse.ArgumentParser(description="Patch embedded RSA moduli in a .so")
    ap.add_argument("so", help="path to the .so to patch (modified in place)")
    ap.add_argument("--moduli",
                    default=str(Path(__file__).resolve().parent.parent
                                / "data" / "client" / "android" / "moduli.json"))
    args = ap.parse_args()

    so = Path(args.so)
    data = bytearray(so.read_bytes())
    moduli = json.loads(Path(args.moduli).read_text())

    changed = 0
    for m in moduli:
        find = m["find"].encode()
        replace = m["replace"].encode()
        if len(find) != len(replace):
            sys.exit(f"length mismatch for {m['name']}: {len(find)} vs {len(replace)}")
        idx = data.find(find)
        if idx == -1:
            if data.find(replace) != -1:
                print(f"  {m['name']}: already patched")
            else:
                print(f"  {m['name']}: NOT FOUND (neither original nor replacement)")
            continue
        # patch every occurrence
        n = 0
        while idx != -1:
            data[idx:idx + len(replace)] = replace
            n += 1
            changed += 1
            idx = data.find(find, idx + len(replace))
        print(f"  {m['name']}: patched {n} occurrence(s) @ first 0x{data.find(replace):x}")

    if changed:
        so.write_bytes(bytes(data))
        print(f"[+] wrote {so} ({changed} patch site(s))")
    else:
        print("[=] no changes")


if __name__ == "__main__":
    main()
