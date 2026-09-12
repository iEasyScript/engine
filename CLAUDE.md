# Project X — Monorepo Guidelines

This monorepo unifies two previously separate, tightly-related projects into one all-in-one
platform for the RS3 (RuneScape 3) NXT client:

- **Project X server** — a Kotlin/JVM RS3 private server (Ktor networking, MongoDB, multi-module
  Gradle: `core`, `lobby`, `world`, `tools`) plus a **Rust launcher + LD_PRELOAD/DLL patcher**
  under `client/`.
- **Project X engine** — a Kotlin/JVM + C++20 **injection engine** (`client-plugin-engine/`) that injects
  `libprojectxbootstrap.so` into the live `rs2client` process via GDB `dlopen` (funchook hooks,
  ImGui overlay, in-process MCP, scripting framework, and a `TcpIn` network sniffer).

Both target the **same** NXT client binary (`rs2client`), share the same Ghidra RE workflow, and
now share one **knowledge hub** (`re-resources/`, a submodule) and one **all-in-one launcher**
that patches the client (Project X) **and** injects the engine (Project X) in a single step — with
**no network proxy required** (the injected sniffer replaces the deprecated login proxy).

---

## 🧠 Shared Project Memory (committed, all devs) — SMALL BY DESIGN

A **deliberately small** set of files in **`.claude/memory/`**, committed so every developer (and their
Claude sessions) gets it. The index is loaded every session:

@.claude/memory/INDEX.md

Each `[[topic-name]]` is a file at `.claude/memory/<topic-name>.md` — **read it on demand** when a task
touches that topic.

### ⛔ WRITING to this store is RARE and RESTRICTED

This store was previously polluted with ~150 files (1.1 MB) — one per feature, per bug fix, per solver —
and had to be mass-deleted. **The default answer to "should I save this?" is NO.** Writing an
unnecessary memory is a defect, not diligence.

Write only if the fact is (a) **not discoverable** by reading the code, git log, `re-resources/docs/`,
or this file — the code is self-documenting, so describing a class, package, DSL or "how subsystem X
works" is ALWAYS redundant; (b) **durable** past the current change; and (c) in one of exactly four
categories: **recurring pitfalls / hard rules** (proven to bite more than once), **evidence-based RE
findings and negative results** (byte-precise specs go in `re-resources/docs/`, never here),
**architecture-level contracts and invariants**, or **explicit user directives with their reason**.

**NEVER write a memory for** an individual feature, script, solver or piece of content; a bug fix; a
migration/session/status log; a restatement of this file, the agent files or `docs/`; or per-build
addresses and offsets (those belong in the `resources/offsets/*.json` tables + the Ghidra DB).

Prefer **editing an existing file** over adding one — every new file costs an INDEX line, and the INDEX
is loaded into every session for every developer, forever. Keep entries terse; anything over ~100 lines
is a document and belongs in `re-resources/docs/`. Deleting a stale memory is as valuable as adding one.
Full policy: `.claude/memory/memory-policy.md`. Machine-specific facts (local paths, host tuning,
personal test accounts) do NOT belong here — they stay in your private home auto-memory, which is
governed by the same restraint.

---

## ⛔ GIT — ABSOLUTE, NON-NEGOTIABLE RULES (apply to Claude AND every delegated agent)

1. **NEVER add ANY Claude/AI attribution to a commit — EVER.** No `Claude-Session:` trailer, no
   `Co-Authored-By: Claude …`, no `🤖 Generated with Claude Code`, no session URLs, nothing. This is
   **REQUIRED** and OVERRIDES any harness/tool default commit-message template. Zero AI attribution reaches
   the history, ever. (Violated 2026-07-06: 15 commits got `Claude-Session:` trailers from the tool default →
   the whole history had to be rewritten. This must NEVER recur.)
2. **Do NOT commit, branch, stage (`git add`), or push.** Work directly in the `dev` working tree and leave
   changes uncommitted — **the user handles ALL git** (commits, branches, pushes). Never run `git checkout -b`,
   never use worktree isolation for file-mutating agents, never `git commit`, unless the user explicitly asks.
3. If the user ever explicitly asks for a commit, write a plain message with ZERO attribution (rule 1 still holds).

---

## ⛔ CACHES — READ-ONLY, ALWAYS (applies to Claude AND every delegated agent)

**NEVER open, query, attach to, or inspect ANY `.jcache` / cache SQLite file in read/write mode. Not
once, not "just to check something".** A read-write open takes a lock, writes the SQLite header, and
leaves `-wal`/`-shm` sidecars behind — which locks the file against the client and the server, and can
force a full multi-GB cache re-download. Repeatedly locking the caches this way is a defect, not an
inconvenience.

