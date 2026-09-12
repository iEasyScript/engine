# GhidraMCP (vendored) — GUI plugin + headless server + MCP bridge

The Ghidra MCP for this monorepo, vendored so it can be improved in-tree and built/loaded from here.
Based on [LaurieWired/GhidraMCP](https://github.com/LaurieWired/GhidraMCP) (Apache-2.0, see `LICENSE`),
extended to run **fully headless** (no GUI) while keeping the local Ghidra project DB in sync.

## Layout
```
ghidra-mcp/
├── bridge_mcp_ghidra.py     # MCP server the agent talks to (HTTP client; scans ports 8080-8089)
├── src/main/java/com/lauriewired/
│   ├── GhidraMCPServer.java  # GUI-less HTTP server; holds a Program directly; /save + /shutdown
│   └── GhidraMCPPlugin.java   # thin GUI plugin wrapper delegating to GhidraMCPServer
├── mcp_headless_host.py     # PyGhidra host: opens programs from the project, serves the MCP headless
├── build-install.sh         # mvn package + install the jar into the Ghidra Extensions dir
├── start-headless-mcp.sh    # bring the headless host up (background) + readiness gate
└── stop-headless-mcp.sh     # final-save + release the project lock so the GUI can open
```
`.mcp.json` (repo root) already launches `ghidra-mcp/bridge_mcp_ghidra.py`.

## Build + install
Requires `$GHIDRA_INSTALL_DIR` (defaults to `/home/trent/projects/ghidra/build/dist/ghidra_12.1_DEV`)
and `mvn`. Deps resolve from the local Ghidra install — no jars are committed.
```
GHIDRA_INSTALL_DIR=/path/to/ghidra ./build-install.sh
```
Produces one `GhidraMCP.jar` (both classes) and installs it to
`~/.config/ghidra/<ghidra_ver>/Extensions/GhidraMCP/lib/`. The **same jar** serves the GUI plugin and
the headless host. Restart the Ghidra GUI to pick it up.

## GUI mode (unchanged)
Open a program in CodeBrowser; `GhidraMCPPlugin` auto-starts the HTTP server on 8080+ (per open tool).
The bridge finds it by scanning the port range. Same behavior as upstream.

## Headless mode (no GUI)
The migration RE flow runs with no CodeBrowser open. A local Ghidra project takes a **single-process
lock**, so the headless host, the GUI, and any `analyzeHeadless` run (`run_updater.py`,
`RS3SignatureUpdater`, …) are mutually exclusive and must be **sequenced** — never overlapped.

```
# 1) (project free) run the bulk headless tools first — these already persist via save-on-exit:
#    RS3SignatureUpdater / RS3ProjectXUpdater (run_updater.py --apply) / RS3DataType* / RS3ProtFinder
# 2) start the MCP host. --write is repeatable: every writable program gets its own
#    exclusive file lock and its own port, so both platform DBs can be kept in sync in
#    one session. Read-only programs follow on the ports after the writable ones.
./start-headless-mcp.sh --write rs2client.949-4 --write rs2client.exe.949-4
# (or new writable @8080 with an older build read-only @8081:)
./start-headless-mcp.sh --write rs2client.949-4 --read rs2client.949-1
# 3) drive it with the normal mcp__ghidra__* tools (writes -> new, reads of old -> binary_name=old);
#    call mcp__ghidra__checkpoint periodically to flush edits to disk.
# 4) tear down (final save + release the lock):
./stop-headless-mcp.sh
# 5) a human opening rs2client.949-4 in the GUI now sees every rename / label / comment / struct.
```
Deterministic ports (write=8080, read=8081…) remove the port-swap hazard; still pass `binary_name=`.

### Persistence
MCP writes are transactional in-memory edits. The host autosaves the writable program every
`--save-interval` seconds and on shutdown; `mcp__ghidra__save`/`checkpoint` forces an immediate flush;
`mcp__ghidra__shutdown_headless` does a final save + releases the lock. Read-only programs never save.

## New MCP tools (bridge)
`save_program` / `checkpoint` — flush the target program to the project DB now. `shutdown_headless` —
session teardown (final save + release). All route by `binary_name` like every other tool.
