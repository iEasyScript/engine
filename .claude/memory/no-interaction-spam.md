# ⛔ NEVER let an interaction re-fire on the script tick

Re-firing an interaction at a single object/NPC many times a second is **a one-way ticket to a ban**.
The dangerous shape is not a slow retry loop — it is a state loop that clicks, returns immediately, and
is re-entered on the next tick. `Script` ticks every 40 ms, so "click, return, repeat" is **~25 clicks a
second** at one object. Nothing about that looks human and it is trivially detectable.

**How it happened** (Gates of Elidinis, 2026-08-12): the moonstone gather clicked the node and then hit a
path that returned straight away — an already-satisfied wait predicate, a node that read as gone, an
interrupt flag that was already set. `stateLoop` was re-entered on the very next tick and clicked again,
tens of times per second. The action log's `FIRED SYNTHETIC OBJECT_1(...)` lines show it plainly.

**The exception: when repeated clicking IS the mechanic.** Some content is driven by pressing an option
over and over — each press does the work, and there is no outcome to wait on (Gates of Elidinis akh
"Dismiss" is one; each click chips it down). There, a steady human-paced cadence — `delay(600, 350)` —
is the *correct* implementation, and waiting for an outcome instead just stalls the mechanic. The rule
below is about interactions that start an action; it is not a blanket ban on repetition.

**Rules**
- ⛔ Do **not** bolt a hand-rolled timestamp throttle onto each `interact`. Sprinkling stopwatches through
  a script is a hack that hides the broken control flow instead of fixing it.
- Gate on **state the game already gives you**: `localPlayer.isAniMoving` (`isAnimating || isMoving`) means
  the player is walking to the target or already working it — so don't re-issue. That is the same signal a
  person acts on.
- Put the floor in the wait itself: **`waitThenDelayUntil(1200, timeout) { … }`**. Its leading delay runs
  before the predicate is ever polled, so a condition that is true on entry still cannot hand control back
  to the tick loop instantly. Plain `delayUntil { alreadyTrue }` can, and does.
- Fire once, then wait on the *outcome* (count changed, target dead, var flipped, animation started);
  re-issue only after a sustained stall.
- Ask of every retry: *if this predicate is never satisfied, how fast does this click?* If nothing in the
  loop can answer in seconds, the answer is 25/sec.

Reactive waits are covered in [[script-authoring-principles]]; this file is the harder constraint —
reacting to outcomes is about quality, not spamming is about not losing the account.
