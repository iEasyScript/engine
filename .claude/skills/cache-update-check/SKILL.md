---
name: cache-update-check
description: Check the live RS3 JS5 server for cache/content updates, snapshot and diff the cache to find out exactly what a game update changed (index -> archive -> field), detect gameval component drift caused by interface changes, and track decoder coverage. Use whenever the task is "is our cache up to date", "did the game update", "what did this update change", "update/download the cache", "why is this interface component wrong", "check for content updates", or before and after any cache download.
---

# Cache update check

Answers **"what did this game update actually do?"** — not "an index changed", but which archives,
which definitions, which fields, and which of our artifacts it invalidated.

Background and mechanism: `re-resources/docs/cache/cache-update-flow.md`.
Decoder status: `re-resources/docs/cache/decode-coverage.md`.
For the **beta** gameval (index 67) refresh specifically, use the `gameval-beta-check` skill —
this skill is about the **live** content cache.

## ⛔ Rules that bite here

- **NEVER open a `.jcache` read-write.** Every read in this flow is read-only. In the shell that
  means `sqlite3 "file:/abs/path/x.jcache?immutable=1"` (no locks, no sidecars) or `?mode=ro` when
  the file may be concurrently written. A bare path opens read-write and can force a multi-GB
  re-download. In code go through `SQLiteCache.load(path, readOnly = true)`.
- **Stop the lobby and world servers before downloading.** They hold `data/cache` open; the
  downloader opens it read-write. Check with `pgrep -af 'projectx|MainKt'`.
- **Snapshot before you download.** The download overwrites in place. Miss this and the update can
  never be explained.
- **Leave everything uncommitted** — the user handles all git. `re-resources` is a submodule.

## 1. Is anything stale? (one fetch, writes nothing)

The JS5 token regenerates per fetch — always fetch it in the same command that uses it. Live and
beta tokens are **not** interchangeable.

```bash
curl -sL 'https://www.runescape.com/k=5/l=0/jav_config.ws?binaryType=4' \
  | grep -E '^(server_version=|launcher_sub_version=|param=(29|37|49)=)'
```

`server_version` → `--major`, `launcher_sub_version` → `--minor`, param 29 → token,
params 37/49 → host. (Beta is a different URL: `jav_config_beta.ws`.)

```bash
TOKEN=$(curl -sL 'https://www.runescape.com/k=5/l=0/jav_config.ws?binaryType=4' | sed -n 's/^param=29=//p') && \
./gradlew :tools:betaScanner -Pargs="--host content.runescape.com --major <maj> --minor <min> --token $TOKEN --live ./data/cache --scan"
```

`--live ./data/cache` diffs against the cache **we serve** rather than a client-owned one. Read the
`live-status` column: `in-sync`, `CRC-DELTA`, `SIZE-DELTA`, `version-only`, `MISSING/EMPTY`.

Handshake reply codes: `0` accepted · `6` wrong revision (re-read the config, probe `--minor` up
from 0) · `48` revision fine, token bad or from the wrong environment (re-fetch param 29).

**If the revision moved, this is a client update too** — that is `rs3-update-migrator`'s job, not
this skill's. A content-only update leaves the revision unchanged.

## 1b. What exactly would change? (the dry run — still downloads no archive data)

The index-level scan says *which* indices are stale. To see **which archives** would change before
committing to a download, snapshot the remote host's reference tables and diff them against the
cache you have. Reference tables are small, so this describes the entire remote cache for a few
hundred KB:

```bash
TOKEN=$(curl -sL 'https://www.runescape.com/k=5/l=0/jav_config.ws?binaryType=4' | sed -n 's/^param=29=//p') && \
./gradlew :tools:cacheSnapshot -Pargs="--host content.runescape.com --major <maj> --minor <min> --token $TOKEN --label live_remote_<date>"
```

Then diff it against your current cache's snapshot (take that first — see §2):

```bash
./gradlew :tools:cacheDiff -Pargs="--from <current-label> --to live_remote_<date> --out pending-update.md"
```

This answers "what is this update, and do I care" **before** stopping the server. In particular it
tells you up front whether any interface archive changed, which is the one outcome that forces a
gameval re-alignment.

⚠ **Do not size a download from index sizes.** The downloader is incremental at archive
granularity, so it fetches only changed archives. An index containing one changed archive still
reports its full size in a scan; the archive counts in this diff are the real workload.

## 2. Snapshot the current cache — BEFORE anything downloads

```bash
./gradlew :tools:cacheSnapshot -Pargs="--cache ./data/cache --label <rev>_<date-of-current-content>"
```

Reads read-only; safe while the server runs. Captures every populated index at **archive
granularity**, including indices with no decoder. Whole cache ≈ 40 MB, output is gitignored.

