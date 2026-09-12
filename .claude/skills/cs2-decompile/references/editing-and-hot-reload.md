# Editing a clientscript and reloading it

The decompiler's output is not a listing — it recompiles. That is what makes editing possible, and
the fidelity gate is what makes it trustworthy: **a script that does not round-trip untouched will
not round-trip edited either**, so check that first and you will never wonder why a change
misbehaves.

Nothing here writes to the cache. The cache is opened read-only, and a reload installs the new
bytecode as an in-memory override on the analyzer, which the virtual machine, the code generator and
the structurer all resolve through. It lives as long as the process does.

## The source folder

`cs2 decompile-all <dir>` and `cs2 export <dir> [id]` write the same shape:

```
<dir>/clientscript-<id>.ts     one script each
<dir>/cs2.d.ts                 opcode signatures
<dir>/vars.d.ts                renamed variables bound to their ids
<dir>/tsconfig.json            so an editor resolves the above
```

`cs2 declarations <dir>` writes just `cs2.d.ts` + `tsconfig.json`. The default folder for the
commands that take one is `cs2-dump`.

Two properties of that layout drive everything:

- **The `// clientscript <id>` header is the script's identity** — not the file name, not the
  function name. Rename the function freely, copy the file anywhere, and it still compiles to the
  right id; lose the header and it no longer does.
- **Renamed identifiers resolve only through the folder's declarations.** The symbol table is read
  from `vars.d.ts` and every script header in the folder, so it is folder-scoped state that must be
  re-read whenever those change. A rename that works in one folder is meaningless in another.

## The loop

```bash
./gradlew -q :tools:cs2 -Pargs="export cs2-dump 10000"        # decompile one, verify it round-trips
$EDITOR cs2-dump/clientscript-10000.ts
./gradlew -q :tools:cs2 -Pargs="compile cs2-dump 10000"       # byte-compare against the cache
./gradlew -q :tools:cs2 -Pargs="hotswap cs2-dump 10000 1 2"   # run cached, reload, run from source
./gradlew -q :tools:cs2 -Pargs="watch cs2-dump"               # reload on every save
```

`export` with an id prints the verification summary for that script straight away. `export` without
one writes only the declarations.

`compile` reports the instruction count, the byte count, and one of three verdicts — identical to
the cached script, differs from it, or nothing in the cache to compare against. Before an edit,
`identical` is the state you want; after one, `DIFFERS` is.

`hotswap` is the whole reload path in one command: it runs the script as the cache has it, reloads
from source, and runs it again, printing both return values and both sequences of host calls. That
is what proves an edit actually reaches the running machine rather than merely compiling. It fires
through the same dispatcher an editor uses, deliberately built *before* the reload.

`watch` runs that watcher headlessly. With a duration it runs unattended; without one it takes
commands on stdin — `r <id>` reload, `v <id>` verify, `l` log, `q` quit. A reload that works here
works in the editor, because it is the same pipeline.

## Before trusting a whole folder

```bash
./gradlew -q :tools:cs2 -Pargs="sources cs2-dump"       # every file; add a limit to sample
```

This compiles every source file in the folder and reports how many still encode to exactly the
cached bytes, grouping the failures by message and saying how many have a line number to jump to.
It is the number that decides how much the reload path can be trusted. Run it on a fresh dump
before editing anything, so a later failure is unambiguously yours.

## What stays stale after a reload

Host-side state a previous run created. Hooks bound with the `IF_SETON*` family stay bound to
whatever they were bound to — those are script *ids* resolved per dispatch, so they do point at the
new bytecode, but if the edit changed *which* hooks get bound, the old bindings survive until the
interface is reset.