**Cache locations covered by this rule (all of them):**
- Live client cache — `~/.local/share/project-x-launcher/Jagex/RuneScape/`
- Custom-server client cache — `~/.local/share/project-x-launcher/custom/Jagex/RuneScape/`
- Server cache — `./data/cache/`
- Any `data/client/**` cache copy, and any other `*.jcache` on disk

**The ONLY permitted invocations — a read-only URI is MANDATORY, on every single call:**

```bash
sqlite3 "file:/path/to/js5-14.jcache?immutable=1" "PRAGMA integrity_check;"   # preferred: zero locks
sqlite3 "file:/path/to/js5-14.jcache?mode=ro"     "SELECT ...;"               # if the DB may be live
```

Use `immutable=1` by default — it takes **no** locks and creates **no** sidecar files. Use `mode=ro`
only when the file may be concurrently written and you need a consistent read.

**BANNED — every one of these opens read-write:**
- `sqlite3 /path/to/x.jcache …` (bare path — sqlite3's default is **read-write**, and it will happily
  create the file if the path is wrong)
- `.backup`, `.clone`, `.recover`, `VACUUM`, `REINDEX`, any `PRAGMA journal_mode=…` write
- `DriverManager.getConnection("jdbc:sqlite:$path")` without `file:` + `?mode=ro` — in code, go through
  `IndexFile(readOnly = true)`; never hand-roll a connection string
- Python `sqlite3.connect(path)` without `uri=True` and `?mode=ro`/`?immutable=1`
- Any GUI/TUI browser (DB Browser for SQLite, `litecli`, …) opened without an explicit read-only flag

**If you must write to a cache, copy it first** and operate on the copy (`cp x.jcache /tmp/…`), exactly
as `BetaScanner`/`CacheAnalyzer` do. Never write in place.

**Before ANY cache access, confirm nothing owns it** — if `rs2client` or a server is running, the file
is live and `immutable=1` is the only safe mode.

**Cleanup after an accidental lock:** with the owning process stopped and the `-wal` files at 0 bytes,
delete the orphaned `*.jcache-wal` / `*.jcache-shm` sidecars, then re-verify each DB with
`PRAGMA integrity_check` + `PRAGMA journal_mode` over an `immutable=1` URI. A DB whose header reports
`wal` when the client expects `delete` has been corrupted by a read-write open.

---

## Repository Layout

```
project-x/
├── core/        lobby/   world/   tools/    # Project X server (Gradle modules)
├── client/                                  # Rust launcher (project-x-launcher) + LD_PRELOAD/DLL/dylib patchers
├── client-plugin-engine/                    # Project X injection engine == :client-plugin-engine module
│   ├── src/main/kotlin/com/projectx/…       #   cache, game/nxt, hooks, mcp, script, ui, scene, …
│   ├── native-bootstrap/                    #   C++20 bootstrap (funchook + imgui submodules)
│   └── inject                               #   GDB-dlopen injector script
├── client-plugin-engine-supervisor/         # Pure-Java hot-reload supervisor (system-classpath layer)
├── data/                                    # NXT client binaries + ~24 GB JS5 cache (cache gitignored)
├── re-resources/         (SUBMODULE)        # Unified RE knowledge hub -> gitlab:reclass-data
│   ├── docs/   (== top-level ./docs symlink)#   net/ cache/ binary/ re-methodology/ engine/
│   ├── symbols/                             #   functions.txt, parsed_functions.txt, *_handlers.txt, …
│   ├── gamevals/                            #   cache id↔name dictionaries + gameval.py
│   ├── rstypes.rcnet  *.gzf  updater/  …    #   ReClass types, client-binary archives, offset updater
│   └── CROSS_VERSION_MIGRATION.md           #   cross-build porting guide
├── ghidra-mcp/       (VENDORED MCP)         # GhidraMCP GUI plugin + headless server + bridge + host
├── docs -> re-resources/docs                # symlink to the unified docs (no duplication)
├── .claude/agents/   .claude/commands/      # merged agent roster + re-* RE commands
└── settings.gradle.kts  build.gradle.kts    # unified Gradle build (kotlin 2.3.20, JDK 25)
```