Confirm the run ends with `reference tables fully parsed (0 trailing bytes on every index)`.
Anything else means a reference table was not fully understood — stop and investigate.

## 3. Download

Stop the servers first. Then the standard downloader (incremental — it skips archives whose crc
already matches):

```bash
./gradlew :tools:run -PmainClass=org.projectx.tools.cachedownloader.MainKt \
  --args="content.runescape.com 43594 <maj> <min> 8 ./data/cache"
```

Confirm it reports `Failed: 0`. Restart the servers afterwards.

## 4. Snapshot again, and diff

```bash
./gradlew :tools:cacheSnapshot -Pargs="--cache ./data/cache --label <rev>_<today>"
./gradlew :tools:cacheDiff -Pargs="--from <old-label> --to <new-label> --out cache-update-report.md"
```

Useful flags: `--only <indices>` (comma list and `a-b` ranges), `--detail <n>` for how many ids to
sample per bucket.

Read `references/interpreting-diffs.md` for what each bucket means and the common false positives.

## 5. Act on what the diff says

| the diff shows | do this |
|---|---|
| **interface index archives changed** | **re-run the gameval alignment** — see below. Mandatory. |
| config archives changed | run the decode report; new unknown opcodes are new fields |
| clientscript archives changed | CS2 behaviour changed; refresh the dumps (below) |
| **any config archive changed** | **re-run `./gradlew :tools:cacheUnpack`** to refresh our own decoded type data. The decoder integration tests are intrinsic — full record consumption, cross-decoder agreement, and gameval coverage — so there is no fixture to refresh and nothing drifts on a content update. Also confirm no decoder's record count dropped to zero: a decoder aimed at an archive the index no longer has decodes nothing and still reports success. |
| asset archives changed (models/textures/graphics) | informational unless something renders wrong |
| an index's archive count grew | new content; check whether it needs gameval names |

### If any interface archive changed

```bash
./gradlew :tools:gamevalExport -Pargs="--cache ./data/betacache --out re-resources/gamevals \
  --revision <rev> --source content.beta.runescape.com --align ./data/cache"
```

Then read its summary: `aligned N interface(s)` are corrected; `NOT aligned` interfaces are ones
beta rebuilt outright, whose component ids stay beta-numbered and are **wrong for us**.

Until this is done `GamevalComponentAlignmentTest` fails (the stamp in `component.json` no longer
matches the served interface index) and the lobby/world log a `Gameval drift` error at startup.

⛔ **Do not conclude "no drift" from unchanged component counts.** An interface can keep its count
and still reorder slots — this has been observed on interfaces carrying hundreds of named
components. Every interface whose **crc** changed needs the alignment run over it.

Details and the by-hand verification method: `references/gameval-drift.md`.

## 6. Decode health

```bash
./gradlew :tools:decodeCheck -Pargs="--cache ./data/cache"
```

A clean run prints only the summary line. Compare the per-decoder trailing/unknown counts against
the previous run — **unchanged counts mean the update added no new fields.** New or grown counts
are the signal to act on.

⛔ **A decoder with no explicit unknown-opcode arm cannot be trusted here.** An unknown opcode
consumes nothing, so such a decoder falls through, misparses the record, and still often ends on a
byte boundary — reporting zero trailing and zero failures while being entirely wrong. Treat a clean
result from one of those as unverified, not as a pass.

New **trailing bytes** or **unknown opcodes** after an update mean a definition type gained a field
and is now being silently mis-decoded from that field onward. That is a decoder fix
(cache-library-engineer), and the field layout must come from the client binary
(ghidra-reverse-engineer) — never from guesswork.

Update `re-resources/docs/cache/decode-coverage.md` if coverage moved.

## 7. Verify

```bash
./gradlew :core:test --rerun-tasks
./re-resources/gamevals/gameval.py npc 0     # -> hans
git -C re-resources status --porcelain       # expect only intended changes, left uncommitted
```

⛔ **Use `--rerun-tasks`.** Gradle caches test results against source inputs, not cache-file inputs,
so after a cache download a plain `:core:test` reports `UP-TO-DATE` and re-prints the *previous*
run's green result. The integration tests read the cache directly — a stale pass here will hide a
real post-update regression.

`GamevalIndexDecoderTest` pins exact var/varbit counts and **will** fail after a gameval refresh —
that is expected data drift, not a regression; update the constants. `GamevalTest` pins a specific
component slot, so it fails loudly if alignment moved that interface — investigate rather than
just updating it.

## Interpreting a "nothing changed" result

Check you compared the right things. The index-level scan diffs against whatever `--live` points
at; if that is a client-owned cache rather than `./data/cache`, a green scan says nothing about
what we serve. And index 12 (clientscripts) carries **no meaningful version**, so only its crc
moves — a version-only comparison will always call it unchanged.
