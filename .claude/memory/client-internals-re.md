# Client internals — RE findings the engine depends on

Addresses drift every build; `Offsets.kt` + the Ghidra DB are the source of truth for values. What's
recorded here is the *model* — the structural facts and negative results that are expensive to
re-derive.

## Picking (two separate paths)

- **Locations / scene objects** go through screen-space `TestEntityHit`, using the Entity picking
  fields (pickType, screen rect, radii) populated by the `UpdatePickingMode` vtable slot. Two modes:
  point/circle when `pickType == 0`, line-segment otherwise.
- **NPCs and players** go through **per-triangle mesh picking**: bone skinning → entity world transform
  × camera view-projection → project every vertex → triangle-vs-rect test. Per-vertex bone id comes
  from the weights block (byte 0), with a fallback to a vertex byte. The engine replicates the whole
  bone-skinned projection in `MeshProjection.kt` for the clickbox overlay.
- ⛔ **GraphNode AABB and scene position store float3 as {X, Z, Y}, not {X, Y, Z}.** The
  world-to-screen swizzle is `.x→col0, .z→col1, .y→col2`. Getting this wrong yields plausible-looking
  but subtly wrong boxes.

## Highlight

A global highlight category table (8 entries × 16 bytes: mode byte, scale byte, RGB floats) drives
per-entity highlight; a PathingEntity carries a category index plus a `showAsImportant` flag set by CS2,
and the render model receives R/G/B/alpha/scale/flag floats each frame.

⛔ **Highlighting Locations / ItemStacks from the engine is a DEAD END — do not retry the obvious
paths.** Three approaches were tried and all had zero visible effect: setting the Location
`showAsImportant` flag alone (the canonical CS2 opcode write), setting all three Location highlight
fields directly, and priming the category table then flipping the flag. The per-Loc render orchestrator
does read the flag, but a stack of earlier gates (world-ref state struct flags, LocType list contents)
isn't satisfied for ordinary in-range Locations; the CS2 opcode and its setter look like leftover
infrastructure. What in-game looks like Loc hover-highlight is most likely an always-on flag baked into
specific LocTypes' cache data, not a runtime hover system. **NPC/player highlight via direct render-model
write works perfectly and is the baseline — don't touch it.** Scene objects and ground items get a tile
overlay instead. Untried option if it ever matters: funchook the per-draw-call render-model builder for
Locations and write the colour slots post-hook.

## Terrain heights

`HeightMap.kt` **replicates** the client's fine-height sampler rather than calling it (the real function
returns a 16-byte struct; replication is safer and matches how the rest of the engine works). The model:
a region grid of cells → a height container per region → per-plane vertex grids sampled bilinearly in
fine units (512/tile). Two non-obvious pieces, both of which produce plausible-but-wrong output when
missed:

1. The container's readiness test is an **equality** of two bytes — inverting it silently samples the
   not-ready fallback and yields near-flat terrain.
2. Raised platforms and bridge decks are **NOT in the base grid**. Their elevation lives in a second,
   **additive per-vertex int16 grid** on the same container, plus a distinct plane-0 bridge grid. Base
   bilinear alone renders them flat.

⛔ Still not implemented: the true-bridge **+1 plane bump** (sample plane+1 when the LinkMap bridge flag
is set for the tile). Only matters for walk-on bridges, not raised terrain.

## Mouse pipeline

⛔ **CORRECTED — "there is no continuous mouse-movement packet" was wrong.** The client does send movement
history, and a Windows client sends a second family of mouse packets the Linux one never does. The real
shape, re-derived from the binary with structs applied:

- ⛔ **MainLogicManager owns TWO rings with identical entry layout, not one**: a small **click** ring drained
  one entry per tick by the click sender, and a much larger **movement** ring drained in a loop by a separate
  variable-length movement-history prot. Each has its own write/read cursor pair sitting immediately after its
  slots. Confirmed three ways: two init loops in the manager's constructor each followed by an 8-byte cursor
  clear, the move-pop's own wrap bound, and the discard-pending-input path draining both pairs side by side.
  The shipping Linux build has the same 6-slot sender vtable shape and the same two-ring design.
