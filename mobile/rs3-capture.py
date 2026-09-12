#!/usr/bin/env python3
"""Live packet dump for the RS3 mobile client.

Hooks the client's own decode path, so packets arrive already ISAAC-deciphered,
size-resolved and framed. Optionally arms the launchurl config redirect.
"""

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

import frida

PACKAGE = "com.jagex.runescape.android"
AGENT = Path(__file__).with_name("rs3-capture.js")
RVA_FILE = Path(__file__).with_name("rva.json")
ANDROID_DIR = Path(__file__).resolve().parent.parent / "data" / "client" / "android"

ARROW = {"s2c": "S->C", "c2s": "C->S"}


def hexdump(data, limit):
    if not data:
        return ""
    body = " ".join(f"{b:02x}" for b in data[:limit])
    return body + (f" ... ({len(data)} bytes)" if len(data) > limit else "")


class Dump:
    def __init__(self, out, width, quiet):
        self.out = out
        self.width = width
        self.quiet = quiet
        self.t0 = time.time()
        self.counts = {"s2c": 0, "c2s": 0, "desync": 0}
        self.out_dir = out
        # packets.log: human-readable, FULL payload hex (untruncated)
        self.log = open(out / "packets.log", "w") if out else None
        # packets.jsonl: one machine-readable object per packet, for decoder tooling
        self.jsonl = open(out / "packets.jsonl", "w") if out else None
        self.wire = {}

    def _console(self, text):
        print(text)

    def _logfull(self, text):
        if self.log:
            self.log.write(text + "\n")
            self.log.flush()

    def _record(self, obj):
        if self.jsonl:
            self.jsonl.write(json.dumps(obj) + "\n")
            self.jsonl.flush()

    def stamp(self):
        return f"[{time.time() - self.t0:08.3f}]"

    def _packet(self, direction, opcode, size, data, source):
        self.counts[direction] += 1
        full = data.hex() if data else ""
        t = time.time() - self.t0
        head = f"{self.stamp()} [{ARROW[direction]}] op={opcode:<4} size={size:<6}"
        self._console(f"{head} {hexdump(data, self.width)}")
        self._logfull(f"{head} {full}")
        self._record({"t": round(t, 4), "dir": direction, "op": opcode,
                      "size": size, "src": source, "hex": full})

    def on_message(self, message, data):
        if message["type"] == "error":
            print(f"[!] agent error: {message.get('description', message)}", file=sys.stderr)
            return

        p = message["payload"]
        kind = p.get("kind")

        if kind == "log":
            print(f"[agent] {p['message']}")
            self._logfull(f"{self.stamp()} [agent] {p['message']}")
        elif kind == "module":
            print(f"[+] {p['name']} base={p['base']} size={p['size']}")
        elif kind == "rsa":
            st = p["status"]
            if st == "patched":
                print(f"[+] RSA patched: {p['name']} x{p['count']} @ {p['at']}")
            else:
                print(f"[!] RSA {p['name']}: {st}" + (f" ({p['reason']})" if p.get("reason") else ""))
        elif kind == "connect":
            line = f"{self.stamp()} [CONN] fd={p['fd']} -> {p['ip']}:{p['port']}"
            self._console(line)
            self._logfull(line)
        elif kind == "blocked":
            self.counts["blocked"] = self.counts.get("blocked", 0) + 1
            line = f"{self.stamp()} [BLOCKED] fd={p['fd']} -> {p['ip']}:{p['port']} (isolation)"
            self._console(line)
            self._logfull(line)
        elif kind == "disconnect":
            line = f"{self.stamp()} [DISC] fd={p['fd']}"
            self._console(line)
            self._logfull(line)
        elif kind == "packet":
            self._packet(p["dir"], p["opcode"], p["size"], data, "decoded")
        elif kind == "outop":
            # opcode + resolved size before payload is appended; payload comes via the stream hook
            line = f"{self.stamp()} [C->S] op={p['opcode']:<4} size={p['size']:<6} (opcode only, pre-ISAAC)"
            self._console(line)
            self._logfull(line)
            self._record({"t": round(time.time() - self.t0, 4), "dir": "c2s",
                          "op": p["opcode"], "size": p["size"], "src": "opcode-only", "hex": ""})
        elif kind == "isaac":
            keys = ", ".join(f"0x{k:08X}" for k in p["keys"])
            line = f"{self.stamp()} [ISAAC] {p['which']:<10} {keys}"
            self._console(line)
            self._logfull(line)
            self._record({"t": round(time.time() - self.t0, 4), "kind": "isaac",
                          "which": p["which"], "keys": [f"0x{k:08X}" for k in p["keys"]]})
        elif kind == "directlogin":
            st = p["status"]

            def _steps(s):
                if not s:
                    return ""
                if "err" in s:
                    return f" (read-err {s['err']})"
                return (f" state={s['state']} step={s['step']} "
                        f"sub16c={s['sub16c']} sub1b8={s['sub1b8']} main={s['main']}")

            def _es(e):
                return f"size={e['size']} '{e['text']}'" if e else "?"

            if st == "mgr-captured":
                line = f"{self.stamp()} [direct-login] captured loginMgr={p['mgr']}"
            elif st == "state":
                line = f"{self.stamp()} [direct-login] client main-state -> {p['main']}"
            elif st == "pre":
                line = f"{self.stamp()} [direct-login] pre-call ({p['via']}){_steps(p.get('steps'))}"
            elif st == "built":
                line = (f"{self.stamp()} [direct-login] built args: "
                        f"user {_es(p.get('builtUser'))}, pass {_es(p.get('builtPass'))}")
            elif st == "login":
                line = (f"{self.stamp()} [direct-login] BeginLobbyLogin fired for '{p['user']}' "
                        f"via {p['via']} — stored user {_es(p.get('storedUser'))}, "
                        f"pass {_es(p.get('storedPass'))} (lobby: sub1b8=-3 => both landed);"
                        f"{_steps(p.get('steps'))}")
            elif st == "post":
                line = f"{self.stamp()} [direct-login] post{_steps(p.get('steps'))}"
            else:
                line = (f"{self.stamp()} [direct-login] {st}" +
                        (f" ({p['reason']})" if p.get("reason") else ""))
            self._console(line)
            self._logfull(line)
        elif kind == "drain":
            pass
        elif kind == "desync":
            self.counts["desync"] += 1
            line = (f"{self.stamp()} [DESYNC] {ARROW.get(p['dir'], '?')} op={p['opcode']} "
                    f"size={p['size']} — {p['reason']}")
            self._console(line)
            self._logfull(line)
        elif kind == "stream":
            # raw login/handshake bytes (pre-TcpIn). Full hex to log + append to per-dir bin.
            full = data.hex() if data else ""
            head = f"{self.stamp()} [{ARROW[p['dir']]}] [stream] len={p['len']}"
            if not self.quiet:
                self._console(f"{head} {hexdump(data, self.width)}")
            self._logfull(f"{head} {full}")
            self._store_wire("stream-" + p["dir"], data)
        elif kind == "wire":
            self._store_wire(f"wire-fd{p['fd']}-{p['dir']}", data)

    def _store_wire(self, name, data):
        if not self.out_dir or not data:
            return
        fh = self.wire.get(name)
        if fh is None:
            fh = open(self.out_dir / f"{name}.bin", "wb")
            self.wire[name] = fh
        fh.write(data)
        fh.flush()

    def close(self):
        for fh in self.wire.values():
            fh.close()
        if self.log:
            self.log.close()
        if self.jsonl:
            self.jsonl.close()


