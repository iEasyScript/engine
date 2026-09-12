# Gameval component drift

## The failure

Component names in `re-resources/gamevals/component.json` are keyed by **slot position within an
interface**. Insert a component and every slot after it shifts. The name then addresses a different
component — and because the id still exists and still resolves, **nothing errors**. Symptoms are
things rendering in the wrong place or controls doing nothing, with no trace back to the cause.

Compounding it: index 67 is beta-only, so every id we ship is a **beta** slot while the server
serves the **live** layout. Alignment reconciles the two.

## The two failure classes

| class | detectable how | fixed by |
|---|---|---|
| **shift** — a slot moved | shape alignment | `gamevalExport --align` |
| **rename / removal** — a name no longer exists | loud failure on the strict resolve path; silent null on the soft path | re-dumping gamevals from beta |

Alignment fixes only the first.

## ⛔ Counting components under-reports drift

A changed component count proves a shift. It is **not required** for one: an insertion and a
deletion cancel out in the total while every name between them moves. Interfaces carrying hundreds
of named components have been observed to drift with an *identical* component count and only a crc
change.

**Trigger on crc, not on count.** Every interface archive whose crc changed needs the shape
alignment run over it before drift can be ruled out.

## How alignment works

`ComponentAlignment` aligns each interface's component sequence between the two caches with
Needleman–Wunsch, scoring on a **shape** key — leading structural bytes plus length — rather than
content. Only a small minority of components are byte-identical across the two branches because
beta edits graphics and text constantly, but the shape survives those edits.

Two design constraints worth knowing before touching the constants:

- **Match on shape, not content.** Content equality alone leaves the aligner almost no anchors.
- **The gap penalty must be worse than a mismatch.** With a cheap gap the algorithm buys its way
  out of any mismatch with an insert/delete pair and invents shifts that are not there. An earlier
  version with a cheap gap hallucinated extra insertions in a large interface.

A remap is accepted only above a high shape-agreement threshold **and** only when it beats leaving
the ids alone. Interfaces below that are reported as **NOT aligned** — beta rebuilt them outright,
their ids stay beta-numbered and are wrong for us.

⛔ **Never lower the threshold** to force one to align. That trades a loud, visible gap for silent
wrong ids.

## Verifying a remap by hand

Compare component **byte lengths**, not content — rare lengths make unmistakable anchors. Dump both
caches' per-component lengths for the interface side by side (both opened read-only) and read off
the insertion: a genuine shift shows one unmatched length followed by a constant offset that holds
unbroken to the end of the interface, with the sequences agreeing exactly before it.

## The end-state assertion

For every aligned interface, **no named slot may be at or beyond the served cache's component
count**. Named < served is normal — those are components the server ships that beta has no name
for. Named ≥ served is a real defect: a name addressing a slot that does not exist.

## Finding out who is affected

A grep will not find them. Names reach the resolver as enum constructor arguments, as constants
behind local helper functions, from content DSL callers and from data files — and component keys
are usually assembled by concatenation, so the full key never appears literally in the source.
Only a small fraction of real component dependencies exist as inline literals.

Capture the names actually resolved **at runtime** instead, then intersect that set with the
interfaces the diff flagged. That gives a severity-ranked list: a shift in an interface the server
resolves dozens of names through is an incident; a shift in one nothing references is a footnote.