- ⛔ **The click entry's `button` field is a button ID whose zero value is the LEFT button — it is NOT a
  "was this a click" flag, and motion never enters the click ring.** The per-tick minimenu/interaction update
  reads a queued entry as *where the click happened* and falls back to the live cursor when the ring is empty;
  those fallbacks are only reachable if the ring is normally empty, which it could not be if every mouse move
  pushed an entry. The click packet carries `button != 0` in its top bit — the right-button bit — so **no
  encoding of it means "the cursor moved without clicking".** Writing a zero-button entry and sending
  therefore announces a left click. *Caveat: the click ring's producer is unlocated (no writer of its write
  cursor exists under any addressing form its consumers use), so this rests on consumer semantics rather than
  on a store of the constant. A live capture of one left vs one right click settles it in one observation.*
- The click entry carries **two coordinate pairs**: one that goes on the wire and one the minimenu and
  interaction consumers read. Anything writing an entry has to save and restore both.
- **Four mouse prots exist**, not one: EVENT click, EVENT movement-history, NATIVE click, NATIVE
  movement-history. Only the NATIVE history packet appends a per-sample extra byte; the EVENT sender's
  equivalent vtable slot is a bare `ret`.
- A **per-tick prot pump** flushes each mouse sender in turn. Per sender there are two stages: a
  **conditional variable-length movement-history packet** built by draining that sender's *move* ring
  (position delta-encoded in a tiered 1/2/4-byte form, time delta-encoded with a carried remainder, samples
  repeating the last sent position skipped, sample count back-patched), then an **unconditionally called**
  click sender whose emptiness guard is inside it rather than at the call site. So "the send function was
  called" does not mean "a packet went out".
- The gate on the history stage keys off the **click** ring plus history staleness, not the move ring — so a
  move-only tick emits history at most once per staleness interval, and immediately on any tick that also has
  a click pending.
- **Windows adds a whole second sender** with its own click and move rings (a small click ring, a much larger
  move ring, sharing one sample type). It exists to report the OS's hardware-vs-injected verdict: a
  low-level mouse hook feeds each event to a dedicated dispatcher, and a single listener registered by the
  ClientProt constructor stores position, a monotonic timestamp, the **raw Windows message number** and the
  hook flags into the ring. That listener does nothing else — no raycast, no DoAction, no hit-test — so
  driving the dispatcher has no local game side effects. It does take the InputHandler mutex, and only the
  *client's* InputHandler carries the listener.
- ⛔ **The Windows verdict is not click-only: the native movement-history packet carries one source-flag byte
  per movement sample.** Producing a click companion without also feeding the native move ring closes half
  the gap and leaves the other half inconsistent.
- The low-level hook forwards **button-DOWN and double-click messages only, never button-UP**, so the ratio
  of native entries to game clicks is not 1:1 — a double click yields two native entries, and a middle-button
  press yields a native entry with no game click at all.
- Ring overflow **drops the oldest sample and always accepts the new one**; replicate that rule rather than
  refusing on a full ring.
- The packet opcode and size for the native sender are **not statically recoverable** — the descriptor pair
  is populated at runtime by prot registration and reads as zero in the file. Any opcode claim about it is a
  guess until a live capture or a runtime read settles it.
### Input recorders — the producers, and why no ring is a private channel

A MainLogicManager sub-object registers delegates on the `Input` **global** handler: eight for the mouse
(three button down/up pairs, move, wheel) and three for the keyboard (down, up, char). These delegates are the
ring producers.

