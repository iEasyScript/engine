# The opcode table — installing, solving, and carrying it to a new build

Everything the toolchain does rests on knowing, for each opcode, how wide its operand is. Get that
wrong and the instruction stream mis-slices from the first bad opcode onward, silently. The table
lives at `data/cs2/opcodes-<index12Crc>.json` (gitignored), **keyed by the CRC of the index-12
reference table**, so a solve is bound to the exact cache it was solved against. Every command
prints which table it loaded and where it came from before doing anything else — read that line.

## Two independent sources, and why you want both

| Source | Command | Covers | Authority |
|---|---|---|---|
| The client's own dispatch table | `cs2 import <csv>` | Every opcode, including ones no script uses | Authoritative — it is the client's own data |
| The corpus itself | `cs2 calibrate` | Only opcodes some script actually exercises | Derived, but needs no binary |

Both were run and they agreed on every opcode they both covered. Running only one is a mistake: the
binary export cannot tell you the corpus is being parsed correctly, and the corpus solve cannot see
an opcode nothing calls.

If a dispatch-table export is already installed, `calibrate` **does not overwrite it**. It
cross-checks against it and reports agreements, disagreements and what the table calls the opcodes
the corpus left ambiguous. A disagreement means one of the two sources is wrong — most often that
the build changed the width rule — and is never something to paper over.

The dispatch-table CSV is produced by reverse-engineering the client (`ghidra-reverse-engineer`
territory) and lands in `data/cs2/dispatch-table.csv`. Its columns carry per-build handler addresses,
which is precisely why that directory is gitignored and why **no value out of it is ever quoted in a
doc, a comment or a memory file**.

## The layers on top of widths

Widths make the stream parseable. These make it readable, and each is a separate, re-runnable
install over the same table:

| Command | Adds |
|---|---|
| `cs2 names [csv]` | The recovered opcode names |
| `cs2 behaviour [csv]` | Names plus argument types read out of the handlers, with evidence and confidence |
| `cs2 reference [csv]` | Jagex's own handler names, matched from another build |
| `cs2 variants [csv]` | Settles opcodes the handler read left with several candidates |
| `cs2 effects [csv]` | Stack effects read from the handlers |
| `cs2 stacks` | Solves stack effects from the corpus instead, when no handler read is available |

Each defaults to a CSV under `data/cs2/`. Output full of bare numeric operators where names are
expected means these layers were never installed on the current table.

Supporting queries: `cs2 vartypes` (script variable types and what they index), `cs2 gamevals` (how
often each operand renders as a gameval name), `cs2 types [value]` (what the corpus typed, or every
place one number has been seen).

## Carrying the table to a new client build

A new build renumbers the opcodes wholesale, so **every table is invalid until re-derived**. The
derivation reads both builds' corpora — the same scripts, differently numbered — and recovers the
permutation. Four commands, three of which exist so the answer is known before update day:

```bash
# 1. Anchor: write the current build's table into the shared knowledge hub
./gradlew -q :tools:cs2 -Pargs="export-table"          # -> re-resources/cs2/opcodes-<build>.json

# 2. How much could the corpus rebuild alone, with nothing installed?
./gradlew -q :tools:cs2 -Pargs="coldstart"

# 3. Rehearse the whole derivation against a permutation generated here,
#    with --churn simulating the scripts a real update also edits
./gradlew -q :tools:cs2 -Pargs="rehearse --churn 0.01 --seed 20260823"

# 4. Update day: derive the new numbering from the old
./gradlew -q :tools:cs2 -Pargs="unscramble --to <new cache dir> --to-build <id> \
    --dispatch data/cs2/dispatch-table.csv"
```

`--cache` still points at the **old** build's cache during `unscramble`; both disassemblies are
needed. `--from-table` reads a committed export instead of the installed table, and `--dispatch`
supplies the new build's binary widths so the derivation has a second opinion.

`rehearse` is the one worth running unprompted. It permutes the current table itself, so it knows
the truth, and reports how many opcodes were recovered correctly, how many wrongly, and how many not
at all — plus, for the ones it missed, how many scripts witness them. Churn matters: pairs that
changed between builds have to be discarded rather than allowed to vote, and an opcode surviving
only in those has to be recovered some other way.

Build ids come from `client-plugin-engine/src/main/resources/offsets/index.json`; pass one
explicitly when that is not in place.

⛔ On a real update this is the `rs3-update-migrator` agent's job. Do not improvise it mid-task, and
never carry forward an unverified value — an opcode the derivation could not confirm stays
unresolved rather than inheriting the old number.
