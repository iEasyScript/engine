# Hard rules — git, comments, code hygiene, build

## ⛔ Git (non-negotiable, applies to every delegated agent)

- **NEVER add Claude/AI attribution to a commit.** No `Co-Authored-By: Claude`, no `Claude-Session:`
  trailer, no `🤖 Generated with…`, no session URL. This overrides any harness default template.
  (Violated once: 15 commits had to be rewritten out of history.)
- **The user handles ALL git.** Do not commit, branch, stage, or push. Work directly in the `dev`
  working tree and leave changes uncommitted. No worktree isolation for file-mutating agents.

## ⛔ Comments — default ZERO

Code must self-document through names and small functions. A comment is justified only for a
genuinely non-obvious *why* (a constraint, an invariant, a surprising behaviour), and then it is one
terse line. **Banned inline:** byte/field tables, disassembly addresses, struct offsets,
"binary-confirmed"/"asm-verified" narration, ticket/date/section references, and step-by-step
narration of what the code does — that is RE documentation and lives in `re-resources/docs/` + the
Ghidra DB. Before writing a comment, try renaming a variable or extracting a function. When editing
any file, trim verbose comments you encounter.

## Code hygiene

- **No inline fully-qualified names, ever.** Import it (`import x as Y` on collision) and use the bare
  name — in bodies, signatures, types, lambdas and KDoc alike.
- **Offsets live in `Offsets.kt` and nowhere else.** No inlined revision constants, no per-subsystem
  offset/struct mirror objects, no duplicated constant tables. `Offsets.kt` is maintained by the
  auto-updater; a second copy silently rots.
- **Use the existing buffer library** (`Writer`/`Reader`/Jag extensions) — never hand-rolled inline
  byte arithmetic. Never write a second codec for a format that already has one.
- **No fake tests.** A Gradle `JavaExec` "verification main" with hand-rolled assertions is not a test.
  Write real tests in `src/test` with JUnit, or write none.
- **Naming:** spatial units are **Zone** (8×8) and **MapSquare** (64×64) — never Chunk/Region. Packets
  are referenced by **name**, never `op<n>`; a raw opcode only for a genuinely unidentified packet
  (`UNKNOWN_<n>`).
- **Jagex's names are the names.** Where Jagex's vocabulary is known (gameval table names, cs2 types,
  config keys) it is used everywhere — types, directories, fields, docs. A 2D image is a **graphic**
  (cs2 `graphic`, index 67 archive `graphic`, index 8), never a "sprite"; a **spotanim** is a spot
  animation and has no gameval table; an obj's 2D render fields are the `2d*` config keys. Community
  names (sprite, item, object for loc) are wrong here, not merely different.

## ⛔ Caches are READ-ONLY — never opened read/write, ever

**Every** cache open — CLI, JDBC, Python, GUI — must use a read-only URI. `sqlite3`'s default is
**read-write**: a bare `sqlite3 x.jcache` locks the file, writes the header, leaves `-wal`/`-shm`
orphans, and can force a multi-GB re-download. This has bitten repeatedly; it is a defect, not a slip.

```bash
sqlite3 "file:$f?immutable=1" "PRAGMA integrity_check;"   # default — takes NO locks, no sidecars
sqlite3 "file:$f?mode=ro"     "SELECT …;"                 # only if the DB may be concurrently written
```

- Covers **all** caches: `~/.local/share/project-x-launcher/{custom/,}Jagex/RuneScape/`, `./data/cache/`,
  `data/client/**`, any `*.jcache` anywhere.
- Banned: bare paths, `.backup`/`.recover`/`VACUUM`/`REINDEX`, `PRAGMA journal_mode=` writes,
  `jdbc:sqlite:$path` without `file:`+`?mode=ro` (use `IndexFile(readOnly = true)`),
  `sqlite3.connect(path)` without `uri=True`, any GUI browser without a read-only flag.
- Need to write? **Copy the file first** and work on the copy — never in place.
- Orphaned sidecars (owning process stopped, `-wal` at 0 bytes) are safe to delete; re-verify with
  `integrity_check` + `journal_mode` over `immutable=1` afterwards. A header reading `wal` where the
  client expects `delete` means a read-write open already corrupted it.

**Enforced in code:** `LiveCacheGuard` (`:core`) refuses every read-write open under a client-owned
root (launcher data dirs, `$HOME/Jagex`, `$PROGRAMDATA/Jagex`, `.projectx`) from `IndexFile` and
`SQLiteCache.load` before the connection exists. It has **no opt-out and must never get one** — a
`WriteRefused` means the caller is wrong: pass `readOnly = true` or copy the cache out. Tests resolve
caches only via `CacheFixture`, which picks the mode from the guard; never hand-roll a cache-dir probe
in a test (that is how `./gradlew build` came to corrupt the live cache on every run).

Full rule with rationale: project `CLAUDE.md` → "⛔ CACHES — READ-ONLY, ALWAYS". Engine-side contract:
[[engine-architecture]].

## Build / artifacts

- After any change, **rebuild the real deployable artifact** (shadowJar / cargo), not just the classes —
  but see the injected-client exception in [[engine-build-inject-workflow]].

[[memory-policy]] [[re-discipline]] [[engine-build-inject-workflow]] [[engine-architecture]]

## ⛔ NEVER add "safe" / guarded memory reading (explicit user directive, 2026-09-08)
- **Never** add guarded copies, page probes, chunked "safe snapshots", fault-catching readers, or any
  other defensive memory-access layer, in Kotlin or in the bootstrap. The existing memory reading
  system is the only one; use it as it is. Two such additions were shipped and removed the same day;
  one broke injection on windows.
- **Why:** an invalid memory access is never something to survive. It means a real engine defect: a
  structure sized or laid out wrong, a traversal that does not match the client's own code in Ghidra,
  or a thread race / read from the wrong thread. Every crash from an invalid access is fixed at that
  root: size structures from the offset table, walk them the way the client does, and fix the race.
