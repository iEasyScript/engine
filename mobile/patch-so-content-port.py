#!/usr/bin/env python3
"""Patch the mobile client's hardcoded on-demand-content HTTP port (80) in the .so.

The client builds `GET http://<contentHost>:80/ms?...` for on-demand JS5 (music/media streaming). The
port 80 is a literal `mov w9,#0x50` in the base-URL builder (FUN_009a517c @ 0x009a5208) — no jav_config
key can move it, and port 80 isn't available on the server. So we rewrite that immediate to the server's
config port, resolving content to `http://<contentHost>:<port>/ms?...` (served by the existing /ms
handler). See re-resources/docs/mobile/net/content-js5-http.md.

Patched by unique instruction context (the two fixed branches bracketing the port load), so it hits
exactly the content-port site and none of the ~43 other `mov w9,#0x50` instructions in the binary.
Idempotent against a fresh (unpatched) .so; the build always extracts a pristine .so.
"""

import argparse
import sys
from pathlib import Path

# 0x009a5204  b   0x009a5214   -> 04 00 00 14   (branch into the URL builder)
# 0x009a5208  mov w9, #<port>  -> the MOVZ immediate we rewrite (0x50 = 80 in the stock client)
# 0x009a520c  b   0x009a5218   -> 03 00 00 14
CTX_PRE = bytes.fromhex("04000014")
CTX_POST = bytes.fromhex("03000014")


def movz_w9(imm: int) -> bytes:
    if not (0 <= imm <= 0xFFFF):
        sys.exit(f"port {imm} out of range for a single MOVZ immediate (0..65535)")
    return (0x52800000 | ((imm & 0xFFFF) << 5) | 9).to_bytes(4, "little")


def main():
    ap = argparse.ArgumentParser(description="Rewrite the mobile content-server HTTP port in a .so")
    ap.add_argument("so", help="path to the .so to patch (modified in place)")
    ap.add_argument("--port", type=int, required=True, help="server config port the client should use for /ms")
    args = ap.parse_args()

    so = Path(args.so)
    data = bytearray(so.read_bytes())
    orig = CTX_PRE + movz_w9(0x50) + CTX_POST
    new = CTX_PRE + movz_w9(args.port) + CTX_POST

    if orig == new:
        print("  content-port: server already uses port 80 — nothing to patch")
        return

    n = data.count(orig)
    if n == 0:
        if data.count(new):
            print(f"  content-port: already patched to {args.port}")
            return
        sys.exit("content-port site not found (unexpected .so layout — re-run the RE for this build)")
    if n != 1:
        sys.exit(f"content-port context not unique ({n} matches) — refusing to patch")

    idx = data.find(orig)
    data[idx:idx + len(new)] = new
    so.write_bytes(bytes(data))
    print(f"  content-port: 80 -> {args.port} @ 0x{idx + len(CTX_PRE):x}")


if __name__ == "__main__":
    main()