**Build:** unified Gradle build, **Kotlin 2.3.20 / JDK 25**. `./gradlew projects` shows
`:core :tools :packetlog :client-plugin-engine :client-plugin-engine-supervisor`. The engine keeps its
native CMake tasks (`:client-plugin-engine:buildNativeBootstrap` → `libprojectxbootstrap.so`). No
scripts are built here: they live in the public `iEasyScript/official-scripts` and
`iEasyScript/community-scripts` repositories, compile against the `iEasyScript/script-api` jars, and
reach users through those repositories' own releases.

**Cache source tree (unpack / repack any cache):** `world.gregs.voidps.cache.store` (containers,
reference tables, groups, the sector and SQLite stores, the converter) + `world.gregs.voidps.cache.source`
(the tree, unpacker, packer, builder, verifier, codecs). `./gradlew :tools:cacheSource -Pargs="unpack|pack|
build|verify|status|convert ..."` turns `./data/cache` into one file per asset under `./unpacked-cache/`
(gitignored until it becomes its own repository) and packs it back byte for byte into either store; the
727 sector cache converts to SQLite losslessly. The server serves the packed cache by default; setting
`CACHE_SOURCE_PATH` makes it build the tree into `CACHE_BUILD_PATH` on startup and serve that instead
(`org.projectx.core.cache.CacheSource`). Contract and layout: `re-resources/docs/cache/CACHE_SOURCE_TREE.md`;
index purposes: `re-resources/docs/cache/RS3_INDEX_CATALOGUE.md`. Parity is proven by `verify` (exit 1 on any
difference) and `WholeCacheSourceTest`; a codec that cannot reproduce a file keeps the shipped bytes in a
`.pristine` sidecar, so an imperfect codec costs disk, never bytes.

**Consumed by the ingest server:** `../projectx-3-data-project` pulls THIS repository in as its
`engine/` submodule and builds `:core` + `:packetlog` as part of its own build, so the server decodes
captures with the same code that wrote them. A change to either module reaches the server only when
that repo's submodule pointer is moved — it does not follow `dev` automatically. Its agent is
`data-ingest-engineer`, and it lives in that repo's own `.claude/agents/`.

⛔ **A change to `:core` net code is not finished until the ingest server has it.** The server decodes
every stored capture with whatever `:core` its submodule pins, so a codec fix that is not pinned is a
fix nobody gets — and the corpus keeps reporting the packets as `UNKNOWN_n`, which reads as "no decoder
exists" rather than "the server is behind". Whenever `:core`'s prot/codec/decoder code changes: land it
here, then in `../projectx-3-data-project` move `engine/` onto that commit, run `./gradlew :test`, commit
the bump, and cut an annotated `vX.Y.Z` tag — the tag is what publishes the image the server runs.

**Cloning:** the repo has submodules (`re-resources`, the engine native subs). Clone recursively:
`git clone --recursive <url>` (or `git submodule update --init --recursive`).

---

## Mobile Client (Android)

The RS3 **Android** client is the same NXT engine as desktop `rs2client`, recompiled for **AArch64**
(`liblibs.hal.system.rs2client.so`). Wire protocol, cache/JS5 formats, and the `jav_config` mechanism are
shared; the machine code is not. Goal: point the mobile client at the local Project X server.

- **Tooling:** `mobile/` (root) — Frida capture, RSA-patch, and self-contained "Project X Mobile" APK build
  (unrooted, via frida-gadget). Start at `mobile/README.md` and `mobile/CLAUDE.md`. Run `mobile/setup.sh`
  then `mobile/import-apk.py --from-device`.
- **Knowledge:** `re-resources/docs/mobile/` (browsable at `docs/mobile/`) — APK, config redirect
  (`launchurl`), login/JS5 handshake, RSA key locations, packet-capture hook points, cross-arch methodology.
- **Binaries + build metadata:** `data/client/android/` (gitignored) — APK, extracted `.so`/dex/jadx,
  `build-info.json`, generated `moduli.json`, gadget, built APKs, captures. Recorded as the `android` entry
  (`binaryType 7`) in `data/client/clients.manifest.json`; `ConfigServer` already keys `7 → android`.
- **Agent:** `mobile-reverse-engineer` owns the mobile Ghidra DB + `docs/mobile/`. Both the desktop and
  mobile binaries load in the **same Ghidra project + MCP** — cross-reference freely, but keep mobile
  findings in `docs/mobile/`. ⚠️ Desktop is **949-4**, mobile is **949-3** — same major, so the protocol
  and cache formats match, but they are NOT the same sub-revision: never assume an address ports across.

---

## CRITICAL: Mandatory Agent-Driven Workflow

