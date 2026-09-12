# Protocol invariants (survive builds; re-verify values, not these rules)

⛔ Byte-precise packet tables live ONLY in the Ghidra DB — never in `re-resources/docs/`, never in a
code comment. `docs/` carries build-agnostic system knowledge only. This file is the set of rules that
keep being violated.

- ⛔ **Opcode width is ASYMMETRIC.** C2S is ALWAYS a single ISAAC-ciphered byte (0..255). The 2-byte
  `>= 128` encoding is **S2C only**. There is no C2S `NO_TIMEOUT`; the heartbeat is the per-tick
  server-side `SERVER_TICK_END`.
- ⛔ **RS3 maps have NO XTEA.** It was removed 15+ years ago. An empty map/loc read is NEVER an
  encryption problem — check archive id, file id, compression, and presence instead. Do not add key
  handling to map loading.
- **Opcode and size come from the deterministic prot dump only** — never from a guess, a sibling
  revision, or an older server. `:core`'s serverProt tables must match the dump exactly.
- **Use the official Jagex prot names** from the enum symbols. An unidentified packet is `UNKNOWN_<n>`,
  never an invented descriptive name that will later conflict with the real one. When registering
  client codecs, register the official-name table before any stubs so the real names win.
- **A live capture outranks a decompilation reading.** If they disagree, the capture is right.
- **One codec, one place.** The engine consumes `:core`'s protocol; opcodes/sizes/names/structures exist
  in exactly one module. Never re-declare a packet type engine-side.

[[re-discipline]] [[cross-version-migration]] [[hard-rules]]
