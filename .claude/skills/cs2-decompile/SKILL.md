---
name: cs2-decompile
description: Decompile, read, search, verify and recompile RS3 CS2 clientscripts (cache index 12) with the `:tools:cs2` toolchain. Use whenever the task is "decompile clientscript N", "what does this cs2 script do", "which script draws/handles X", "dump all the clientscripts", "why is this interface behaving like that", "the cs2 round-trip is failing", "recalibrate the opcode table", or porting the CS2 opcode table to a new client build.
---

# CS2 clientscript decompilation

The `:tools:cs2` toolchain turns cache index 12 into readable, **byte-exact round-tripping**
TypeScript. It is the only way to see what a client-side script actually does — a CS2 update is
otherwise completely opaque, because index 12 carries no meaningful version and its opcode ids are
per-build.

Mechanism and naming provenance: `docs/cache/clientscript-cs2.md`.
Command reference and requirements: `docs/cache/cs2-toolchain.md`.

## ⛔ Rules that bite here

- **The cache is opened read-only and must stay that way.** `MainKt` calls
  `SQLiteCache.load(path, readOnly = true)` and then `Cache.init(cache)` so the static accessors
  land on the same read-only handle. Never "just check" a `.jcache` with a bare `sqlite3` path —
  that opens read-write, takes a lock, leaves `-wal`/`-shm` sidecars and can force a multi-GB
  re-download. Read-only means `sqlite3 "file:/abs/path/x.jcache?immutable=1"`.
- **Because it is read-only, this toolchain is safe to run while the servers or the client are
  live.** No need to stop anything. That is a deliberate property — do not "improve" it.
- **⛔ Never write an opcode id, a handler address or an operand width into a doc, a comment or a
  memory file.** Those are per-build and belong in the Ghidra DB and in the generated tables under
  `data/cs2/` (gitignored). The docs describe mechanism only.
- **`data/cs2/` and `cs2-dump/` are gitignored generated artifacts.** Never commit them, never
  hand-edit the JSON table — regenerate it.
- **Leave everything uncommitted.** The user handles all git.

## Requirements

| Requirement | Detail |
|---|---|
| JDK 25 + the Gradle wrapper | Everything runs as `./gradlew :tools:cs2 -Pargs="…"` from the repo root |
| A cache at `./data/cache` | Override with `--cache <dir>`; the path must exist or the tool exits 1 |
| An installed opcode table | `data/cs2/opcodes-<index12Crc>.json`, keyed by the index-12 reference-table CRC |
| ~4 GB heap | `org.gradle.jvmargs=-Xmx4g` in `gradle.properties` already covers the whole-corpus commands |

The table is **keyed by the CRC of the cache it was solved against**, so a solve survives exactly as
long as that cache does. On startup the tool prints which table it loaded and where it came from; if
it prints `No solved opcode table for index-12 crc … - run 'cs2 calibrate' first`, everything above
it is worthless — fix that before reading any output. See `references/opcode-table.md`.

## Reading a script

```bash
./gradlew -q :tools:cs2 -Pargs="decompile 10000"     # structured TypeScript
./gradlew -q :tools:cs2 -Pargs="info 10000"          # header: name, arg/local counts
./gradlew -q :tools:cs2 -Pargs="dump 10000"          # raw instruction listing
./gradlew -q :tools:cs2 -Pargs="trace 10000"         # simulate, printing the stacks per instruction
./gradlew -q :tools:cs2 -Pargs="run 10000 1 2"       # execute against a no-op host, log the calls
```

`decompile` renders gameval names and unpacks composite operands (a component id becomes
interface+component, a packed coordinate becomes a constructor). Both renderings are **bidirectional**,
which is why the output still compiles.

### Finding the script you want

There are ~21k scripts and the id alone tells you nothing. Dump once, then grep:

```bash
./gradlew -q :tools:cs2 -Pargs="decompile-all cs2-dump"   # ~21k files, seconds, gitignored
rg -l 'rs3tli_button_layer_type' cs2-dump/                # by gameval name
rg -n 'if_setonclick' cs2-dump/clientscript-10000.ts      # by opcode name
```

Decode a bare numeric id **before** guessing what it means — `./re-resources/gamevals/gameval.py npc 7987`,
`gameval.py obj -n coins`, `gameval.py -s magic_logs` to search every type. Script names that Jagex
shipped are recovered from the name hashes in the cache and appear in the header comment.

The dump writes `cs2.d.ts`, `vars.d.ts` and `tsconfig.json` alongside the sources, so an editor gives
you real completion and go-to-definition over the whole corpus.

## Verifying, in layers

A failure only localises if you check the layers in order — each one needs less than the next.

```bash
./gradlew -q :tools:cs2 -Pargs="verify"        # codec only: decode + re-encode every script
./gradlew -q :tools:cs2 -Pargs="structure"     # how much of the corpus reads as structured control flow
./gradlew -q :tools:cs2 -Pargs="roundtrip"     # end-to-end: decompile -> recompile -> compare bytes
./gradlew -q :tools:cs2 -Pargs="roots"         # split validation failures into roots and cascade
```

`verify` needs only operand widths, so if it fails the opcode table is wrong and nothing above it can
be trusted. **The passing state is byte-identical over every script, at both layers** — as of the
current build that is 21097/21097 on both, with a handful of scripts reaching it through the literal
block-by-block fallback rather than structured control flow. That fallback exists on purpose:
readability yields to fidelity. Anything less than PASS is a bug, not a formatting choice.

`--shape-heuristic` keeps readings that rest on a value's shape alone, emitted marked
`/* unverified */`. Left off (the default), such a value stays the integer it is, so **nothing
unverified reaches the output**. Turn it on to explore, never to produce something you will cite.

## Editing a script

`export` → edit → `compile` → `hotswap`/`watch`. The gate is that a script which does not round-trip
untouched will not round-trip edited either. See `references/editing-and-hot-reload.md`.

## Update day

A new client build renumbers the opcodes, so every table is invalid until it is re-derived. That is
`cs2 unscramble`, rehearsed in advance by `cs2 rehearse` and `cs2 coldstart`. See
`references/opcode-table.md` — and note this is `rs3-update-migrator`'s territory, not something to
improvise mid-task.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `No such cache directory` | Wrong cwd or no cache — the task's `workingDir` is the repo root; pass `--cache <dir>` |
| `No solved opcode table for index-12 crc …` | The cache changed under the table. `cs2 import` the dispatch-table export, or `cs2 calibrate` from the corpus |
| Output full of bare numbers where names are expected | The table loaded without its name/behaviour layers — re-run `cs2 names` / `cs2 behaviour` / `cs2 reference` |
| `verify` fails | Operand widths are wrong. Fix the table before looking at anything else |
| `roundtrip` fails but `verify` passes | A decompiler or code-generator bug. `cs2 diff <id>` shows the two listings side by side; `cs2 diff <id> faithful` shows the literal fallback |
| A script decompiles but `structure` reports it falling back | Expected for a few scripts; the fallback still round-trips. Only a fidelity failure is a defect |
