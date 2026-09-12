package org.projectx.core.game.combat

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval

data class SlotVarRefs(val typeVarbit: Int, val idVarbit: Int, val objVar: Int)

data class ResolvedSlot(val type: Int, val id: Int, val obj: Int, val structId: Int?, val isItem: Boolean)

data class SlotRef(val type: Int, val id: Int)

object ActionBarModel {
    const val TEMP_BAR = 31
    const val SLOTS = 14
    const val OBJ_EMPTY = -1

    private val TYPE_TO_ENUM: Map<Int, Int> by lazy {
        mapOf(
            1 to CombatIds.ENUM_MELEE,
            3 to CombatIds.ENUM_DEFENCE,
            4 to CombatIds.ENUM_CONSTITUTION,
            5 to CombatIds.ENUM_RANGED,
            6 to CombatIds.ENUM_MAGIC,
            7 to CombatIds.ENUM_PRAYER,
            9 to CombatIds.ENUM_EMOTES,
            11 to CombatIds.ENUM_ANCIENT,
            13 to CombatIds.ENUM_SUMMONING,
            17 to CombatIds.ENUM_NECROMANCY,
        )
    }

    private val TYPE_TO_STRUCT: Map<Int, Int> by lazy {
        mapOf(
            8 to CombatIds.STRUCT_SPECIAL_ATTACK,
            12 to CombatIds.STRUCT_HP_BUTTON,
            14 to CombatIds.STRUCT_WORN_SLOT,
            16 to CombatIds.STRUCT_AUTO_RETALIATE,
            18 to CombatIds.STRUCT_OVERHEAD_EMOTE,
        )
    }

    private val STYLE_PRIORITY = intArrayOf(1, 5, 6, 17, 7, 3, 4, 11, 13, 9)

    val slotVarRefs: Map<Pair<Int, Int>, SlotVarRefs> by lazy {
        buildMap {
            for (bar in (1..18) + TEMP_BAR) {
                val prefix = if (bar == TEMP_BAR) "actionbar_temp" else "actionbar$bar"
                for (slot in 1..SLOTS) {
                    val type = Gameval.id(Gameval.VARBIT, "${prefix}_int${slot}_type") ?: continue
                    val id = Gameval.id(Gameval.VARBIT, "${prefix}_int${slot}_id") ?: continue
                    val obj = Gameval.id(Gameval.VAR_PLAYER, "${prefix}_obj${slot}") ?: continue
                    put(bar to slot, SlotVarRefs(type, id, obj))
                }
            }
        }
    }

    val refForStruct: Map<Int, SlotRef> by lazy {
        val out = HashMap<Int, SlotRef>()
        for (type in STYLE_PRIORITY.reversed()) {
            val enumId = TYPE_TO_ENUM[type] ?: continue
            Cache.enum(enumId)?.values?.forEach { (id, struct) ->
                (struct as? Int)?.let { out[it] = SlotRef(type, id) }
            }
        }
        for ((type, struct) in TYPE_TO_STRUCT) out.putIfAbsent(struct, SlotRef(type, 0))
        out
    }

    fun enumForType(type: Int): Int? = TYPE_TO_ENUM[type]

    fun resolveSlot(bar: Int, slot: Int, varps: VarReader): ResolvedSlot {
        val refs = slotVarRefs[bar to slot] ?: return ResolvedSlot(0, 0, OBJ_EMPTY, null, false)
        val type = varps.getVarBit(refs.typeVarbit)
        val id = varps.getVarBit(refs.idVarbit)
        val obj = varps.getVar(refs.objVar)
        if (obj != OBJ_EMPTY) return ResolvedSlot(type, id, obj, null, isItem = true)
        if (type == 0) return ResolvedSlot(type, id, obj, null, false)
        val resolved = when {
            type in TYPE_TO_ENUM -> Cache.enum(TYPE_TO_ENUM.getValue(type))?.values?.get(id) as? Int
            type in TYPE_TO_STRUCT -> TYPE_TO_STRUCT[type]
            else -> null
        } ?: return ResolvedSlot(type, id, obj, null, false)
        val transformed = AbilityTransform.apply(resolved, varps)
        val valid = if (Cache.struct(transformed) != null) transformed else null
        return ResolvedSlot(type, id, obj, valid, false)
    }

    fun activeBars(varps: VarReader): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(5)
        val main = varps.getVarBit(CombatIds.CURRENT_BAR)
        if (main > 0) out += 1 to main
        CombatIds.ADDITIONAL_BARS.forEachIndexed { index, varbit ->
            val bar = varps.getVarBit(varbit)
            if (bar > 0) out += (index + 2) to bar
        }
        return out
    }
}