**Substantive work flows through specialized agents.** Each agent has a defined role, code
ownership, and handoff points. Do NOT implement protocol/offset details from memory — defer to
documentation produced by the reverse-engineering agent (now stored in `re-resources/docs/`).

### Agent Roster (`.claude/agents/`)

| Agent | Role | Owns | Produces |
|-------|------|------|----------|
| **ghidra-reverse-engineer** | Reverse engineers the **desktop** `rs2client` (x86-64) via Ghidra MCP | Ghidra DB (renames, structs, prototypes, comments) | Protocol docs, format specs, packet layouts, struct/offset definitions |
| **mobile-reverse-engineer** | Reverse engineers the **Android** client (`liblibs.hal.system.rs2client.so`, AArch64) via Ghidra MCP | Ghidra DB for the mobile `.so` | `re-resources/docs/mobile/` — APK/config/login/JS5/RSA/capture-hook docs; see `mobile/` tooling |
| **networking-protocol-engineer** | Server networking layer | `org.projectx.core.net`, codecs, handlers, login, JS5 | Network code byte-compatible with the NXT client |
| **cache-library-engineer** | Cache read/write/serve | `world.gregs.voidps` (buffer, cache, type) | Cache library, definition decoders, JS5 provider, compression |
| **client-launcher-engineer** | Launcher, patching, injection | `client/launcher/` (Rust `project-x-launcher`) | Launcher UI, OAuth, client download, LD_PRELOAD/DLL patching, **engine injection** |
| **js5-server-engineer** | JS5 file serving | JS5 listener/protocol | JS5 server (framing, compression, caching) |
| **data-ingest-engineer** | The capture ingest server behind `rs3-data.projectx.org` | `../projectx-3-data-project` (its own repo; consumes `:core` + `:packetlog` as a submodule) | Ingest, blocking/purging, dashboard, query tokens, deploys |

RE **commands** (`.claude/commands/`): `/re-analyze`, `/re-identify`, `/re-overview`,
`/re-search`, `/re-trace` for ad-hoc Ghidra analysis.

### Workflow Rules (STRICTLY ENFORCED)

1. **No protocol/offset implementation without RE.** If you need a packet, login step, cache format,
   or struct offset, confirm it **in the Ghidra DB** — invoke the ghidra-reverse-engineer agent
   first. NEVER guess at packet formats, opcodes, or byte layouts, and never take them from a doc:
   ⛔ **per-build values are NOT written into `docs/`** (see Documentation Standards). The DB is the
   source of truth; the implementation reads it and encodes the result in code, not in prose.
2. **No cross-domain code changes.** Each agent owns its code exclusively (cache-library-engineer
   ↔ `world.gregs.voidps.*`, networking-protocol-engineer ↔ `org.projectx.core.net.*`, etc.). The
   RE agent NEVER modifies server/engine source — it produces docs + refactors the Ghidra DB.
3. **The Ghidra DB is the contract; docs carry only durable system knowledge.** The RE→implementation
   handoff is the DB itself plus the updater's generated tables. `re-resources/docs/` explains how a
   subsystem works and what stays true across builds — ⛔ never an address, offset, or opcode.
4. **When in doubt, RE first.** Any uncertain field type, opcode meaning, encoding boundary, or
   offset — stop and confirm from the binary. Wrong assumptions compound into protocol mismatches.
5. **Engine ↔ Binary ↔ Ghidra three-way sync is mandatory** (see below) — discrepancies in
   offsets/structs/names are fixed in ALL THREE places, never left unfixed.

> Because `re-resources/` is a submodule, RE/protocol docs are committed to the shared
> `reclass-data` repo — that is the point: one shared knowledge base for both server and engine.

---

## Ghidra MCP Integration

This project uses GhidraMCP to interact with Ghidra. The MCP supports **multiple simultaneous
Ghidra instances** — analysis of the stripped target alongside an unstripped reference binary.

The MCP is **vendored in-repo at `ghidra-mcp/`** (GUI plugin `GhidraMCPPlugin` + GUI-less `GhidraMCPServer` +
`bridge_mcp_ghidra.py` + PyGhidra `mcp_headless_host.py`); build/install with `ghidra-mcp/build-install.sh`
(`$GHIDRA_INSTALL_DIR`). It runs in the GUI (plugin auto-starts on 8080+) **or fully headless** with no
CodeBrowser via `start-headless-mcp.sh`/`stop-headless-mcp.sh` — writes persist to the local project DB
(autosave + `mcp__ghidra__checkpoint`) so a later GUI open shows every edit. The local project's
single-process lock means the headless host, the GUI, and `analyzeHeadless` runs are mutually exclusive —
sequence them (see `.claude/agents/rs3-update-migrator.md` §2).

