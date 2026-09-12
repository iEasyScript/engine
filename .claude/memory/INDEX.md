# project-x — Shared Project Memory (INDEX)

Committed, VCS-shared knowledge, loaded every session. One line per topic; each `[[topic]]` is
`.claude/memory/<topic>.md` — **read it on demand** when a task touches that topic.

> ⛔ **DO NOT WRITE NEW MEMORIES CASUALLY.** This store is deliberately small. It holds ONLY recurring
> pitfalls, evidence-based RE findings, architecture-level contracts, and explicit user directives —
> never features, scripts, solvers, bug fixes, session logs, or anything discoverable by reading the
> code, `re-resources/docs/`, or CLAUDE.md. **Read [[memory-policy]] before adding or editing anything
> here.** Prefer editing an existing file to adding one; prefer saying it in your reply to both.

## Rules (always apply)
- [[hard-rules]] — ⛔ NEVER add guarded/"safe" memory reading: an invalid access is an engine defect, fix the
  structure size, the traversal or the race; ⛔ ZERO AI attribution in git + user handles ALL git (no commit/branch/stage/push);
  ⛔ **NEVER open ANY cache read/write — `file:…?immutable=1` or `?mode=ro` on every single access**;
  ⛔ zero-comment default; no inline FQNs; offsets ONLY in `Offsets.kt`; no fake JavaExec tests;
  Zone/MapSquare naming; packets by name not opcode.
- [[re-discipline]] — correctness over volume: HYPOTHESIS by default, three-evidence rule before any
  rename, persist what you confirm, never trust stripped-Ghidra names or the outdated reference binary,
  ⛔ a name WE assigned is not evidence when a later pass reads it back (naming loops),
  ⛔ legacy `~/projectx` servers have zero authority, captures outrank decompilation, no defensive
  pointer validation.
- [[protocol-invariants]] — ⛔ C2S opcodes are always one byte, 2-byte is S2C only; ⛔ RS3 maps have no
  XTEA; opcode+size from the deterministic dump only; official prot names; one codec in `:core`.

## Reverse engineering
- [[re-workflow-and-resources]] — gamevals → our own dumps → Ghidra lookup order (third-party cs2-dumps removed); `docs/` is the RE↔impl
  contract; vendored Ghidra MCP (headless, ⛔ single-process lock); techniques that actually land IDs.
- [[cross-version-migration]] — ⛔ MAJOR bump re-scrambles every packet layout even at unchanged size;
  ⛔ NEVER carry an unverified absolute — a broken anchor means it MOVED, emit UNRESOLVED not the old value;
  ⛔ `.data`/`.bss` CAN move on a sub-revision (949-5 shifted Linux +0x1000); narrow auto-updater scope;
  data-label porting; MCP port-swap trap; manager-pointer anchors.
- [[client-internals-re]] — picking (two paths, ⛔ {X,Z,Y} axis order); highlight + the Loc-highlight
  dead end; terrain height model (additive raised-terrain grid); ⛔ the click ring buffer is the game's
  input dispatch queue, not a packet staging area; ⛔ the mouse pipeline DOES send movement history, and
  Windows has a whole second sender with per-sample source bytes (the old "click stream only" note was wrong);
  ⛔ NO input ring is a private server-bound channel — movement, click and key rings are all dual-consumed
  locally; keyboard is press-only on the wire; ⛔ the Linux pump/encoder are inlined with no callable boundary.
- [[instance-collision]] — ⛔ client has NO collision grid; instance walls are static source-cache locs
  (live ones are decorative); dirty-zone re-clip; water is a render flag.

## Engine
- [[engine-architecture]] — injection/hooks/FFI/overlay; `:core` is the single source for net, cache and
  spatial; ⛔ live cache opens READ-ONLY; lazy per-id cache accessors; JDBC driver registration in the
  child classloader; Euclidean vs Chebyshev distance.
- [[engine-native-safety-and-crashes]] — ⛔ `invokeExact` in a value-producing position throws
  WrongMethodTypeException, which escapes the upcall and SIGABRTs the client (read the engine log, not the
  crash file); ⛔ build the UI on the game thread, GL only on the render
  thread; EGL-teardown cores MASK the real crash; wrong-offset garbage reads; ⛔ never `!!` a texture in
  a top-level object; telemetry suppression re-ports each build.
- [[engine-build-inject-workflow]] — ⛔ never rebuild the shadowJar while injected (use `compileKotlin`;
  uninject → rebuild → reinject); hot-reload classloader model and its teardown contracts; inject
  privilege requirements.
- [[engine-synthetic-input]] — ⛔ the wire path (server-only, zero local effect) and the action path (real
  input) are structurally separate and must never merge; ⛔ a cursor trail can NEVER go through the click
  sender (its entries are button presses — that is click spam), only through the movement-history packet;
  ⛔ emit the platform's whole packet set or disable the path; one input source per cycle; ring writes only on
  the game thread; ⛔ synthetic clicks don't run client `onop` scripts (drive renders via CS2); interaction
  flag bytes read live state a synthetic fire doesn't set; packet bytes must come from Jagex's own writers.

## Scripting
- [[no-interaction-spam]] — ⛔ a stateLoop that clicks then returns is re-entered every 40ms tick = ~25
  clicks/sec at one object. Enforce a minimum interval at the call site, on every early-return path. Ban risk.
- [[script-authoring-principles]] — ⛔ all script authoring goes through the `script-writer` agent;
  react to outcomes, never blind-sleep; gaussian delays; register content, don't grow a dispatch chain;
  expose debug primitives not predicates; don't stop a bot for death while testing.
- [[script-modules-official-community]] — scripts live OUTSIDE this repo (public official-scripts /
  community-scripts repos, built against script-api); engine script-facing API must be
  public, not `internal`.
