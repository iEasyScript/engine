# Instance (dynamic region) collision — rebuilt, never read

⛔ **The RS3 NXT client keeps NO walk-collision grid.** Movement is server-authoritative via GPI; there
is no collision/clip/pathfind code to read. Any engine that needs collision must **rebuild** it. Byte
the region-rebuild packet's layout is in the Ghidra DB — never copied into docs.

## Where instance walls actually come from

⛔ **Instance walls are STATIC SOURCE-CACHE locs, not live locs.** The visible wall objects inside a
dungeon are **decorative** — `clipType 0`, `blocks false` — so the clip pass skips them entirely and a
live-loc-only rebuild produces **zero wall collision**. The real walls are the **source template
mapsquare's locs**, rotated into the virtual zone (exactly what the server does when it copies an
instance zone: floor tile flags *and* the source objects). Reading only the source **tile flags** gives
whole-tile floor blocks but leaves the wall edges between walkable tiles open — which is how a bot ends
up pathing straight through walls toward unreachable doors.

Three sources, layered:

1. **Static floor + static locs** from the region-rebuild packet: per virtual zone `{srcMapSquare,
   srcLocalZone, level, rotation}` → rotate the source tile flags into blocked tiles **and** rotate the
   source mapsquare's objects into real clipped locs. A void zone blocks all 64 tiles. This is the
   primary wall source.
2. **Dynamic live locs** layered on top for doors and debris. Doors are per-dungeon randomized dynamic
   scenery and are *not* in the template, so applying all static source locs does not re-block an opened
   door.
3. **Single-loc mutations**, which all funnel through one client function (`ApplyLocChange`) carrying an
   absolute-world loc record with an add/delete/anim kind. Hook it and mark the affected zone dirty —
   a spawned solid loc is otherwise never clipped (nothing re-scans after the initial settle), and a
   removed one is never unclipped. ⛔ Re-clip **dirty zones only, grouped by mapsquare** — never iterate
   the whole scene per tick.

Clip rules to mirror: shapes 0–3 are **edge** walls, 9–21 are **solid** whole-tile (both only when
`clipType != 0`), and shape 22 ground decoration is solid only when `clipType == 1` — so most decorative
ground objects do not clip at all.

⛔ **Known caveat:** injecting *mid*-dungeon means the rebuild packet for already-revealed rooms was
never seen, so those zones have neither floor nor walls; only live scenery clips. It self-heals when a
door opens or a room is revealed (the packet is re-sent). Validate in a fresh room.

## Two traps

- **Zone counts are unsigned bytes — up to 255 per axis.** A quest-portal instance sent a 24×24-zone
  region; a `width in 1..16` sanity guard silently dropped the whole packet and no collision was ever
  built. Do not cap at 16, and keep the build-area span guard wide enough to cover a maximum region.
- ⛔ **Water is a per-tile RENDER flag, not a collision flag and not the water mesh.** The shared
  floor/blockwalk collision flag is set for water *and* for other blocked ground, so it is a superset,
  not a water test. The map's water-patch section is placed 3D meshes (position/rotation/scale/mesh id)
  with no per-tile bit — deriving "water tiles" from its extent is completely wrong.

## Falsified — do not re-investigate

The client's terrain link map is terrain-only (bridge/roof/level) and its grid is **empty inside
instances**. The mapsquare's node vector is render geometry, not tile descriptors; the descriptor grid is
transient. `REBUILD_NORMAL` is **not** the instance map (it is GPI absolute-position reinit) — the
instance map is the separate region-rebuild packet.

[[engine-architecture]] [[protocol-invariants]]