### Multi-Binary Support (Reference Binary Workflow)

1. **Target binary** (`rs2client`) — the current stripped binary we're reverse engineering. **All**
   renames, structs, comments, and prototypes go here.
2. **Reference binary** (`librs2client.so`) — an older unstripped Linux build with full debug
   symbols (~12,500 named functions). **Read-only pattern-matching reference ONLY.** It is
   **SEVERELY outdated** — its offsets, struct layouts, enum values, packet structures, and
   signatures DO NOT match the modern target. Never copy concrete data from it; use it only for
   code-pattern/behavioral comparison.

Management tools: `mcp__ghidra__list_binaries` (call at session start),
`mcp__ghidra__select_binary` (e.g. `select_binary("rs2client")`),
`mcp__ghidra__discover_ghidra_instances`. All other tools accept an optional `binary_name`.

**Reference workflow (PATTERN COMPARISON ONLY):** decompile in the target first → search the
reference (`search_functions_by_name(query="ClassName", binary_name="librs2client.so")`) →
compare CODE PATTERNS (control-flow shape, behavioral purpose, call-graph) → apply to target ONLY
if certain, deriving signatures from the TARGET's own code. **Never** copy offsets, enum values,
switch cases, struct field positions, or signatures from the reference.

### MANDATORY: Progressive Ghidra Documentation

**If you decompile it and understand it, document it immediately.** Rename every identified
function (full namespace path via `rename_function_by_address`), set COMPLETE prototypes (return +
all params via `set_function_prototype`), create structs for every `*(type *)(ptr + 0xNN)` access
pattern (`create_struct` + `add_struct_field`), apply them to locals
(`set_local_variable_type` → `obj->field` access), add entry-point comments, name params/vars,
create enums for magic-number sets, set return types. Do NOT defer documentation.

### Cross-Version Sig-Scan Workflow (new client builds)

When a new build lands and the auto-updater can't relocate a function, the canonical workflow is
**byte-pattern sig-scan** (`mcp__ghidra__search_memory_pattern`), NOT name search:
1. In the OLD build, grab ~12–20 distinctive bytes of the known function's body (avoid the prologue).
2. Wildcard varying immediates (`E8`/`E9` call/jmp targets, `48 8B 05`/`48 8D 05` RIP-relative
   displacements, `mov reg,imm64` absolutes) with `??`.
3. In the NEW build, `search_memory_pattern(pattern="…")` → the match is the equivalent function.
4. Read the new offset off the analogous instruction; rename + comment; feed the name back to the
   auto-updater signature DB. See `re-resources/CROSS_VERSION_MIGRATION.md` and the worked example
   in `.claude/agents/ghidra-reverse-engineer.md`. Operational guide:
   `re-resources/docs/re-methodology/UPDATING.md` + `AUTO_UPDATER.md`.

### Symbol Discovery sources (both SEVERELY outdated — pattern-match only)

1. **Reference binary** (`librs2client.so`) — loaded in Ghidra; for class/namespace discovery +
   code-pattern comparison.
2. **`re-resources/symbols/parsed_functions.txt`** — flat `ADDRESS SYMBOL_NAME(params)` dump
   (same symbols). Useful for quick class/namespace discovery. **Addresses and parameter types are
   from the old binary and DO NOT apply to the target.** Only rename when ABSOLUTELY CERTAIN with
   multiple independent lines of evidence; otherwise leave `FUN_` + a HYPOTHESIS comment.

### Namespace Enforcement

All renamed symbols MUST use full namespace paths (`::` auto-creates the hierarchy) — never leave
symbols in Global. Top-level namespaces: **`jag`** (game engine; sub: `jag::ScriptRunner`,
`jag::game`, `jag::graphics`, `jag::input`, `jag::opcode`, `jag::Packet`, `jag::ServerProt`, …)
and **`eastl`** (EA STL). When unsure of the sub-namespace, at minimum use `jag::`.

### Struct Creation Workflow

`create_struct("StatEntry", 24, "/jag")` → `add_struct_field(...)` per offset →
`get_struct_fields(...)` to verify → `set_local_variable_type(addr, "stat", "StatEntry *")` →
re-decompile. Naming: C++-style matching the binary's namespace (e.g. `Entity`, `NPCType`), NOT
the engine Kotlin `O*` offset-object names. Cross-reference verified offsets against the engine's
`Offsets.kt` before creating.

### Available Ghidra MCP Tools (summary)

