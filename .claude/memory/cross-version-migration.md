# Migrating to a new client build

Canonical playbook: `re-resources/CROSS_VERSION_MIGRATION.md` + `docs/re-methodology/UPDATING.md` +
`AUTO_UPDATER.md`. Owned end-to-end by the `rs3-update-migrator` agent. These are the points that keep
having to be re-explained.

## ⛔ Classify the bump first

- **Sub-revision** (948-2 → 948-5): `.text` shifts in uniform blocks — predict
  `new = below_FOUND_new + (old − below_FOUND_old)`, usually exact. Struct field offsets are stable
  (relative, not absolute). Packets are unchanged.
  **⛔ `.data`/`.bss` CAN still move on a sub-revision** — at 949-4 → 949-5 Linux `.data`/`.bss` shifted
  `+0x1000` while Windows `.rdata` did not. The old "globals keep absolute addresses on a minor" rule is
  FALSE and cost a silent breakage: every carried absolute in that region pointed at dead memory, and no
  tool reported it. Re-derive every absolute per build, per platform; never assume a section stayed put.
- **MAJOR bump** (948 → 949): **every packet buffer FORMAT re-scrambles, even at unchanged size.** A
  matching size proves only that the packet still carries the same ids — never the same layout. RE every
  buffer op from the new build's handler and verify against a live capture. **Never** copy a body from
  the previous revision, and keep per-revision codec packages rather than mutating shared enums.

## Auto-updater scope is NARROW

`update_offsets.py` + `RS3*.java` only: sig-match and rename FOUND functions in the new DB, update
`DoActionOpcode.kt` (the only auto-updated Kotlin file), and import structs/enums 1:1. It does **not**
touch `Offsets.kt`, does not validate struct offsets, and **loses prototypes and decompiler comments on
rename** (renamed functions revert to `param_1`/`undefined`). Results land in
`re-resources/sigs-results/results_<ver>.ndjson`, line-for-line parallel with the previous version's
file — join by line index for the old→new address map.

**Manual every time:** `Offsets.kt` (`OGlobal` + `OFunctions`), resolving AMBIGUOUS/MISSING by sig-scan,
re-porting prototypes and comments, validating imported structs against the new binary — and **porting
named DATA LABELS**, which both the updater and the datatype importer skip and everyone forgets.

## Gotchas that cost real time

- **Data-label porting:** enumerate with `list_namespace_contents` per namespace and re-apply with
  `rename_data(addr, "ns::name", binary_name=new)` at the same address. Do NOT use `list_data_items` —
  it strips the namespace and you end up clobbering namespaced symbols with bare Global names.
  `.rodata` string constants can shift even when `.data`/`.bss` doesn't: if a same-address rename is a
  silent no-op, relocate via the identical referencing instruction.
- **⚠️ MCP port swap:** the two `rs2client` instances share a Binary name and can swap ports mid-session,
  silently reversing `binary_name` routing so "write to new" lands in old. Before any write wave, run
  `discover_ghidra_instances` and verify with a known-address read. Data-label renames survive a
  mis-route; function renames corrupt.
- **Sig-scan disambiguation:** AMBIGUOUS means the updater over-wildcarded near-identical siblings.
  Build a pattern that keeps the byte which *differs* between the twins. CS2 `jag::opcode::*` handlers
  embed a self-naming `CreateOpcodeError("<name>")` string. Byte-identical twins differing only in a
  call target are separated by **caller set** (`get_xrefs_to`). `search_memory_pattern` matches
  RIP-relative operands reliably but not embedded imm64 constants.
