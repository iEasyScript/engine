# RE workflow — resources, tooling, techniques

## Canonical lookup order (do this before guessing what an id means)

1. **`re-resources/gamevals/gameval.py`** — decode any bare numeric cache id to its RuneScape dev name,
   both directions, for every config type: `gameval.py npc 7987`, `loc -s yew`, `obj -n coins`,
   `-s magic_logs` (search all types). Names are unique per file.
2. **Our own dumps** — `cs2-dump/` (`./gradlew :tools:cs2 -Pargs="decompile-all cs2-dump"`, all
   ~21k clientscripts, byte-exact round-trip, with recovered script names and gameval-rendered
   operands) and `data/cache-unpacked/<label>/types/` (`./gradlew :tools:cacheUnpack`, field-named
   JSONL for every decoder type). Grep `cs2-dump/` for real callers before theorising about an
   opcode/var/interface.
   ⛔ **The third-party `re-resources/cs2-dumps` submodule was REMOVED — never reintroduce it.**
   We decode every var domain it had, record for record, plus CONTROLLER which it lacked entirely.
   ⚠️ **A decoder pointed at a missing archive returns nothing and reports success** — that is how
   the clan domains sat empty behind pre-RS3 archive ids with no error on any health signal. Assert
   a record count, not just absence of failure (`VarDomainIntegrityIntegrationTest`).
3. **Ghidra** — document the struct/function, citing the gameval name in the comment.

`re-resources/docs/` (= `./docs`) is the **handoff contract** between RE and the implementation agents:
⛔ NO build-specific data in `docs/` at all — per-build offsets/opcodes live ONLY in the Ghidra DB and the updater's generated tables. `docs/` is build-agnostic
specs at the roots (`net/`, `cache/`, `binary/`, `re-methodology/`, `mobile/`). Byte-precise packet and
format specs belong there, never in a memory file and never inline in source.

## Ghidra MCP

Vendored at `ghidra-mcp/` (GUI plugin + GUI-less server + bridge + PyGhidra host); build/install via
`ghidra-mcp/build-install.sh`. Runs in the GUI (auto-starts on 8080+) **or fully headless** via
`start-headless-mcp.sh` — writes persist to the local project DB (autosave + `checkpoint`), so a later
GUI open shows every edit. ⛔ The local project's single-process lock makes the headless host, the GUI
and `analyzeHeadless` **mutually exclusive** — sequence them, never run two at once.

Call `list_binaries` at session start, then `select_binary("rs2client")`; every tool takes an optional
`binary_name`. Tool caveats worth knowing: `rename_variable` renumbers auto-vars, so re-decompile
before the next rename; `set_local_variable_type` reports "Type not found directly" but still succeeds
via the `/jag` category fallback; `list_functions` is huge — prefer `search_functions_by_name`.

## Techniques that actually land identifications

- **Caller-graph anchoring beats sig-scan** for medium-sized functions: walk out from one trustworthy
  anchor to its callers and callees and derive identities from position in the graph.
- **SSE intrinsic byte patterns are gold** — they survive optimisation changes and are highly
  distinctive. For any math/render function, search the intrinsic pattern first.
- **Field-width changes defeat naive signature transfer.** A flag widened byte→word changes the
  instruction opcode and length, so a pattern lifted from the old build silently fails. Recompute the
  pattern from the new build's own access.
- **Offset drift is roughly consistent within a class but not exact** — confirm each field; never
  extrapolate a whole layout from one shifted field.
- `set_function_prototype` keeps a stale `__thiscall` even when wrong — follow with an explicit
  `set_calling_convention`.

[[re-discipline]] [[cross-version-migration]] [[client-internals-re]]
