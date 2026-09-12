# Mobile Client (Android) — scoped guidance

Reverse engineering the **RS3 Android client** to point it at a locally-run Project X server. The mobile
client is the **same NXT engine as desktop `rs2client`**, recompiled for **AArch64**. Wire protocol, cache
formats, and the `jav_config` mechanism are shared; the machine code is not.

## Where things live

- **Tooling** (this dir, `mobile/`): capture + patch + build scripts. See `README.md`.
- **Knowledge**: `re-resources/docs/mobile/` (== `docs/mobile/`). RE findings, packet/login/JS5 specs,
  hook points, RSA key locations, cross-arch methodology. Start at `docs/mobile/README.md`.
- **Binaries + build metadata**: `data/client/android/` (gitignored) — APK, extracted `.so`/dex/jadx,
  `build-info.json`, generated `moduli.json`, frida-gadget, built APKs, captures. Populated by
  `import-apk.py`; recorded in `data/client/clients.manifest.json` (the shared android entry, `binaryType 7`).
- **Agent**: `mobile-reverse-engineer` (`.claude/agents/`) owns the Ghidra DB for the mobile `.so` and the
  `docs/mobile/` output. The desktop `ghidra-reverse-engineer` stays the x86-64 authority.

## Cross-architecture rule (the one that matters most)

Desktop is x86-64; mobile is AArch64. Same source, different machine code. Desktop is currently
**949-4**; mobile is **949-3** (`data/client/android/build-info.json` is authoritative). Same major, so
the wire protocol and cache formats are shared — but architecture is **not** the only axis of
difference, and concrete addresses never port across either axis.

- **Ports:** wire protocol, opcodes, packet layouts, cache/JS5 formats, class/namespace names, `.rodata`
  algorithm constants (RSA moduli, ISAAC, CRC tables), string literals.
- **Does NOT port:** byte-pattern signatures, addresses, function sizes, inlining decisions, calling
  conventions. An x86-64 sig is meaningless here.
- **Probably ports, verify per-struct:** field offsets. Both are LP64, so same-source structs *usually*
  match — a great hypothesis generator, a terrible source of truth. Predict from desktop, confirm against
  mobile's own field accesses before committing.

**Always analyze the mobile target first; consult desktop only to confirm.** Never state a desktop fact as
a mobile fact — tag it `[PREDICTED FROM DESKTOP — UNVERIFIED ON MOBILE]`.

## Confidence tags (every documented claim carries one)

`[VERIFIED @ 0xADDR]` · `[VERIFIED — artifact]` · `[PREDICTED FROM DESKTOP — UNVERIFIED ON MOBILE]` ·
`[PATTERN HINT from librs2client.so]` · `[UNCONFIRMED — hypothesis]`. An untagged claim is a defect.

## Conventions (inherit the monorepo rules)

- Root `CLAUDE.md` git rules apply: **work in the working tree, do not commit/branch/stage/push.** Note
  `re-resources/` is a separate submodule repo — mobile docs there are a distinct commit boundary.
- Ghidra namespaces: `jag::*` / `eastl::*`, plus **`jag::android`** for the mobile-only platform glue
  (JNI bridge, `NativeActivity` lifecycle, `StartupArguments` upcalls).
- Certainty before commitment: nothing goes into the Ghidra DB unless conclusively verified. `FUN_` + a
  labelled hypothesis is the correct output for uncertainty. Wrong names are worse than none.
- Reference packets by NAME (`REBUILD_NORMAL`), never bare `op#`.
- The mobile `.so` and the desktop `rs2client` are loaded in the **same Ghidra project + MCP bridge**
  (`.mcp.json` at repo root) — no separate MCP.
