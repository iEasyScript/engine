package com.projectx.game.interfaces

import com.projectx.game.bootstrap.Bootstrap
import org.projectx.core.game.combat.AbilityRegistry
import org.projectx.core.game.combat.AbilityType
import org.projectx.core.game.combat.ActionBarModel
import world.gregs.voidps.gameval.Gameval

fun parseAllActionBarAbilities(): Map<AbilityType, IFSlot> {
    val varps = Bootstrap.client.playerVarDomain
    return buildMap {
        for ((barLoc, barNum) in ActionBarModel.activeBars(varps)) {
            val slots = actionbarSlots[barLoc] ?: continue
            for (slot in 1..ActionBarModel.SLOTS) {
                val structId = ActionBarModel.resolveSlot(barNum, slot, varps).structId ?: continue
                val ifSlot = slots[slot] ?: continue
                put(AbilityRegistry[structId] ?: AbilityType(structId), ifSlot)
            }
        }
    }
}

/** Bar slots holding an item (potions, bombs, food) rather than an ability; pairs are (item id, slot). */
fun parseAllActionBarItems(): List<Pair<Int, IFSlot>> {
    val varps = Bootstrap.client.playerVarDomain
    return buildList {
        for ((barLoc, barNum) in ActionBarModel.activeBars(varps)) {
            val slots = actionbarSlots[barLoc] ?: continue
            for (slot in 1..ActionBarModel.SLOTS) {
                val resolved = ActionBarModel.resolveSlot(barNum, slot, varps)
                if (!resolved.isItem) continue
                val ifSlot = slots[slot] ?: continue
                add(resolved.obj to ifSlot)
            }
        }
    }
}

/**
 * Each bar's 14 slots, as the ability icon the player clicks (`graphic_N`). They are looked up by name so a client
 * update that shifts the bar's layout cannot leave clicks landing on the slot's background.
 */
val actionbarSlots: Map<Int, Map<Int, IFSlot>> by lazy {
    ACTION_BAR_INTERFACES.mapValues { (_, name) ->
        (1..ActionBarModel.SLOTS).associateWith { slot ->
            val hash = Gameval.requireComponentHash("$name:graphic_$slot")
            IFSlot(hash ushr 16, hash and 0xFFFF)
        }
    }
}

private val ACTION_BAR_INTERFACES = mapOf(
    1 to "toplevel_v2_combat_bar",
    2 to "toplevel_v2_combat_bar2",
    3 to "toplevel_v2_combat_bar3",
    4 to "toplevel_v2_combat_bar4",
    5 to "toplevel_v2_combat_bar5",
)