Multi-binary: `list_binaries`, `select_binary`, `discover_ghidra_instances`. Functions:
`list/search_functions_by_name`, `decompile_function[_by_address]`, `disassemble_function`,
`rename_function[_by_address]`, `get_function_by_address`. Signatures: `set_function_prototype`,
`get_function_signature`, `set_return_type`, `add/remove/change_parameter_type`, `rename_parameter`,
`set_calling_convention`. Variables: `rename_variable` (re-decompile after — auto-vars renumber),
`set_local_variable_type`. Types: `create_struct`/`add/delete_struct_field`/`get_struct_fields`,
`create_union`/`add_union_field`, `create_enum`/`add_enum_value`, `get_data_type`,
`apply_struct_to_address`. Xrefs: `get_xrefs_to/from`, `get_function_xrefs`. Pattern:
`search_memory_pattern` (IDA-style, `??`/`4?` wildcards — the primary cross-version porting tool).
Data/symbols: `rename_data`, `list_data_items`, `list_strings`, `list_segments`, `list_imports`,
`list_exports`, `list/create_namespace`, `list_namespace_contents`, `move_symbol_to_namespace`.
Comments: `set_decompiler_comment`, `set_disassembly_comment`. Selection: `get_current_address`,
`get_current_function`.

---

## Engine ↔ Binary ↔ Ghidra Three-Way Synchronization (CRITICAL)

The engine Kotlin code, binary analysis, AND Ghidra data types must always be kept in sync.

- **Source of truth for offsets:** the per-(platform, build) tables in
  `client-plugin-engine/src/main/resources/offsets/<platform>-<arch>-<build>.json`, bundled into the
  engine jar and resolved at runtime by `OffsetTable`. Values are module-relative (binaries are always
  imported into Ghidra at image base 0, so Ghidra addresses ARE the values stored here). Use them to
  fill struct fields in Ghidra and to cross-check decompiled field access.
- **`Offsets.kt` holds no values** — its `O*` objects are `by offset()` / `by count()` delegates whose
  names form the lookup key (`OClient.CAMERA` → `objects.OClient.CAMERA`). Add a field by adding it to
  BOTH the JSON table and the object. Hooks are name-keyed: `@Hook("CLIENT_MAINLOGIC")` resolves through
  `OFunctions`, and a name absent from the current platform's table leaves the hook uninstalled rather
  than hooking a bogus address.
- **Never hardcode an address in engine source.** A missing offset must surface as an absent table
  entry, not a literal.
- **Flag discrepancies, don't silently change engine code.** If the binary/Ghidra disagrees with the
  offset table, surface it to the user before editing it (unless explicitly asked).
- **Debugging-driven sync (MANDATORY):** when debugging reveals a wrong offset/type/name, fix ALL
  THREE — the Ghidra struct/field, the JSON table entry, AND the Kotlin entity/wrapper class.
  Never leave a known discrepancy unfixed.
- **Aggressive data-type creation:** every pointer-deref pattern is a struct; 2+ field accesses on
  a base pointer → create + apply a struct; every touched function gets a complete signature.

Key engine offset files: `resources/offsets/*.json` (the values), `Offsets.kt` + `OffsetTable.kt` +
`DoActionOpcode.kt` (the accessors), `types/Vector.kt`, `memory/eastl/*`, `cs2/CS2Executor.kt`.

---

## Shared Resources (`re-resources/` submodule — USE PROACTIVELY)

The unified knowledge hub. Its own git repo (`gitlab:reclass-data`), included here as a submodule.

- **`re-resources/docs/`** (= `./docs` symlink) — unified documentation:
  `net/` (protocol/packet/login/JS5/social), `cache/` (JS5/LZMA/SQLite), `binary/` (patch
  targets, RSA keys, memory layout), `re-methodology/` (UPDATING, AUTO_UPDATER),
  `engine/` (engine offset notes). RE-produced docs are committed here.
- **`re-resources/symbols/`** — RE symbol dumps (`functions.txt`, `parsed_functions.txt`,
  `opcode_handlers.txt`, `cs2_opcode_handlers.txt`, `unique_handlers.txt`, …). Outdated build —
  pattern-match/namespace-discovery only; never copy concrete data.
- **`re-resources/gamevals/`** — cache id ↔ RuneScape dev-name dictionaries for every config type
  (`npc`/`obj`/`loc`/`varbit`/`var_player`/`param`/`component`/`enum`/`struct`/`seq`/`graphic`/…).
  **Decode any bare numeric id before guessing its meaning:**
  `./re-resources/gamevals/gameval.py npc 7987` (→ `sum1_ghost_erik_bonde_no_wander`),
  `loc -s yew`, `obj -n coins` (→ 995), `-s magic_logs` (search all types). Names are unique per
  file → fully bidirectional. Pairs with our own dumps: gamevals names the id, the decoded type
  data holds its field values.
