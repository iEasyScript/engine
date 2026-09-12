# Script authoring — reactive, humanised, modular

⛔ **All authoring/editing of code in `client-plugin-engine/official-scripts/` or
`client-plugin-engine/community-scripts/` goes through the `script-writer` subagent**
(`.claude/agents/script-writer.md`), including the supporting sensing/map/render/util code and tests
in those modules.

## Reactive behaviour is the whole point

Blind fixed delays and duplicated deterministic clicks are the primary **detectability** defect — a bot
that waits exactly 1200 ms after every click and never checks whether anything happened does not look
human. The rules:

1. **React to outcomes, never blind-sleep.** After an action, wait on the *observable consequence*
   (item appears/consumed, interface open, loc/npc/anim/tile change, event fired) with a randomized
   timeout, then a short variant settle. A bare `delay(n)` standing in for "probably done" is banned.
2. **Gate on the interact result.** Interaction calls return a `Boolean` — only wait for an outcome if
   the fire actually resolved.
3. **Every delay is gaussian-randomized** — settles, pacing, and `delayUntil`/wait timeouts alike. No
   exact repeating constants. (A pure sensing/overlay poll cadence may stay fixed; it is not an in-world
   action.)
4. **No trivial wrappers, no single-use string constants.** Inline the one-off. Keep numeric id/opcode/
   interface constants, id sets, and names genuinely reused three or more times.
5. **Humanisation is a behaviour-preserving refactor** — keep control flow, ordering, bounds, ban/skip
   logic and logging intact. A wait *before* an interact (e.g. "not moving yet") is already reactive;
   it is the blind delay *after* that becomes an outcome-gated wait.

## Structure

- **Register, don't dispatch.** Content variants (dungeon rooms, puzzles, bosses) are **data entries in
  a registry** that the bot loop walks, first match wins. Adding one is a single entry — never grow an
  `if (X.present()) …` chain in the main loop, and never edit the loop to add content. The user called
  the growing chain "a disgusting way to handle all of the puzzles"; when pointed at structural ugliness,
  design the abstraction instead of adding another special case.
- **Presence checks must be scope-bounded**, not a flat radius — scan the room/area the puzzle actually
  occupies, or unrelated nearby objects will trigger it.
- **Quest puzzles are per-step solvers** registered against the step's `solverId`, dispatched by the
  existing quest tick. Do NOT add a per-puzzle tick call into the client main-logic hook — there are
  hundreds of puzzles and peppering the hot path with each is unacceptable. Interface layout that other
  quests could reuse goes in a shared utility; only the puzzle-specific deduction lives in the solver.
  A genuinely cross-content always-on overlay may be a standalone feature; quest-bound ones may not.

## Debug tooling and testing

- **Expose primitives, not predicates.** A debug/MCP tool that calls the engine's own `isDialogOpen()`
  lies in exactly the same way the predicate does — and the predicate is usually the thing being
  debugged. Surface the raw state it reads from (open interface list, varbit values, screen rects,
  before/after diffs) so the bug is *visible*. "Wait for X" tools poll a primitive and report what
  changed.
- **When live-testing a bot, do not stop it to prevent death.** Death is irrelevant to what is being
  tested, and stopping throws the test away. Stop only if the user asks or to hot-reload. Diagnose from
  the live state tools and logs.
- **No fake tests** — see [[hard-rules]].

[[script-modules-official-community]] [[engine-synthetic-input]] [[engine-build-inject-workflow]]
