# 2026-08-24 content update — what actually changed

Revision unchanged (**949.1**) — content-only, no client migration.
Method: field-level diff of decoded type data, pre-update `949-1_2026-08-23/types` vs
post-update `2026-08-24/types`, ids resolved through `re-resources/gamevals`.

> **Excluded as tooling noise:** 1,722 varbit records differ only by `domain: UNK -> CONTROLLER`.
> That is our own commit 58087c39 adding `VarControllerDecoder`, not Jagex. The real varbit change
> is 8 new player varbits on var_player indices 13487 and 13428.

## 1. Combat v2 tier tables extended to 255 — the biggest mechanical change

Every `combatv2` scaling table was extended past its old ceiling:

| enum | before | after |
|---|--:|--:|
| `combatv2_head/body/legs/gloves/boots/cape/ring_armour_100` (7 tables) | 100 | 256 |
| `combatv2_2h_speed4_damagevalues`, `combatv2_2h_ranged_speed4/5/6_damagevalues` | 99 | 255 |
| `combatv2_1h_speed4/5/6_damagevalues`, `combatv2_2h_speed5/6_damagevalues` | 200–201 | 255–256 |
| `combatv2_npc_armourvalues` | 150 | 305 |
| `combatv2_weapon_level` | 200 | 255 |

No entries were removed or altered — purely appended, so this is headroom for higher tiers rather
than a rebalance. New script 11545 gates on `struct.combatv2_ability_attack_dive`.

## 2. Construction / Player-Owned House rework

**650 new locs**, almost entirely POH furniture — 172 distinct names, ~224 placeholders
(`ph`/`PH`/null). Largest groups: Table 25, Altar 17, Armour Stand 15, Wardrobe 14, Kitchen
Cabinet 13, Double bed 13, Desk 12, Hedge 12, Chair 11, Flower planter 11, plus Throne, Portal
Nexus, Crafting Table, Bookcase, Workbench.

**105 changed locs**, all cosmetic/lighting: House portal and the city portals (Varrock, Lumbridge,
Falador, Camelot, Canifis) gained dynamic `lights`; the six altars (Oak/Teak/Cloth/Mahogany/
Limestone/Marble) got new `modelIds` + lights; treasure chests and armour cases got `ambient`
tweaks; incense burners `contrast`; Miscellania/Ports Table `options`.

**24 decoration items (63533–63577) converted from marketplace purchase to craftable.** Anniversary
architect statue/plushie/banner/plaque/display shield, party balloons, bunting, party cannons:

- `destroyondrop_mes`: *"…must be purchased from the marketplace"* -> *"…you will need to construct another."*
- `recipe_ingredient_type_1`: Teak plank (8780) -> real materials — Iron bar (2351), Leather (1741)
  + Thread (1734), Limestone brick (3420), Plank (960)
- `recipe_cycles` 5 -> 10; new `recipe_requirement_type_2: 67` paired with `quest_struct_quest: 1`
- tools unchanged: Hammer (2347) + Saw (8794); skill req and xp both type 22

Supporting changes: `poh_material_xp` gained Leather -> 100 and Thread -> 10; ~28 wooden furniture
items dropped `recipe_ingredient_number_2`; Beehive ingredient 33784 -> 33786; quests
**188 A Clockwork Syringe** and **398 You Are It** now require quest **532 "There's No Place Like
Home…"** (the POH tutorial).

## 3. Marketplace drop + Yak Track migration — largest by volume

**60 new items (63681–63740)** — Fatebound Champion set (Mask/Chestpiece/Legpiece/Gauntlets/Boots),
Shadowbound Champion set, Shadowbound Battlemage robes, Fatebound + Shadowbound weapons
(wand/book/staff/longsword+offhand/longbow), bundles, and pets.

**36 new structs (3656–3691)**, all `mtxmgt_*`, catalogue paths `rs:outfits:zarosian_champion`,
`rs:outfits:zarosian_shadow_champion`, `rs:outfits:zarosian_battlemage`, `release_date` 8944.
Plus 50 new marketplace db-rows and 3 new grouping enums (13618/13619/13620).

**58 changed structs — Yak Track rewards moved to the Marketplace.** Vagabond Knight outfit,
Ancient Necronium outfit and the three pets (Dark Beast, Poison Spider Red/Green) flipped
`mtxmgt_source` **9 -> 1**, `mtxmgt_source_custom` *"obtained from the Expedition to the Wilderness
Yak Track"* -> *"obtained from the Marketplace"*, `withdraw_date` cleared, and gained
`mtxmgt_pos_string` catalogue paths. Items 63719–63740 are their new bundle/pet entries.

147 marketplace db-rows changed; the date column rotated 8937 -> 8944 (+7 — a weekly rotation; the
epoch behind these day-codes is not established, so no calendar date is asserted here).
`[proc,mtxmgt_check_available]` (script 6488) changed. Featured/carousel enums rotated.

## 4. Leagues

Interface **1442 `league_parent_ranks` rebuilt, 20 -> 37 components** — a reward-account nomination
flow. New script 11653 builds slot 28 with *"Nominate a **DIFFERENT** account to receive your League
rewards for this league onwards."*; new script 15760 drives a 5-second "Confirm" countdown.
`[clientscript,league_parent_progress_bar_tasks]` (21081) changed.

Locality unlock costs cut in `league_1_locality_unlocks`: 175->150, 300->275, 450->400.
`league_1_task_minor_to_major_locality` +2 entries.

Six league achievements changed: maple **shieldbow -> longbow** wording (4366, 4367, 4584) and skill
requirements shifted (4331 Prayer 50 -> 5; 4332 Hunter 28 -> Prayer 50). Related: items 48/49
"Longbow (unstrung)" **cost 1920 -> 60**.

## 5. Cooking

Full kettle (7690) gained `cook_require`; Hot kettle (7691) gained a full recipe
(ingredient 7690 x1, requirement type 16 level 1). `cooking_other_fire_table` and
`cooking_other_range_table` each gained an entry.

## 6. Assets

+50 models, +86 archives in each of the four texture formats, +32 sprites, +1 animation skeleton,
+1 anim-keyframe, +35 material definitions, +13 archives in index 61. Routine.

## Gameval component drift — one real defect, and it is not new

Verified by decoding the **beta** cache's index 3 and comparing component shape keys slot-by-slot
against the cache we now serve — not by trusting the aligner's summary.

| interface | named slots landing on a same-shaped component | verdict |
|---|---|---|
| 1311 `mtxmgt` | **698 / 698** | safe |
| 1361 `league_child_tasks` | **22 / 25** | safe — the 3 misses are pure height resizes (58->88, 52->86, 97->125) at the same type and position |
| 1442 `league_parent_ranks` | **1 / 16** | **broken** |

`league_parent_ranks` is the one that matters. The aligner reported it as NOT aligned (server 37 vs
beta 16, 25% shape agreement) and correctly refused to remap it — so no component id moved in our
gamevals, but 15 of its 16 names address the wrong component. Only `progress_bar` is right.

**Blast radius, measured in our own CS2 dump: 13 of 14 name-resolved `league_parent_ranks.*`
references are wrong.** For example script 17356 decompiles to
`if_sethide(true, component(league_parent_ranks.divider_title))` — that name now points elsewhere.

This predates this update (beta 16 vs pre-update live 20 already disagreed) but the update
compounded it (20 -> 37). Alignment cannot fix it; it needs a beta re-dump once beta rebuilds the
interface — the `gameval-beta-check` skill.

Separately, none of the genuinely new content has gameval names at all (0/60 new items,
0/686 new loc ids), because index 67 is beta-only and our beta dump is from 2026-07-27.
