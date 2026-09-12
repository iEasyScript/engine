# Synthetic actions — what the client does and doesn't do for you

## ⛔ Two input paths, structurally separate — never merge them

`com.projectx.game.input.wire` tells the server where the cursor was and produces **zero** local effect;
`com.projectx.game.input.action` deliberately produces real input (script actions, staying logged in). The
wire path must never reference the action path — a unit test enforces it, because nothing in the type
system does. The raw primitives are `internal` so scripts reach them only through `script.api`.

- ⛔ **NEVER inject camera input, and never send camera state to the server.** Trent's explicit direction:
  the camera is *absurdly* dangerous to drive or to report improperly, so it is out of scope entirely — not
  "do it carefully later". Do not add camera columns to recordings, do not synthesise camera movement, and do
  not touch the camera prot. This overrides any earlier note that treated camera context as a nice-to-have.
- **Both server-only producers now exist, and each works by substituting rather than by enqueuing.** The
  trail writes samples into the movement ring, invokes the client's own flush, and restores the ring — but
  deliberately does **not** restore the sender's delta state, because the server's model of the cursor is now
  ours and the next natural packet must encode from it. Key presses never touch the key ring at all: the
  keyboard packet is built from a vector of copied records on the per-tick input-event view, so the producer
  points that vector at engine-owned records for the duration of one flush and puts it back. Both skip
  entirely when the player has real input staged, so a synthetic packet never stacks on a natural one.
- **The movement stage fires on a staleness interval, not because the ring has content.** A trail therefore
  has to be buffered across ticks and re-offered, and emission is detected by the encoder's delta clock
  advancing — "the flush ran" does not mean "a packet went out".
- ⛔ **A cursor trail can NEVER be built from the click sender.** Its ring entries are button presses (see
  [[client-internals-re]] for the evidence), so writing a zero-button entry and sending announces a left click
  at those coordinates. Doing that once per tick — as the engine did before this was found — is a stream of
  left clicks at a regularity no human produces, on a packet the client sends only on a real press. Position
  alone travels in the **movement-history** packet fed from a separate movement ring, emitted on a staleness
  interval rather than every tick, with tiered position deltas, coarsely quantised time deltas, a back-patched
  sample count and same-position samples elided. Until that producer exists the wire path stays off on every
  platform.
- **The wire path must emit the platform's whole packet set or nothing.** A real mouse event produces one
  packet on Linux and **two** on Windows (the click event plus the OS hardware-vs-injected source report,
  which the Linux client registers but never sends). Emitting only the click event on Windows is a
  *stronger* signature than sending nothing, so the wire path self-disables when it cannot produce the
  full set. Resolved once in `WirePacketVariants`, never as scattered platform branches.
- **At most one input source per client cycle**, natural > action > wire (`InputArbiter`). Natural input is
  visible as a non-empty click ring, but an action is not always — the right/middle dispatch runs listener
  slots rather than enqueuing — so the action path reports itself, and an unknown cycle denies the wire
  path instead of guessing.
- **Ring writes must prove they are on the game thread** (`GameThread.isCurrent()`). The game reads and
  writes that ring without locks, so an off-thread stash-drain-restore corrupts an entry mid-read and the
  crash lands nowhere near the cause.
- **The recorder's tick-polled buttons see synthetic writes.** Right/middle injection writes the same state
  byte the recorder samples, and a flag held across the call cannot filter it because dispatch and poll both
  run on the game thread. The injecting side marks the button and the next poll resyncs its baseline
  silently (`SyntheticButtonState`).

Firing an action from the engine is not the same as a real click, and the differences are the source of
most "the packet went out but nothing happened" bugs.

- ⛔ **A synthetic component click sends the server packet but does NOT run the client-side `onop` CS2
  scripts.** Any UI that is *rendered by a client script* — dropdowns, "view options", most secondary
  panels — therefore never appears: the packet goes out, nothing renders, and there is nothing valid to
  click next. Drive those renders explicitly with `CS2Executor.executeScript(...)`. Keep the visibility
  safety net that drops clicks on unrendered components — it is correctly refusing invalid input; the
  fix is to make the component genuinely rendered, not to bypass the check.
- ⛔ **Trailing flag bytes on interaction packets are read from live client state that a synthetic fire
  does not set.** The ground-item (objstack) packet carries a bit sourced from the right-click menu
  manager's `menuOpen` byte, and it flips behaviour entirely: set → the server picks the item up
  immediately; clear → the server opens the area-loot interface. A direct synthetic fire inherits the
  resting value, so it must poke the flag across the native call and restore it afterwards. Expect the
  same class of problem on any interaction family whose sender reads a manager field; the run flag is
  shared across loc/npc/objstack, `menuOpen` is unique to objstack, and that asymmetry is how it was
  pinned down. Re-verify the polarity on major bumps.
- **Server-bound packet bytes must originate from Jagex's own code.** Never assemble opcode/length/payload
  in Kotlin and push it through the send function — the wire format is what gets monitored, and any
  divergence in ordering, padding or length semantics is a signature. Write the values into the client's
  own entry and invoke the real send trampoline so Jagex's writers produce the bytes.
- **Never let a synthetic send stack on a natural one in the same tick.** A non-empty input buffer means
  real player input is already queued and the tick orchestrator will send it; sending too produces two
  packets in a window that naturally holds one — the easiest possible bot signature. Short-circuit when
  the buffer is non-empty, and restore any state you poked in a `finally`.

[[client-internals-re]] [[script-authoring-principles]]
