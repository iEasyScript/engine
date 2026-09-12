# Build / inject / hot-reload loop

## ⛔ Never rebuild the shadowJar while the engine is injected

The injected JVM has `client-plugin-engine/build/libs/*-all.jar` mapped and open. Rebuilding it in
place (`:client-plugin-engine:build`, or any `shadowJar` run) **corrupts the running client hard** —
overlay features go silently dead, the in-process MCP hangs, general instability. It is not a code
regression; the process is wrecked by the file swap, and only a restart recovers it.

- To iterate and verify code while injected, run **`:client-plugin-engine:compileKotlin`** only — it
  writes `build/classes`, not the jar.
- ⛔ **`:client-plugin-engine:test` is NOT safe while injected.** It depends on `jar`, which depends on
  `buildNativeBootstrap`, so running the unit tests rebuilds and reinstalls the `.so` the client has
  mapped. Same for anything else that reaches `jar`. Use `compileTestKotlin` while injected and run
  `test` only when the client is down — check with `pgrep -x rs2client` first.
- Build the real jar only when **not** injected. The order is **uninject → rebuild → reinject**.
- Script jars are a partial exception: building a script repo's jar never touches the
  engine `.so` or shadowJar, so it is safe *for the engine* while injected. ⛔ **It is NOT safe while a
  script from that jar is running.** The script `URLClassLoader` holds the jar open and resolves suspend
  lambdas / `State` continuation classes *lazily*, so overwriting it mid-run makes the next new code path
  throw `ZipException: invalid LOC header` → `NoClassDefFoundError: <State>$stateLoop$1`, and the script
  dies mid-task while the log fills with one stack trace per tick. Always **stop_script → build →
  reload_scripts → start_script**, never build first.
- This does not repeal the "rebuild the real artifact before calling it done" rule in [[hard-rules]] —
  it just has to happen around the inject lifecycle.

## Hot reload

Reload is **not** disable/enable: `JNI_CreateJavaVM` runs once per process and the `.so` is
`RTLD_NODELETE`, so the JVM and the native library are permanent for the process's life. A reload loads
the engine into a **fresh disposable `URLClassLoader`** in the same JVM. A fresh loader means fresh
Kotlin `object` singletons, which resets one-shot flags for free; only state living *outside* the
loader needs explicit teardown — funchook hooks, native ImGui, engine threads, native arena allocations,
JNI global refs. A permanent supervisor (the only engine code on the system classpath) owns a per-pid
Unix control socket and drives STATUS/INJECT/UNINJECT/REINJECT.

Two contracts that were learned the hard way:

- ⛔ **Teardown must NOT destroy the native ImGui/GL backend.** The game's GL context is unchanged across
  a reload and the reloaded engine reuses the existing ImGui context (native init is idempotent).
  Destroying and recreating the backend breaks texture creation on reload.
- **All per-load native allocations must come from a per-engine arena** (hook upcall stubs, funchook
  slots, UI state buffers, scratch), closed as the *last* step of shutdown — after hooks are uninstalled
  and every thread and script is stopped. A global arena pins metaspace and leaks across reloads.

Known unfixed: on a second-or-later reinject in one process, texture (re)creation and the input hook are
not reliably re-established — the deferred texture path times out and the UI hotkey stops responding.
The overlay is coded to survive it (null-safe decorations), but the real fix is native teardown-ordering
work. **Workaround: full client restart.** Symptoms are not caused by script code.

## Injection prerequisites

Auto-inject GDB-`dlopen`s the bootstrap into the newest non-injected `rs2client` and needs `JAVA_HOME`
(JDK 25). Attaching to a non-child process requires privileges when `ptrace_scope != 0` — so unattended
GUI injection needs either a running polkit agent (for the `pkexec` prompt), a NOPASSWD sudoers rule for
gdb, or relaxed `ptrace_scope`. Injection is best-effort and never blocks or kills the launch.

For launch/patch failures, read the spawned client's redirected log in the launcher data dir
(`.../project-x-launcher[/custom]/launcher-client.log`) — it captures the full patcher sequence and the
`rs3linux` error codes. Do not try to reproduce via CLI: a pre-seeded client skips the download/verify
path entirely and masks the failure.

[[engine-architecture]] [[engine-native-safety-and-crashes]] [[script-modules-official-community]]
