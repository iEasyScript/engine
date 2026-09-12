# Reading a cache diff

## The buckets

| bucket | meaning |
|---|---|
| **added** | archive id present in the new snapshot, absent in the old. New content. |
| **removed** | present in the old, gone in the new. Rare; usually a content retirement. |
| **changed** | crc differs. The archive's bytes are genuinely different. |
| **version-only** | crc identical, version bumped. Jagex reshipped identical bytes. |
| **file/component count changed** | the archive gained or lost files. For the interface index this means components were inserted or removed. |

## What is *not* a real change

- **version-only** entries. The content is byte-identical; Jagex bumps versions on rebuild. Noise
  unless you are chasing a cache-invalidation problem.
- **Index 12 (clientscripts) carries no meaningful version.** Its version field is inert, so crc is
  the only signal. A version-based comparison will always call it unchanged — never conclude from
  a version comparison that scripts did not change.
- **An index absent from one snapshot** is not "everything removed" — check whether that snapshot
  was taken with `--indices` restricting the capture.

## Reading counts

Archive counts are reference-table entry counts, not "how much content exists". An archive can
exist with a single file or with thousands. For the definition indices the file count is the
number of definitions in that archive, so `archives × files` is the meaningful size.

A **growing file count on a definition archive** means new ids were added — likely needing gameval
names, which only arrive via the beta branch.

## Severity, roughly

1. **Interface archives changed** — highest. Silently invalidates component name ids. Always act.
2. **Config archives changed** — may have added fields. Run the decode report.
3. **Clientscripts changed** — CS2 behaviour our automation depends on may have moved.
4. **Map archives changed** — world geometry; matters if anything is pathfinding or scene-building.
5. **Asset archives (models, textures, graphics, anim frames)** — cosmetic unless something renders
   wrong. These are usually the bulk of any diff by archive count and the least interesting.

Do not let raw archive counts drive attention: a texture index churning 40,000 archives is routine,
while three changed interface archives can break the UI.

## Cross-checking an archive's identity

If you need to confirm what an archive actually holds, two independent sources beat any single one:

- the **file count** of the archive in the cache, and
- the **entry count** of the matching `re-resources/gamevals/<type>.json`.

An exact match is strong evidence. A decoder parsing the archive cleanly is **not** evidence — a
decoder pointed at the wrong archive can consume every byte and produce entirely wrong values.
The client binary is the only authority.
