---
name: gameval-beta-check
description: Check the RS3 beta JS5 server for updates to cache index 67 (gameval / RSCM id↔name tables), refresh re-resources/gamevals, and align its beta component ids onto the cache the server actually serves. Use whenever the task is "check the beta servers for gameval updates", "re-dump the gamevals", "did the gameval index change", "do the gamevals line up with our server", a component name resolves to the wrong slot, or a new/unknown gameval type needs identifying.
---

# Checking beta for gameval (index 67) updates

Index 67 is **beta-only** — the live cache ships it empty, so every gameval refresh comes from
`content.beta.runescape.com`. Format spec + archive→type map: `re-resources/docs/cache/gameval_index67.md`.

Everything below is read-only against the live cache; downloads land in the gitignored `./data/betacache`.

> **Live content updates are a different task.** This skill refreshes the **beta** gameval name
> tables. To find out what a **live** game update changed, or before/after downloading the live
> cache, use the `cache-update-check` skill instead. The two meet at one point: whenever the live
> cache's interface index changes, the alignment in §3 must be re-run even if index 67 itself
> did not move.

## 1. Beta jav_config — token, revision, host (one fetch, all three)

The JS5 token **regenerates per fetch** — never reuse a saved one, and always fetch it in the same
shell command that uses it.

```bash
curl -sL 'https://www.runescape.com/l=0/jav_config_beta.ws?binaryType=4' \
  | grep -E '^(server_version=|launcher_sub_version=|param=(29|37|49)=)'
```

- `server_version` → `--major`, `launcher_sub_version` → `--minor`
- `param=29` → the JS5 token; `param=37`/`49` → the JS5 host (`content.beta.runescape.com`)
- The **live** config is a different URL (`https://www.runescape.com/k=5/l=0/jav_config.ws?binaryType=4`)
  and its token is rejected by the beta host. Don't cross them.

Handshake response codes (byte 0 of the JS5 reply), useful when a scan fails:

| code | meaning |
|-----:|---------|
| 0 | accepted |
| 6 | wrong revision — re-read `server_version`/`launcher_sub_version`, probe `--minor` upward from 0 |
| 48 | **revision is correct**, token missing/expired/wrong-environment — re-fetch param 29 |

## 2. Scan — has index 67 changed? (writes nothing)

```bash
TOKEN=$(curl -sL 'https://www.runescape.com/l=0/jav_config_beta.ws?binaryType=4' | sed -n 's/^param=29=//p') && \
./gradlew :tools:betaScanner -Pargs="--host content.beta.runescape.com --major <maj> --minor <min> --token $TOKEN --scan"
```

Read the `67  gameval/RSCM` row: `crc`, `version` (a unix timestamp — that's when Jagex last rebuilt
it), `files` (archive count).

The scanner diffs against the **live** cache, which has index 67 empty, so its `MISSING/EMPTY`
verdict is meaningless here. To diff against what we already downloaded, compare the server `crc`
with the local ref-table CRC (`storeRefTable` writes exactly the master's container CRC, with
`VERSION` 0):

```bash
sqlite3 "file:$PWD/data/betacache/js5-67.jcache?immutable=1" "SELECT CRC FROM cache_index; SELECT count(*) FROM cache;"
```

⛔ `immutable=1` is mandatory on every cache read — see the caches rule in `CLAUDE.md`.
CRCs equal → nothing changed, stop here. Different → continue.

## 3. Download + export

```bash
TOKEN=$(curl -sL 'https://www.runescape.com/l=0/jav_config_beta.ws?binaryType=4' | sed -n 's/^param=29=//p') && \
./gradlew :tools:betaScanner -Pargs="--host content.beta.runescape.com --major <maj> --minor <min> --token $TOKEN --download 67,3"

./gradlew :tools:gamevalExport -Pargs="--cache ./data/betacache --out re-resources/gamevals \
  --revision <maj> --source content.beta.runescape.com --align ./data/cache"
```

To see the diff before overwriting the bundled dumps, export to a scratch dir first and compare
`entries` per type (added / removed / renamed), then re-export over `re-resources/gamevals`.

### ⛔ `--align` is mandatory — component ids are BETA ids

Index 67 is beta-only, so every `component.json` id is a **beta** slot. Beta and live do not ship the
same interfaces: one component inserted on beta shifts **every slot after it**, and the name then
addresses the wrong component of the cache we actually serve — silently, because the id still
resolves. That is why index **3** must be downloaded alongside 67 (`--download 67,3`): alignment
needs beta's interface layout, not just its names.

Re-run the alignment when **either** cache moves — a beta gameval refresh *or* a fresh live cache
download — because it is a diff between the two. Full mechanism, evidence and the 949-1 drift table:
§7b of `re-resources/docs/cache/gameval_index67.md`.

#### The alignment tool

`tools/.../gamevalexport/ComponentAlignment.kt`, invoked from `alignComponents` in the exporter's
`Main.kt` when `--align <serverCacheDir>` is passed. Both caches open **read-only**
(`SQLiteCache.load(path, readOnly = true)`); the server cache is normally live, so this is not
optional.

For each interface archive in index 3 it:

1. builds a per-component `(contentHash, shapeHash)` list from `cache.data(3, iface, file)`;
2. **skips** the interface when the two shape sequences are already identical — no insertion, so no
   slot can have shifted;
3. otherwise aligns the two sequences with **Needleman–Wunsch**, producing a monotonic
   beta→server slot map plus the gaps on either side;