- **Our own dumps (⛔ the third-party `cs2-dumps` submodule was REMOVED — do not reintroduce it).**
  **Decompiled CS2** → `./gradlew :tools:cs2 -Pargs="decompile-all cs2-dump"` (all ~21k scripts,
  byte-exact round-trip, with recovered Jagex script names, gameval-rendered operands and type
  inference the third-party dump never had). **Decoded type data** → `./gradlew :tools:cacheUnpack`
  (field-named JSONL for every decoder type under `data/cache-unpacked/<label>/types/`,
  deterministic). We decode strictly more than the removed dump did — every one of its var domains
  to the record, plus one it lacked entirely.
- **`re-resources/rstypes.rcnet`, `*.gzf`, `updater/`, `anchor_registry.json`,
  `CROSS_VERSION_MIGRATION.md`** — ReClass types, compressed client-binary archives (939-1…948-5),
  and the offset auto-updater.

**Canonical workflow:** `gameval.py` to name an id → our own dumps (`cs2-dump/` for script usage,
`data/cache-unpacked/` for field values) to read its data → Ghidra to document the struct/function
(cite the gameval name in the comment).

---

## CS2 Engine Analysis

Namespaces: `jag::ScriptRunner` (execution), `jag::ClientScriptHelpers`, `jag::ClientScriptState`,
`jag::game::ClientScript`, `jag::opcode::*` (Camera, Core, Entities, InterfaceComponents, …).
Key data: opcode table at `0x014c1ac0`; `ClientScriptState` ≈ `0xC420` bytes (int stack `+0x100`,
string stack `+0x10a8`, long stack `+0x8db0`); return constants Success `0x01702e80`,
Yield `0x01702f80`, Error `0x01701dc0`. (Offsets are build-specific — re-verify per build.)

---

## Key Technical Details (Server)

### RS3 NXT Client Specifics
- The NXT client uses **SQLite** for cache storage (NOT legacy `.idx`/`.dat2`).
- Buffer ops follow `jag::Packet` naming: `gT<type>` reads, `pT<type>` writes; most are **inlined**
  in the modern binary — the RE agent must recognize assembly patterns.
- Encryption: **ISAAC** (opcode cipher), **RSA** (login + JS5), **XTEA/tinyKey** (data blocks).

### Client Launcher & Patching
- The launcher is a **Rust app** (`project-x-launcher`, `client/launcher/`) using **tao** + **wry** webview.
- Handles **Jagex OAuth2/PKCE**, session management, client download/update from the Jagex CDN.
- **LD_PRELOAD** (Linux `libprojectx_patcher.so`) / **DLL injection** (Windows) / **dylib** (macOS)
  patch the running client: **RSA key replacement**, **server URL redirection**, **JS5 URL
  redirection**, **configURI override**. Env-driven: `PROJECTX_RSA_MODULUS`,
  `PROJECTX_JS5_RSA_MODULUS`, `PROJECTX_HTTP_PORT`.
- Patch-target addresses/patterns are documented by the RE agent in `re-resources/docs/binary/`.

---

## Unified Launcher & Injection

The all-in-one launcher (`launch/`, see Phase 8 of the convergence) drives **both** mechanisms
against the same `rs2client` process:

1. **Patch + launch (Project X):** source `.env`, `LD_PRELOAD=data/client/linux/libprojectx_patcher.so`,
   launch `rs3linux --configURI http://localhost:$PROJECTX_HTTP_PORT/jav_config.ws`. The patcher
   rewrites RSA keys + server URLs so the client talks to the local Project X lobby/world.
2. **Inject (Project X):** once `rs2client` is up, GDB-`dlopen` `libprojectxbootstrap.so` (the
   `client-plugin-engine/inject` script) → JVM + bootstrap → funchook hooks, ImGui overlay, MCP
   (`:7882`), scripting, and the `TcpIn` **network sniffer**.

Flags: `--no-engine` (server-only), `--no-patch` (inject into a live client).

### Proxy Removal (network sniffer replaces the MITM proxy)

Project X's login proxy has been **deleted** — the `tools/.../loginproxy` package, its `run-proxy.sh`
launcher, and the patchers' `PROJECTX_PROXY_MODE` code paths are all gone. The Project X engine's
injected `TcpIn` hook reads the protocol directly from client memory, so **no network redirection**
is needed. `tools/.../JS5Proxy.kt` is unrelated and still live (JS5 traffic analysis).

