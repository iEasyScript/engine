# RE discipline — correctness is paramount

A wrong identification does not stay local: it poisons the caller graph, and later agents (and humans)
layer more wrong names on top of it. The user has been burned by this repeatedly — "incorrect namings
quickly spiral into LOTS of incorrect naming down the line." A phase that names 50 functions at 100%
accuracy beats one that names 500 at 90%.

## Committing an identification

- **Default outcome is a HYPOTHESIS comment, not a rename.** Only rename when the evidence is
  overwhelming. "Probably"/"likely" is not sufficient.
- **Three independent lines of evidence** before any rename: name-source agreement across references,
  behavioural match in the target (decompile shape, control flow, callee fingerprint, string/constant
  refs), and caller-graph plausibility. An out-of-place caller halts the rename.
- **Sig-scan must yield exactly one match.** Multiple hits = the signature isn't unique — refine or abandon.
- **No transitive trust.** Names placed by an earlier pass must be re-verified before being used as
  evidence. Sanity-check any anchor function's existing name before leaning on it.
- **No batch-rename scripts** over unverified candidates. Every rename gets per-function verification.
- **No confidence inflation.** If the investigation was partial, the comment says so.

## But: what you DO confirm must be persisted

Documentation alone is not enough — an RE pass that produces a long document and zero Ghidra changes
wastes the work. Every confirmed function, data label and ProtEntry is renamed immediately (full `::`
namespace path), given a complete prototype, and its `*(type*)(ptr+0xNN)` patterns turned into structs
applied to locals. Do not move on before persisting the current item.

## Source trust

- **NEVER trust a name already in the stripped `rs2client` Ghidra DB** — they were all fabricated by
  earlier RE passes guessing. Match handlers by LOGIC, never by label. Distinguish "Ghidra label
  (fabricated)" from "verified Jagex symbol" when reporting.
- ⛔ **A name WE assigned is not evidence when a later pass reads it back.** Writing an ASSIGNED
  placeholder into the DB and then citing it from another pass is a naming loop closing on itself —
  it manufactures corroboration out of nothing and can promote a guess to "confirmed" within a day.
  Mark every assigned name ASSIGNED in its DB comment, and when a pass cites a name as support,
  check who wrote it first. A name with no attestation in the target strings, the cache, or a Jagex
  symbol stays a placeholder no matter how many of our own passes have since repeated it.
- **The reference binary (`librs2client.so`) and `symbols/parsed_functions.txt` are SEVERELY outdated**
  — code-pattern and namespace discovery only. Never copy an offset, signature, enum value, switch case
  or struct field position from them. `rs2client` is the only binary that is ever modified.
- ⛔ **Legacy `~/projectx/*` servers (727-era and 2012) have ZERO authority for ANY current-build value**
  — not just wire format: varbit ids and encodings, skill/state formulas, item/npc/loc/interface ids,
  opcode maps. All 15+ years stale. High-level architecture patterns only, and even then never copy a
  number or an encoding. Every current value comes from the cache, gamevals, our own
  dumps, or RE of the actual binary.
- **A live capture is ground truth.** When a capture disagrees with a decompilation reading or a Ghidra
  label, the capture wins — go back and find what the code actually does.

## Memory safety when replicating client behaviour

- **Never "validate" a pointer** via `/proc/self/maps`, heap-range checks, `isPlausibleNativePtr`, or
  `runCatching` around native reads. A native SIGSEGV is uncatchable from the JVM anyway, so these
  guards buy nothing and hide the real defect. Null/bounds-check exactly where the binary does.
- A garbage-read crash is **always** a wrong offset or a traversal model that doesn't match the binary —
  never "a bad pointer to guard against". The fix is to re-read the decompilation and correct the
  offset in all three places (Ghidra struct, `Offsets.kt`, Kotlin wrapper). See
  [[engine-native-safety-and-crashes]].

[[re-workflow-and-resources]] [[cross-version-migration]] [[protocol-invariants]]
