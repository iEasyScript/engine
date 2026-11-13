package com.projectx.webwalker

import com.projectx.game.items.Item
import com.projectx.util.FeatureIds
import world.gregs.voidps.gameval.Gameval

/**
 * The Passage of the Abyss, as the planner's `<Colour>POA_<Jewellery>` keys read it: for each passage carried, one key
 * per jewellery set in its slots, valued at its charges (0 when it holds one or none). Every one opens a menu.
 */
internal object AbyssPassage {
    // Six 4-bit fields of the passage's own item var, one per slot, and its charge count.
    private val SLOT_VARBITS = intArrayOf(42932, 42933, 42934, 42935, 42936, 42937)
    private const val CHARGES_VARBIT = 42945

    private val COLOURS = mapOf(
        "alch_onyx_teleport" to "Red",
        "alch_onyx_teleport_purple" to "Purple",
        "alch_onyx_teleport_green" to "Green",
        "alch_onyx_teleport_yellow" to "Yellow",
    )

    // The planner's names for a slot's value; 3 and 11 are both the ring of duelling there.
    private val JEWELLERY = mapOf(
        2 to "GamesNecklace",
        3 to "RingOfDuelling",
        5 to "SkillsNecklace",
        6 to "RingOfSlaying",
        7 to "AmuletOfGlory",
        8 to "CombatBracelet",
        9 to "DigSitePendant",
        10 to "RingOfRespawn",
        11 to "RingOfDuelling",
        12 to "TravellersNecklace",
        13 to "DelversAnklet",
    )

    private val ids = FeatureIds("AbyssPassage") { COLOURS.mapKeys { Gameval.requireId(Gameval.OBJ, it.key) } }

    // The slot and charge vars were read from the cache's layout, not from a passage in hand; each passage's reading is
    // logged once so a report from one shows whether they are right.
    private val logged = HashSet<String>()

    /** Every key the passages carried give, with its value. */
    fun keys(backpack: List<Item>, worn: List<Item>): Map<String, Int> {
        val colours = ids.get() ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (passage in backpack + worn) {
            val colour = colours[passage.id] ?: continue
            val vars = passage.varDomain ?: continue
            val charges = vars.getVarBit(CHARGES_VARBIT)
            val value = if (charges > 1) charges else 0
            val slots = SLOT_VARBITS.map { vars.getVarBit(it) }
            for (raw in slots) {
                val jewellery = JEWELLERY[raw] ?: continue
                out.putIfAbsent("${colour}POA_$jewellery", value)
            }
            val reading = "$colour passage: slots $slots, charges $charges"
            if (logged.add(reading)) println("[WebWalk] Passage of the Abyss read as $reading")
        }
        return out
    }
}