4. **accepts** the remap only when shape agreement over the aligned pairs is `>= MIN_AGREEMENT`
   (0.9) *and* beats the agreement you would get by leaving the ids alone.

The exporter then re-keys `"iface:betaSlot"` → `"iface:serverSlot"` and **drops** beta-only
components (no counterpart in the served cache, so no valid id exists for them).

Two design points worth knowing before touching the constants:

- **Match on shape, not content.** Only ~16% of components are byte-identical across the two caches —
  beta edits graphics/text constantly — so content equality alone gives the DP almost no anchors. The
  shape key (`SHAPE_BYTES` = first 9 structural bytes, plus length) survives content edits. Scoring
  is graded: `IDENTICAL 6 > SAME_SHAPE 3 > DIFFERENT -1`.
- **`GAP` (-4) must be worse than a mismatch (-1).** With a cheap gap the DP buys its way out of any
  mismatch with an insert/delete pair and invents shifts — the first version of this scored
  mismatch -2 / gap -1 and hallucinated 5 insertions in `toplevel_v2` instead of the real 3.

Read the summary it prints on every run:

- **aligned** interfaces are corrected — the line reports how many names were remapped and how many
  actually moved;
- **NOT aligned** interfaces are ones beta rebuilt outright (below the threshold) — their ids stay
  beta-numbered and are **wrong for us**. The set grows and shrinks as live catches up with beta;
  against the 2026-08-10 live cache it is the whole Grand Exchange (`stockmarket` — 0% agreement, no
  recoverable mapping — plus `stockmarket_collectall` and `stock_favourites`), the two `marketplace`
  interfaces, `gwd2_rep`, and the four `league_*` panels. Check the current list in the exporter's
  own summary, not here.

If a *new* interface shows up as NOT aligned and the server needs it, do not lower the threshold —
that trades a loud gap for silent wrong ids. Check by hand first (below) and only then decide.

#### Verifying an alignment by hand

Compare component **lengths** rather than content; rare lengths make unmistakable anchors. Dump both
caches' per-component lengths for the interface side by side (`Cache.files` + `Cache.data` over both
`data/cache` and `data/betacache`, both opened read-only) and read off the insertion: a genuine shift
shows one unmatched length followed by a constant offset that holds unbroken to the end of the
interface, with the sequences agreeing exactly before it. That is what proves the remap independently
of the aligner. `toplevel_v2` is the same argument in bulk — near-perfect whole-sequence agreement
shifted, poor unshifted.

The end state to assert: for every aligned interface, **no named slot is at or beyond the served
cache's component count**, and the named count equals it wherever beta names every served slot. Named
< served is normal — those are components the server ships that beta has no name for. Named ≥ served
is a real defect: a name addressing a slot that does not exist.

Both halves are asserted automatically: the export stamps `component.json` with the served interface
index's CRC (`alignment.interfacesCrc`) and the unaligned interface ids, `GamevalComponentAlignmentTest`
in `:core` fails until the stamp matches the cache in `./data/cache`, and the lobby/world log a
`Gameval drift` error at startup against a cache that differs from the stamp.

## 4. Unknown archive → a NEW gameval type

`[WARNING] GamevalIndexDecoder.decode Unknown gameval index-67 archive N — naming it 'type_N'`
means Jagex added a content type. The archive id **is** the client's RSCM type ordinal; archives are
not name-hashed, so the type name has to be derived from the data:

1. Decode the archive and read a random sample of its names (format in §3 of the index-67 doc:
   `i32 version, i32 count`, offset table, CP1252 null-terminated blob; container is usually BZIP2
   with the `BZh1` magic stripped).
2. Name it from the evidence — the name conventions inside the table plus the CS2 type list in
   the CS2 opcode table we generate ourselves (`./gradlew :tools:cs2` — `Cs2OpcodeTable`, sourced
   from the client's own dispatch table), which carries the canonical lowercase type names.
3. Add `N to "<type>"` to `GamevalIndex.TYPE_BY_ARCHIVE`
   (`core/.../world/gregs/voidps/cache/gameval/GamevalIndex.kt`) — until it is mapped, the exporter
   silently skips the archive.
4. Re-run the export; update `re-resources/docs/cache/gameval_index67.md` (§2 archive count + id
   list, §6 table row + provenance footnote, the v1/v2 split, the "N output types from M archives"
   line).

## 5. Verify

```bash
./gradlew :core:test --tests "*GamevalIndexDecoderTest*"
./re-resources/gamevals/gameval.py npc 0        # -> hans (regression)
./re-resources/gamevals/gameval.py <type> <id>  # spot-check an id the diff added
git -C re-resources status --porcelain -- gamevals/
```

Then check the refresh did not break any name the server resolves. Names reach `Gameval` as enum
constructor args and constants, not inline at the call site, so grepping call sites finds almost
nothing — instead take every gameval-shaped string literal in `core/`, `world/`, `lobby/` and the
engine, resolve it against the old dump (`git -C re-resources show HEAD:gamevals/<t>.json`) and the
new one, and flag any that resolved before but not now. Zero regressions is the bar.

`GamevalIndexDecoderTest` pins exact `var_player` / `varbit_player` counts (and their combined
archive total) against the downloaded cache — a data refresh WILL fail it. Update those constants to
the freshly exported counts; that is expected drift, not a decode regression.

`re-resources` is a submodule: the refreshed dumps and doc land in the `reclass-data` repo.
**Leave everything uncommitted — the user handles all git.**