- **A movement sample is pushed once per OS mouse-motion event, not once per frame**, and the delegate has
  **no moved-since-last test** — every invocation writes a slot. Duplicate positions are suppressed much
  later, by the prot sender, never at the producer. So a burst of OS events becomes a burst of ring entries.
  Its `timestamp` is snapshotted inside the delegate, so it is event time — but the clock is *inferred* to be
  millisecond resolution, which would make a whole burst carry one timestamp. Confirm against a capture before
  building anything on intra-burst timing.
- **The keyboard is press-only on the wire.** The key-up delegate updates modifier and held state and pushes
  nothing, so there is no release to encode. An unmapped key is dropped entirely by the down delegate. What
  reaches the wire is the client's **post-remap protocol key id** (low byte) plus a time delta against the
  previously emitted key event; the modifier mask and the repeat flag are local-only, with repeat acting purely
  as a send filter. Typed characters go to a **second ring** and no separate prot for them was found — whether
  they share the key stream is unresolved.
- Windows' native movement ring has a **different producer on a different stream** (it receives the Win32
  message id and source flags, which the game-event delegate cannot supply). The two movement rings are not
  two views of one path.
- ⛔ **Every one of these rings is dual-consumed — none is a private server-bound channel.** The movement ring
  is peeked each tick by the interface/component code to obtain the cursor position (newest entry only, cursors
  never advanced — the mildest case). The click ring is consumed by the minimenu/interaction update. The key and
  char rings are exposed to local key handling and CS2 as pointer ranges over the *live* records, with no copy
  step to slip between. Writing entries and leaving them there therefore always has local effect; the only
  mitigation is the stash-drain-restore window described in [[engine-synthetic-input]], whose viability depends
  on intra-tick ordering and on having a callable emit point.
- Overflow on every mouse and key ring is **drop-oldest, silent, no loss signal**, so usable depth is capacity
  minus one.
- ⛔ **The two ring families use OPPOSITE cursor conventions, and both were mislabelled at some point.** For
  the **mouse** rings the first dword after the slots is the **write** cursor and the second is the **read**
  cursor; for the **keyboard** rings it is the other way round. Verified from instructions in four places
  (the click sender, the movement pop, the movement producer, the key producer) — never carry one convention
  across to the other family. The engine's click-ring offsets were named the wrong way round, which made its
  injection write one slot behind the slot the sender then read.
- ⛔ **A sample's coordinate order is X then Y**, confirmed by the click packet building its position word as
  `(slot[+8] << 16) | slot[+4]` over a little-endian wire and by the movement producer storing its first float
  argument at `+4`. The Linux offset table had X and Y transposed relative to both.
- Both the mouse and keyboard producers stamp their samples from **one shared global millisecond clock**, and
  both prot senders delta-encode against it. Anything synthetic must be stamped from that same global or its
  deltas will not match the cadence the encoder expects.
- The keyboard packet does **not** read the key ring. It reads a **vector of copied records** on the per-tick
  input-processing object, which that pass fills from the ring. So a synthetic key press can be delivered by
  repointing that vector for the duration of one flush, without touching the ring or the client's held-key,
  modifier and CS2 state at all.
- ⛔ **On Linux the per-tick pump and the movement-history encoder are inlined into one enormous merged region
  with no function boundaries**, so there is no callable equivalent of the click sender for them. The Win32
  twin of the same build is where that logic is readable. Repairing those boundaries is the prerequisite for
  driving the history stage explicitly.
- ⛔ **The click ring buffer is the game's primary input dispatch queue, not a packet staging area.**
  Anything written there is consumed locally the same tick — raycast, entity-under-cursor, `DoAction`
  callback — so pushing entries to "humanise the server trail" fires real in-world actions. Proven twice,
  including with click entries hard-rejected at the API: motion-only entries still produced real clicks.
  Inject by hooking the send function and rewriting the entry *after* local processing, or by
  substituting the coordinate bytes as they are written to the packet.

MAPSV2 map-archive format spec: `docs/cache/mapsv2-format.md`.

[[engine-synthetic-input]] [[re-discipline]]
