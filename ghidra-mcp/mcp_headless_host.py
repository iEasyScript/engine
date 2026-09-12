#!/usr/bin/env python3
"""
Headless host for the GhidraMCP server — no GUI required.

Opens one or more programs from a LOCAL Ghidra project and starts a GhidraMCPServer (the
GUI-less HTTP server extracted from GhidraMCPPlugin) for each on consecutive ports, so the
existing bridge_mcp_ghidra.py + every mcp__ghidra__* tool work with no CodeBrowser open.

Each --write program is opened writable (holding its own exclusive file lock) starting at
--base-port; each --read program is opened read-only on the following ports. Writable edits are
flushed to the project DB periodically and on shutdown, so a human opening the same program in
the GUI afterwards sees every rename / label / comment / struct.

Because a local project takes a single-process lock, the GUI and any analyzeHeadless run
(run_updater.py, RS3SignatureUpdater, ...) must NOT hold the project while this host runs —
sequence them (bulk analyzeHeadless first, then this host, then the GUI).

Usage (see start-headless-mcp.sh):
  python mcp_headless_host.py \
      --project-dir /home/trent/ghidra-proj --project-name nxt-exe-2024-9-25 \
      --write rs2client.949-4 --read rs2client.949-1 [--read librs2client.so] \
      [--base-port 8080] [--save-interval 120]
"""
import argparse
import os
import signal
import sys
import threading
import time
from pathlib import Path

import pyghidra


def resolve_ghidra_home() -> Path:
    home = os.environ.get("GHIDRA_INSTALL_DIR")
    if not home:
        sys.exit("GHIDRA_INSTALL_DIR is not set (start-headless-mcp.sh exports it).")
    return Path(home)


def main() -> int:
    ap = argparse.ArgumentParser(description="Headless GhidraMCP host")
    ap.add_argument("--project-dir", required=True)
    ap.add_argument("--project-name", required=True)
    ap.add_argument("--write", action="append", required=True, help="program name(s) opened writable")
    ap.add_argument("--read", action="append", default=[], help="program name(s) opened read-only")
    ap.add_argument("--base-port", type=int, default=8080)
    ap.add_argument("--save-interval", type=int, default=120, help="seconds between autosaves of the writable program")
    args = ap.parse_args()

    pyghidra.start(install_dir=resolve_ghidra_home())

    from java.lang import Object as JObject
    from ghidra.framework.model import DomainFile
    from ghidra.util.task import ConsoleTaskMonitor
    from com.lauriewired import GhidraMCPServer

    monitor = ConsoleTaskMonitor()
    project = pyghidra.open_project(args.project_dir, args.project_name)
    project_data = project.getProjectData()

    opened = []   # list of (program, consumer, server, writable)
    stop_event = threading.Event()

    def open_program(name: str, writable: bool):
        df = project_data.getFile("/" + name)
        if df is None:
            sys.exit(f'program "{name}" not found in project {args.project_name}')
        consumer = JObject()
        if writable:
            program = df.getDomainObject(consumer, True, False, monitor)   # exclusive lock
        else:
            program = df.getReadOnlyDomainObject(consumer, DomainFile.DEFAULT_VERSION, monitor)
        return program, consumer

    port = args.base_port
    write_programs = []
    try:
        # writable targets first → base-port upwards
        for name in args.write:
            program, consumer = open_program(name, writable=True)
            write_programs.append(program)
            server = GhidraMCPServer(program, port)
            if len(write_programs) == 1:
                server.setOnShutdown(lambda: stop_event.set())   # best-effort; SIGTERM is the reliable path
            server.start()
            opened.append((program, consumer, server, True))
            print(f"[headless-mcp] {name} (writable) -> :{port}", flush=True)
            port += 1

        for name in args.read:
            program, consumer = open_program(name, writable=False)
            server = GhidraMCPServer(program, port)
            server.start()
            opened.append((program, consumer, server, False))
            print(f"[headless-mcp] {name} (read-only) -> :{port}", flush=True)
            port += 1
    except Exception as e:
        print(f"[headless-mcp] startup failed: {e}", flush=True)
        _teardown(opened, write_programs, monitor, project)
        raise

    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stop_event.set())

    print(f"[headless-mcp] ready ({len(opened)} program(s)); Ctrl-C or SIGTERM / POST /shutdown to stop", flush=True)

    last_save = time.time()
    while not stop_event.is_set():
        time.sleep(1)
        if time.time() - last_save >= args.save_interval:
            for wp in write_programs:
                _save_if_changed(wp, monitor, "headless autosave")
            last_save = time.time()

    print("[headless-mcp] stopping...", flush=True)
    _teardown(opened, write_programs, monitor, project)
    return 0


def _save_if_changed(program, monitor, comment: str):
    if program is None:
        return
    try:
        if program.isChanged() and program.getCurrentTransactionInfo() is None:
            program.save(comment, monitor)
            print("[headless-mcp] saved " + program.getName(), flush=True)
    except Exception as e:
        print(f"[headless-mcp] save skipped: {e}", flush=True)


def _teardown(opened, write_programs, monitor, project):
    for wp in write_programs:
        _save_if_changed(wp, monitor, "headless final save")
    for program, consumer, server, _writable in opened:
        try:
            server.stop()
        except Exception:
            pass
        try:
            program.release(consumer)
        except Exception:
            pass
    try:
        project.close()   # release the single-process project lock so the GUI can open
    except Exception:
        pass


if __name__ == "__main__":
    sys.exit(main())
