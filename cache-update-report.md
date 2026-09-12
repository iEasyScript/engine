# Cache diff

`949-5_pre_2026-09-06` → `950-1_2026-09-07`

## Archive identity

| idx | label | archives | added | removed | changed | ver-only |
|----:|-------|---------:|------:|--------:|--------:|---------:|
| 2 | configs | 39 | 0 | 0 | 6 | 0 |
| 3 | interfaces | 1883 | 0 | 0 | 3 | 0 |
| 5 | maps | 8675 | 0 | 0 | 17 | 0 |
| 12 | client-scripts | 21097 → 21098 | 1 | 0 | 21097 | 0 |
| 16 | objects | 548 | 0 | 0 | 424 | 0 |
| 17 | enums | 69 | 0 | 0 | 1 | 0 |
| 18 | npcs | 257 | 0 | 0 | 235 | 0 |
| 19 | items | 249 | 0 | 0 | 7 | 0 |
| 20 | animations | 298 | 0 | 0 | 4 | 0 |
| 22 | structs | 1664 | 0 | 0 | 5 | 0 |
| 23 | world-map | 5 | 0 | 0 | 1 | 0 |
| 24 | quick-chat | 2 | 0 | 0 | 1 | 0 |
| 41 | world-map-areas | 771 | 0 | 0 | 2 | 0 |
| 47 | models-rt7 | 145735 | 0 | 0 | 12 | 0 |
| 49 | dbtableindex | 261 | 0 | 0 | 2 | 0 |
| 57 | achievement-def | 40 | 0 | 0 | 39 | 0 |

**16 of 45 indices changed** — 1 archives added, 0 removed, 21856 modified.

## index 2 — configs
- changed: 5,35,36,41,46,60
- **file count changed** (2):
  - 41: 20028 → 20031 (+3)
  - 60: 13269 → 13270 (+1)

## index 3 — interfaces
- changed: 675,1406,1475
- **component count changed** (1):
  - 1475: 60 → 59 (-1)
- ⚠ **gameval component slots may have moved.** A changed component count proves a shift,
  but is not required for one: an interface can keep its count and still reorder slots.
  Every one of the 3 changed interfaces needs the shape alignment to rule drift in or out —
  re-run `gamevalExport --align` and read its summary, do not trust counts alone.

## index 5 — maps
- changed: 4030,4031,4032,4158,4159,4160,4286,4287,4288,4414,4415,4416 … (+5)

## index 12 — client-scripts
- added: 20353
- changed: 0,1,2,3,4,5,6,7,8,9,10,11 … (+21085)

## index 16 — objects
- changed: 0,2,3,4,6,7,8,9,10,11,12,13 … (+412)

## index 17 — enums
- changed: 3

## index 18 — npcs
- changed: 0,1,2,3,4,5,6,7,8,9,10,11 … (+223)

## index 19 — items
- changed: 12,68,69,70,107,236,239

## index 20 — animations
- changed: 125,196,236,260

## index 22 — structs
- changed: 94,151,152,660,725

## index 23 — world-map
- changed: 4

## index 24 — quick-chat
- changed: 1

## index 41 — world-map-areas
- changed: 624,770

## index 47 — models-rt7
- changed: 129608,129609,137144,137145,137146,138695,145517,145522,145523,145525,145533,145537

## index 49 — dbtableindex
- changed: 285,334

## index 57 — achievement-def
- changed: 1,2,3,4,5,6,7,8,9,10,11,12 … (+27)
