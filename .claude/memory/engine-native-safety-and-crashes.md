# Engine crashes, threading, and native safety

## ⛔ `invokeExact` in a value-producing position kills the client (bitten twice)

`MethodHandle.invokeExact` links the call site from the argument types **and the expected return type**, and
demands an exact match. Anything that makes the call an expression rather than a statement — wrapping it in
`runCatching`, passing it as a lambda to a generic helper, letting it be a block's last expression — makes
Kotlin infer `Any`, so the site links as returning `Object` against a `void` trampoline and throws
`WrongMethodTypeException`.

That exception then **escapes the Panama upcall stub, and the VM terminates: SIGABRT, no usable native
backtrace**, which reads as "crashing on injection" rather than as a Java type error. The Java stack trace is
in `~/.projectx/logs/projectx-*.log`; the crash forensics file alone will not tell you.

- Keep every trampoline `invokeExact` as a bare statement, never inside a lambda or a value-producing block.
- For a **direct** function handle (`toFunctionHandle`, not a trampoline) prefer `invoke` — it adapts types
  and cannot fail this way. That is why the existing `callListenersFn` / `isKeyDownFn` call sites use it.
- A hook body must be incapable of letting *any* exception escape; wrap each step, including the trampoline
  call, so a bug degrades a feature instead of killing the process.

First occurrence: injected keys (documented inline in `KeyInput.kt`). Second: the per-tick prot pump hook,
where a generic `aroundFlush { … }` wrapper reintroduced it.

## Thread model — build the UI on the game thread

⛔ **Never read live native game state off the game/main-logic thread.** The recurring "random render
crashes" were an ImGui overlay *built* on a background worker that read varps, cache types and the Client
struct while the game thread mutated and freed them — a use-after-free SIGSEGV, uncatchable. Per-renderer
snapshotting is not a fix: the copy itself does the unsafe read.

The overlay is therefore **built on the main-logic thread** at end of tick, into a triple-buffered command
buffer, and the `eglSwapBuffers` hook is **pure replay**. This is safe because the DSL only constructs
command objects under capture — no GL calls — and textures already defer their GL work to replay.

⛔ **Never touch the EGL/GL context off the render thread.** A second, game-shared EGL context with
`eglMakeCurrent` from a JVM `Cleaner` thread raced the game's own context teardown and tripped the game's
deliberate abort on a failed context release. All texture create/destroy now happens on the render thread
via a queue drained at frame start.

## Triaging a crash

Two unrelated SIGSEGV signatures dominate:

1. **EGL teardown — fires on EVERY client close, including vanilla, and MASKS the real crash.** The game
   fails to release its EGL context (native window already gone) and deliberately traps. When a genuine
   fault causes the client to shut down, this same teardown runs and *its* core is what gets written. An
   EGL core is not evidence that EGL was the cause. Do not chase it — find the actual fault from the
   engine log tail or the thread that really faulted.
2. **A garbage read from a wrong offset or a wrong traversal model** — this is the recurring one, and it
   is never "a bad pointer to guard against". A representative case: a fabricated child-vector offset
   that overlapped two unrelated item fields, so a `{begin,end,cap}` walk dereferenced an item id.
   `try`/`catch`/`runCatching` cannot catch these. The fix is always to re-read the decompilation, verify
   the offset against the binary, and write a traversal that only ever touches real objects.
   Do **not** add pointer plausibility checks or heap-range validation — see [[re-discipline]].

Note that a main-thread fault reaches the engine's own handler and writes a crash file, while a fault on
any other thread reaches the game's handler and shuts down — producing a masked EGL core instead.

## UI initialisation hazard

⛔ **Never `!!` a texture load (or call `error(...)`) in a top-level `object` val.** Texture creation
returns null when the GL pipeline isn't ready yet — common on the first frames after an inject, and
routine after a *reinject*, where the deferred cross-thread texture path times out. An NPE inside
`<clinit>` poisons the class permanently for that classloader's lifetime, so every later touch throws
`NoClassDefFoundError` and the tab appears dead forever. Load textures lazily, keep them nullable, retry
each frame, and never let a decorative texture drive layout. Chrome textures are warmed on the render
thread where EGL is current.

## Telemetry

All outbound crash/error telemetry is suppressed by an intentionally **empty** hook on the client's single
error-report funnel — every crash path (fatal signal handler, fatal error, script error, connection error)
converges there, and it POSTs the message plus a relocated backtrace to Jagex in-process. The body is
inert by design: it is often reached from inside a fatal signal handler with the process in an undefined
state, so reading arguments or running JVM work there is unsafe. There is no third-party crash SDK and no
crash artifact written by the game — those files are the engine's own. The launcher does not upload
crashes and behaves identically however the child dies, so no launcher-side neutralisation is needed.
⛔ The hooked address drifts every build — re-locate it during migration or telemetry silently resumes.

[[engine-architecture]] [[engine-build-inject-workflow]]