- **⛔ Anchor recipes: bound EVERY capture with `disp_min`/`disp_max`.** The recipe matcher ignores
  `mem.index`, `mem.scale` and a top-level `imm`. A step relying on any of them does not fail — it
  matches the first instruction of that mnemonic and the anchor emits a plausible wrong number. On 949-5
  that silently produced `ONPC.CURRENT_HP`=0x10, `OHintTrail.POINT_FINE_HEIGHT`=0x30 (true 0xc, and a
  NEGATIVE POINT_FINE_X on the PE) and `OHintTrailList.SLOT_ARRAY`=0x8 (true 0x10). Every anchor written
  with `base` + a displacement window resolved exactly. A window that excludes the wrong answer converts
  a lie into an UNRESOLVED, which is the only acceptable failure. Run `run_updater.py export` after
  editing the registry — its per-anchor self-resolve line IS the test, and `validated:false` means it has
  never been run.
- **⛔ A `.msvc` twin is not a safety net.** Both recipes execute against whichever binary is loaded; the
  later one merely overwrites. If the base recipe can also match on the PE it will emit garbage first, so
  give each variant a window the other host's encoding cannot satisfy rather than relying on array order.
- **Manager-pointer anchors:** OClient manager pointers aren't findable from a single anchor function,
  but every callsite of a manager's C++ method loads `MOV/LEA RDI,[client+OFF]` first — take the
  most-common offset per namespace as consensus. **Filter to the OClient offset band**: the same methods
  are also invoked on nested objects with small displacements, which will otherwise out-vote the real
  field. Fields accessed by direct read rather than method call need bespoke anchors.
- **⛔ Both Ghidra DBs must use the SAME canonical function names.** The doAction and OFunctions
  resolvers fall back to name lookup, so a PE DB that calls a handler `jag::MiniMenu::DoOpObject1`
  where the ELF calls it `jag::game::DoOpLoc1` resolves nothing and quietly *carries the previous
  build's addresses forward* instead — the exact failure the directive below forbids. At 949-5 that was
  62 Windows doActions dropped and 37 carried unverified; renaming the PE handlers to the ELF's
  symbol-derived names took it to 0 and 0. The ELF names come from the symbolised reference build and
  are authoritative; normalise the PE onto them, never the reverse.
- **Never hardcode an address anywhere**, including commented-out code — always `@Hook(OFunctions.<NAME>)`.

## ⛔ NEVER carry an unverified value forward (user directive)

An offset is emitted **only** if it was re-derived from the new binary this build. If its anchor fails to
resolve, emit it **absent/UNRESOLVED** — never fall back to the previous build's value.

**A broken anchor is evidence the target MOVED, not noise.** It is the worst possible moment to reuse the
old value: the tooling substitutes its least trustworthy input precisely when the value most likely changed,
and formats it identically to a verified one. Absent fails loudly at startup; present-but-wrong fails
silently at runtime. The engine is built for the former — `@Hook` is name-keyed so an absent entry leaves
the hook uninstalled rather than hooking a bogus address. It has no defense against the latter.

Applies to **absolutes** (function RVAs, data addresses, doAction RVAs). Low-address **struct field
offsets** are relative and stable across a sub-revision, so carrying those is fine — but label them carried
so the distinction stays visible. Prefer 20 honest UNRESOLVED entries over 20 confidently wrong addresses.

[[re-discipline]] [[protocol-invariants]] [[re-workflow-and-resources]]

## ⛔ A ported table is not parity: windows and the end-to-end gate (950-1)
- Every offset was right on 950-1 and scripts still did nothing: a wrapper read the interface manager
  through a hand-sized window and the interface-list pointer had moved one slot past it. Reads threw,
  the script loop swallowed it, the bot idled. **Wrappers widen the segment they are handed to their own `OffsetObject.extent` (table-derived) and
  `WindowExtentTest` bans literals. Never size an object window by hand, in a wrapper or at a call site.**
- The updater's `LAYOUT GREW` report lists objects whose last field moved outward: check each wrapper
  that reads inline structures past the last declared field.
- **Parity is declared only after one script runs end to end** (interfaces + scenery + NPC) on each
  shipped platform, via the in-process MCP, with `read_logs errors_only` clean. Nothing else exercises
  the DoAction table, the interface path and the scene scan together.
