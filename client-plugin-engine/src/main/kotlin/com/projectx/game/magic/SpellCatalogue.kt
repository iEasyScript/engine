package com.projectx.game.magic

import com.projectx.script.api.Spellbook
import com.projectx.util.FeatureIds
import org.projectx.core.game.combat.CombatIds
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval
import world.gregs.voidps.type.Tile

/** Every spell in the game's magic ability list, read from its struct: name, book, Magic level and runes. */
internal object SpellCatalogue {
    private const val EVERY_BOOK = 3

    private class Params(val book: Int, val destination: Int)

    private val params = FeatureIds("SpellCatalogue") {
        Params(Gameval.requireId(Gameval.PARAM, "combatv2_which_spellbook"), Gameval.requireId(Gameval.PARAM, "combatv2_teleport_destination"))
    }

    @Volatile
    private var byAbility: Map<Int, SpellInfo>? = null

    @Volatile
    private var abilityByStruct: Map<Int, Int>? = null

    fun info(abilityId: Int): SpellInfo? = spells()[abilityId]

    /** The magic ability id of an action-bar struct, or null when it is not a spell. */
    fun abilityIdOfStruct(structId: Int): Int? {
        abilityByStruct?.let { return it[structId] }
        val all = spells()
        if (all.isEmpty()) return null
        return all.values.associate { it.structId to it.abilityId }.also { abilityByStruct = it }[structId]
    }

    /** The Spellbook Swap on [from] that switches to [to], e.g. "Spellbook Swap (Standard)" on the Lunar book. */
    fun swap(from: Spellbook, to: Spellbook): SpellInfo? {
        val wanted = "Spellbook Swap (${to.name.lowercase().replaceFirstChar(Char::uppercaseChar)})"
        return spells().values.firstOrNull { it.book == from && it.name.equals(wanted, ignoreCase = true) }
    }

    fun destination(abilityId: Int): Tile? {
        val key = params.get()?.destination ?: return null
        val packed = info(abilityId)?.let { Cache.struct(it.structId) }?.getIntValue(key, 0) ?: return null
        if (packed <= 0) return null
        val (plane, x, y) = SpellChecks.unpackCoord(packed)
        return Tile.of(x, y, plane)
    }

    private fun spells(): Map<Int, SpellInfo> {
        byAbility?.let { return it }
        val keys = params.get() ?: return emptyMap()
        val magic = Cache.enum(CombatIds.ENUM_MAGIC) ?: return emptyMap()
        val out = HashMap<Int, SpellInfo>()
        for ((abilityId, value) in magic.values) {
            val structId = value as? Int ?: continue
            val struct = Cache.struct(structId) ?: continue
            val name = struct.getStringValue(CombatIds.ABILITY_NAME) ?: continue
            val book = when (struct.getIntValue(keys.book, -1)) {
                0 -> Spellbook.STANDARD
                1 -> Spellbook.ANCIENT
                2 -> Spellbook.LUNAR
                EVERY_BOOK -> null
                else -> continue
            }
            val runes = CarriedRunes.required { struct.getIntValue(it, 0) } ?: return emptyMap()
            out[abilityId] = SpellInfo(abilityId, structId, name, book, struct.getIntValue(CombatIds.ABILITY_LEVEL_REQ, 0), runes)
        }
        return out.also { byAbility = it }
    }
}
