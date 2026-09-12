# Taking an entry off the coverage ledger

Coverage status lives in `re-resources/docs/cache/decode-coverage.md`.

## Order of work

1. **Confirm the format from the client binary.** Delegate to `ghidra-reverse-engineer`. Never
   infer a field layout from our existing Kotlin, and never port one from the outdated reference
   binary — its offsets, enum values and struct layouts do not match the current build. Per-build
   facts (opcode tables, field layouts) live in the **Ghidra DB**, never in a doc or a code comment.
2. **Implement in `:core`.** `world.gregs.voidps.cache` is `cache-library-engineer`'s exclusive
   domain. Follow the existing decoder shape: a `TypeDecoder` subclass with an opcode `when`, a
   `*Type` data class, and the archive/file id split the client uses.
3. **Give it an explicit unknown-opcode arm** rather than letting the `when` fall through silently.
   That is what makes the next game update's new field visible instead of silently corrupting.
4. **Verify with the decode report** — zero trailing bytes and zero unknown opcodes across the
   whole index. Trailing bytes are the authoritative signal that a record was not understood.
5. **Cross-check against an independent source.** If the type has a `re-resources/gamevals/` table,
   its entry count and max id should line up with the archive's file count, and every id it names
   must decode — the gameval tables ship in their own cache index, so that is a genuine outside
   check. Better still, find a structure a *different* decoder also describes and require the two to
   agree (`DbIntegrityIntegrationTest` is the worked example). ⛔ Not every type has an outside
   oracle; where none exists, say so in the test rather than implying coverage you do not have.
   **Always assert a non-zero record count** — a decoder pointed at a missing archive decodes
   nothing and reports success.
6. **Re-run `:tools:cacheSnapshot`** and update the ledger's status column.

## Choosing what to do next

**Finish what exists before adding more.** This is a library: a decoder is either faithful to the
format or it is not, and whether anything currently calls it has no bearing on that. "Nothing
consumes it" is not a reason to leave a known defect — the first caller to trust a misnamed,
fabricated or truncated field inherits the bug.

So: bring every existing decoder to zero trailing bytes, zero unknown opcodes, no fabricated fields
and no misleading names, *before* writing a decoder for an index that has none.

Among genuinely new decoders, prioritise by **diff value**, not by size:

- indices that change often, over ones frozen for years;
- indices where a change is currently invisible, over ones already covered at archive level.

Bulk binary assets (models, textures, audio) are the largest gap by archive count and among the
least urgent, because archive-level identity already tells you exactly which ones changed. Do not
confuse "biggest gap" with "highest priority".

## A clean parse is not proof

A decoder pointed at the **wrong archive** can consume every byte and report a perfect parse while
producing entirely wrong values. This has happened in this codebase. Before trusting a new decoder,
confirm the archive identity independently (step 5) and sanity-check decoded string fields against
what the type is supposed to contain.
