# ⛔ What may be written to `.claude/memory/` (READ BEFORE WRITING ANY MEMORY)

This store is **committed and shared with every developer**. It is not a scratchpad, not a changelog,
and not a place to record what you just did. It was previously polluted with ~150 files (1.1 MB) —
one per feature, per bugfix, per solver — and had to be mass-deleted. Do not recreate that.

## The bar: would a competent dev fail to learn this by reading the repo?

Write a memory ONLY if **all** of these hold:
1. The fact is **not discoverable by reading the code, the git log, `re-resources/docs/`, or CLAUDE.md.**
   The code is self-documenting. Describing a class, a package layout, a DSL, or "how subsystem X works"
   is ALWAYS redundant — delete the impulse.
2. It is **durable** — still true after the feature ships, after the next build, after a refactor.
3. It is **repo-shareable** — machine paths, personal accounts and host tuning go in the private home
   auto-memory instead.
4. It falls in one of the four allowed categories below.

## The only four allowed categories

- **Recurring pitfalls / hard rules** — a mistake that has bitten more than once and will bite again
  (⛔ rules, "never do X because it corrupts Y"). Evidence of recurrence required.
- **Evidence-based RE findings** — binary-derived facts and *negative results* ("tried A/B/C, all fail,
  here's why") that would otherwise cost hours to re-derive. Byte-precise packet/format specs do NOT
  belong here — they go in `re-resources/docs/` (that is the project's documented contract).
- **Architecture-level topics** — cross-module contracts, invariants, and the non-obvious *why* behind a
  structural decision. Not an inventory of what exists.
- **Explicit user directives** about how to work, with the reason.

## Never write a memory for

Individual features, scripts, solvers, or content. Bug fixes ("fixed X on <date>"). Migration/session
logs and status updates ("Phase 2 DONE"). Restatements of CLAUDE.md, the agent files, or `docs/`.
Per-build addresses and struct offsets — those live in `Offsets.kt` + the Ghidra DB, which are the
source of truth and auto-updated. Anything you would title "we added …".

## Maintenance rules

- **Prefer editing an existing file** over adding one. A new file needs a new INDEX line; the INDEX is
  loaded every session, so every line costs context for everyone, forever.
- Keep files **terse**. If a memory exceeds ~100 lines it is a document — put it in `re-resources/docs/`
  and leave at most a one-line pointer here.
- Deleting a stale memory is as valuable as adding one. Wrong memory is worse than no memory.
- When in doubt: **do not write it.** Say what you learned in the reply instead.