The old capture-regression pipeline has been **removed**: the `:tools` `framingRegression` /
`wireFormatVerify` HARD gates and the engine's `CaptureExport` writer (env-gated
`PROJECTX_CAPTURE_EXPORT=1`) are gone, along with their dependency on the `capture/<session>/`
format (`raw-c2s.bin`, `raw-s2c.bin`, `isaac-keys.txt`). Wire-format correctness is no longer
asserted on `check`/`build`.

---

## Development Phases (Server roadmap)

Phase 1 **JS5 server & cache downloader** · Phase 2 **Lobby login** · Phase 3 **Worldlist &
social** · Phase 4 **World login** (player/NPC sync, scene build, core game packets). Each phase's
prerequisite RE docs live in `re-resources/docs/`. See git history + agent memory for current
status; do not implement a phase's protocol without its RE documentation.

---

## Documentation Standards

### ⛔ NEVER document an offset, an address, or a packet opcode. ANYWHERE.

**The Ghidra DB is the sole source of truth for every per-build fact.** Not `docs/`, not a code
comment, not a memory file. A byte-precise fact written into prose is stale the moment Jagex ships a
build, and stale per-build data has repeatedly been copied forward into the wrong binary. This rule
is absolute and overrides any older instruction to "document the packet layout".

**BANNED everywhere** (`re-resources/docs/`, source comments, KDoc, agent files, memory):
- any address or offset (`0x...`, `+0x18`, RVAs, struct field offsets, image-relative values)
- any packet opcode, opcode↔name↔size table, ClientProt/ServerProt table, or update-mask bit value
- any per-build `DecodeType` opcode table or field-index table
- version-tagged doc trees (`docs/<build>/`) — per-build snapshots do not belong in the repo

**The ONLY places per-build values may live** — for old builds and new alike:
1. the **current Ghidra DB**,
2. a **reference to the previous Ghidra DB** (program name only, no copied values),
3. **migration data emitted by the updater** (`re-resources/updater/`, `sigs-results/`),
4. the engine's `resources/offsets/*.json` tables, which are generated by that tooling — never
   hand-written.

**What documentation IS for:** durable, cross-revision *system* knowledge — how a subsystem works,
its lifecycle and sequence, invariants and constraints, why a design is the way it is, and
structural shape that survives a rebuild (field *names*, type kinds, and sizes; ordering; whether a
block is fixed or variable). Describe the mechanism, never the coordinates. If a sentence would need
editing on update day, it does not belong in a doc.

### Code Conventions
- Packet types `PascalCase` (`AddFriend`, `IfSetText`); handlers `<PacketName>Handler`; definition
  data classes `<Type>Definition`; decoders `<Type>Decoder`; constants `SCREAMING_SNAKE_CASE`.
- Packages: `org.projectx.core.net.prot` (protocol),
  `org.projectx.lobby.server.packet` / `org.projectx.world.server.packet` (handlers).
- Engine: package `com.projectx.*`; struct names are C++-style (not the `O*` Kotlin offset names).
### Code hygiene (STRICTLY ENFORCED — applies to every agent; violations are defects)
- **NO inline fully-qualified names, EVER.** Add an `import` (use `import x as Y` for collisions) and use
  the bare name — in bodies, signatures, parameter/return types, lambdas, and KDoc. Never write
  `world.gregs.voidps.X` / `com.projectx.Y` / `java.io.File` inline. This is a hard ban.
- **Minimal comments — default to ZERO.** Code MUST be self-documenting through good names. A comment is
  justified ONLY to explain a genuinely complex/non-obvious *why* (a constraint, invariant, surprising
  behaviour). First try renaming a variable or extracting a function. **BANNED inline:** byte/field tables,
  disassembly addresses (`@0x...`), struct offsets, handler internals, "binary-confirmed"/"asm-verified"
  narration, `per A2 §…`/ticket/date references, and step-by-step descriptions of what the code does — that
  is RE/protocol documentation and lives in `re-resources/docs/` + the Ghidra DB, NEVER inline in source.
- **Reference packets by NAME, never `op#`** (`REBUILD_NORMAL`, `UPDATE_ZONE_FULL_FOLLOWS`, …); a raw
  opcode is only for a genuinely unidentified packet (`UNKNOWN_<n>`).
- When editing ANY file, trim verbose comments and inline FQNs you encounter.