def load_rvas(path):
    if not path.exists():
        return {}
    raw = json.loads(path.read_text())
    return {k: (int(v, 16) if isinstance(v, str) else v) for k, v in raw.items() if v is not None}


def main():
    ap = argparse.ArgumentParser(description="RS3 mobile live packet dump")
    ap.add_argument("--host", help="redirect config to HOST:PORT (private server), e.g. 10.69.69.50:8829")
    ap.add_argument("--live", action="store_true",
                    help="no redirect — let the client reach the real game (for comparison dumps)")
    ap.add_argument("--isolate", action="store_true",
                    help="block every outbound connection except the server (and loopback) — "
                         "stops crash reports / analytics / OAuth from reaching Jagex or third parties")
    ap.add_argument("--allow", action="append", default=[], metavar="IP",
                    help="extra IP to allow through --isolate (repeatable)")
    ap.add_argument("--out", default=str(ANDROID_DIR / "captures"),
                    help="output dir root ('' to disable files)")
    ap.add_argument("--raw", action="store_true", help="also dump raw socket bytes (noisy)")
    ap.add_argument("--width", type=int, default=32, help="hex bytes shown per packet")
    ap.add_argument("--quiet", action="store_true", help="suppress stream lines")
    ap.add_argument("--attach", action="store_true", help="attach instead of spawn")
    ap.add_argument("--gadget", action="store_true",
                    help="attach to a frida-gadget-injected app (unrooted), then resume it")
    ap.add_argument("--gadget-port", type=int, default=27042,
                    help="gadget listen port (must match libgadget.config.so)")
    ap.add_argument("--device", help="frida device id")
    ap.add_argument("--package", default=PACKAGE)
    ap.add_argument("--rvas", default=str(RVA_FILE), help="json of hook RVAs")
    ap.add_argument("--patch-rsa", nargs="?", const=str(ANDROID_DIR / "moduli.json"),
                    default=None, metavar="MODULI_JSON",
                    help="patch the client's embedded RSA moduli to the server's keys "
                         "(default: data/client/android/moduli.json). Note: for an embedded gadget "
                         "this is usually too late (static-init already parsed the keys) — prefer the "
                         "baked projectx-mobile.apk from build-projectx-mobile.sh.")
    ap.add_argument("--direct-login-user", metavar="USER",
                    help="force a plain username+password login: capture loginMgr and call the "
                         "client's native BeginDirectLogin so it emits the standard op19 direct-login "
                         "block (bypasses the OAuth 'email/password' button). Needs --direct-login-pass.")
    ap.add_argument("--direct-login-pass", metavar="PASS",
                    help="password to pair with --direct-login-user")
    args = ap.parse_args()

    if bool(args.direct_login_user) != bool(args.direct_login_pass):
        sys.exit("--direct-login-user and --direct-login-pass must be given together")
    direct_login = {
        "enabled": bool(args.direct_login_user),
        "username": args.direct_login_user or "",
        "password": args.direct_login_pass or "",
    }
    if direct_login["enabled"]:
        print(f"[+] direct-login: driving native BeginDirectLogin for '{direct_login['username']}' "
              "(client will send op19; no OAuth)")

    if not AGENT.exists():
        sys.exit(f"agent not found: {AGENT}")

    rvas = load_rvas(Path(args.rvas))
    if rvas:
        print(f"[+] RVAs: {', '.join(f'{k}=0x{v:x}' for k, v in rvas.items())}")
    else:
        print("[!] no RVAs configured — raw socket capture only, no decoded packets")

    rsa_patch = []
    if args.patch_rsa:
        rsa_patch = json.loads(Path(args.patch_rsa).read_text())
        print(f"[+] RSA patch: {', '.join(p['name'] for p in rsa_patch)}")

    if args.live and args.host:
        sys.exit("--live and --host are mutually exclusive")
    redirect_host = None if args.live else args.host
    if args.live:
        print("[+] LIVE mode — no redirect, client reaches the real game")
    elif redirect_host:
        print(f"[+] private mode — redirecting config to {redirect_host}")
    else:
        print("[!] no --host and no --live — client uses its own default config (real game)")

    isolate_allow = list(args.allow)
    if args.isolate:
        if redirect_host:
            isolate_allow.append(redirect_host.rsplit(":", 1)[0])
        if args.live:
            sys.exit("--isolate with --live makes no sense (live needs to reach Jagex)")
        if not isolate_allow:
            sys.exit("--isolate needs --host (or --allow IP) to know what to permit")
        print(f"[+] ISOLATION ON — only these reach the network: {', '.join(isolate_allow)} + loopback")

    out = None
    if args.out:
        out = Path(args.out) / time.strftime("mobile-%Y%m%d-%H%M%S")
        out.mkdir(parents=True, exist_ok=True)

    dump = Dump(out, args.width, args.quiet)

    gadget_pid = None
    if args.gadget:
        # Embedded gadget in listen mode: reach it over its own socket, not the USB
        # process list. adb forward maps PC:port -> phone loopback:port, then we attach
        # to the single process the gadget exposes ("Gadget").
        adb = ["adb"] + (["-s", args.device] if args.device else [])
        subprocess.run(adb + ["forward", f"tcp:{args.gadget_port}", f"tcp:{args.gadget_port}"],
                       check=True, stdout=subprocess.DEVNULL)
        addr = f"127.0.0.1:{args.gadget_port}"
        print(f"[+] connecting to gadget at {addr} (adb-forwarded)")
        mgr = frida.get_device_manager()
        dev = None
        for attempt in range(20):
            try:
                dev = mgr.add_remote_device(addr)
                dev.enumerate_processes()
                break
            except Exception:
                if attempt == 0:
                    print("[.] waiting for the gadget — launch the app now if you haven't")
                time.sleep(1)
        if dev is None:
            sys.exit(f"could not reach gadget at {addr} — is the repacked app launched?")
        session = dev.attach("Gadget")
        print("[+] attached to embedded gadget")
    else:
        try:
            dev = frida.get_device(args.device) if args.device else frida.get_usb_device(timeout=10)
        except frida.InvalidArgumentError as e:
            sys.exit(f"device not found: {e}")
        except Exception as e:
            sys.exit(
                f"no device: {e}\n"
                "  needs an arm64 device with frida-server running (adb devices to check),\n"
                "  or --gadget for a frida-gadget repacked APK on an unrooted device."
            )
        print(f"[+] device: {dev.name} ({dev.id})")
        if args.attach:
            gadget_pid = dev.get_process(args.package).pid
            print(f"[+] attaching to pid {gadget_pid}")
            session = dev.attach(gadget_pid)
        else:
            print(f"[+] spawning {args.package}")
            gadget_pid = dev.spawn([args.package])
            session = dev.attach(gadget_pid)

    script = session.create_script(AGENT.read_text())
    script.on("message", dump.on_message)
    script.load()
    script.exports_sync.init({"redirectHost": redirect_host, "raw": args.raw,
                              "rva": rvas, "rsaPatch": rsa_patch,
                              "isolate": args.isolate, "isolateAllow": isolate_allow,
                              "directLogin": direct_login})

    if args.gadget:
        try:
            dev.resume("Gadget")
            print("[+] resumed gadget-held app")
        except Exception as e:
            print(f"[!] resume failed (app may already be running): {e}")
    elif not args.attach:
        dev.resume(gadget_pid)

    if out:
        print(f"[+] writing to {out}")
    print("[+] Ctrl-C to stop\n")
    try:
        sys.stdin.read()
    except KeyboardInterrupt:
        pass
    finally:
        dump.close()
        c = dump.counts
        blk = f", {c['blocked']} blocked" if c.get("blocked") else ""
        print(f"\n[+] {c['s2c']} S->C, {c['c2s']} C->S, {c['desync']} desync{blk}")
        if out:
            print(f"[+] {out}")


if __name__ == "__main__":
    main()
