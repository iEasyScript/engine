# Project X engine — architecture and cross-module contracts

Kotlin/JVM (JDK 25 + Panama FFI) plus a C++20 native bootstrap. Injection is GDB `dlopen` of
`libprojectxbootstrap.so` → JVM → `Bootstrap.initialize(baseAddr)`. Hooks are funchook, declared
`@Hook(offset)` / `@SymbolHook(library, symbol)`. Memory access is `MemorySegment` at offsets relative
to the process base, with EASTL readers (string SSO, hash_map, list, vector, fixed_pool). The overlay is
ImGui driven from a hooked `eglSwapBuffers` on Linux and a Win32 backend on Windows. In-process MCP
server on port 7882. Both linux-x86_64 and windows-x86_64 are supported: the bootstrap has a
`platform/win32.cpp`, the launcher ships a Rust `projectx_engine_injector`, and `resources/offsets/` carries
a table per platform and build.

## Engine ↔ `:core` — one canonical implementation

The engine consumes `:core` for **networking protocol, the cache library, and spatial/collision/
pathfinding**. There is no engine-side copy of any of them; `:core`'s server-only dependencies (Mongo,
embedded mongod, argon2) are excluded from the engine's dependency on it. Contracts worth knowing before
touching engine code:

- ⛔ **The injected engine MUST open the live NXT cache read-only.** The running client has those SQLite
  files open; a read-write open (and especially a `journal_mode=WAL` pragma, which is a persistent header
  write) corrupts exactly the indices the engine decodes and forces the client to re-download them on the
  next launch. This was the "why does the cache redownload every launch" bug. The server owns its own
  cache and stays read-write. Never make the engine's open read-write again.
- **Use the lazy per-id cache accessors** (`Cache.npc(id)`, `Cache.obj(id)`, …, all nullable) in the
  injected process. The eager arrays decode tens of thousands of definitions and exist for the server and
  MCP content listing only.
- **The engine must `Cache.init(path)` before any decode**, resolving the live NXT directory; otherwise
  it falls back to the server's configured cache path, which doesn't exist inside `rs2client`.
- ⛔ **JDBC drivers do not auto-register in the engine's child classloader.** `DriverManager`'s
  ServiceLoader scan only covers the system classloader, so the SQLite driver must be explicitly
  registered or every index silently fails to load and all definitions come back empty. This is invisible
  to disk/unit tests, which run on the normal classpath — only the injected loader hits it. Keep the
  explicit registration.
- **Spatial semantics that must not be "simplified":** `Tile.getDistance` is **Euclidean** while
  `distanceTo` is **Chebyshev**, and the no-arg `withinDistance` default is a plane-aware 20-tile
  Chebyshev box. Collision and pathfinding are core classes (`CollisionMap` holds the flag grid);
  game-coupled wrappers stay engine-side.
- Region decode returns **plain data** — raw object planes, raw shape/rotation ints, no collision or
  scene types. Bridge-plane adjustment and collision building are consumer concerns, engine-side.

## Scripts are not part of the engine

Concrete automation lives in two subprojects, not `:client-plugin-engine` — see
[[script-modules-official-community]]. The engine provides only the framework, so any engine helper that
scripts call must be **public/protected, never `internal`**.

[[engine-build-inject-workflow]] [[engine-native-safety-and-crashes]] [[client-internals-re]]
