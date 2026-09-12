package com.projectx.game.interfaces

import com.projectx.game.bootstrap.Bootstrap
import org.projectx.core.game.combat.AbilityRegistry
import org.projectx.core.game.combat.AbilityType
import org.projectx.core.game.combat.ActionBarModel

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

val actionbarSlots = mapOf(
    1 to mapOf(
        1 to IFSlot(1430, 64),
        2 to IFSlot(1430, 77),
        3 to IFSlot(1430, 90),
        4 to IFSlot(1430, 103),
        5 to IFSlot(1430, 116),
        6 to IFSlot(1430, 129),
        7 to IFSlot(1430, 142),
        8 to IFSlot(1430, 155),
        9 to IFSlot(1430, 168),
        10 to IFSlot(1430, 181),
        11 to IFSlot(1430, 194),
        12 to IFSlot(1430, 207),
        13 to IFSlot(1430, 220),
        14 to IFSlot(1430, 233)
    ),
    2 to mapOf(
        1 to IFSlot(1670, 21),
        2 to IFSlot(1670, 34),
        3 to IFSlot(1670, 47),
        4 to IFSlot(1670, 60),
        5 to IFSlot(1670, 73),
        6 to IFSlot(1670, 86),
        7 to IFSlot(1670, 99),
        8 to IFSlot(1670, 112),
        9 to IFSlot(1670, 125),
        10 to IFSlot(1670, 138),
        11 to IFSlot(1670, 151),
        12 to IFSlot(1670, 164),
        13 to IFSlot(1670, 177),
        14 to IFSlot(1670, 190)
    ),
    3 to mapOf(
        1 to IFSlot(1671, 19),
        2 to IFSlot(1671, 32),
        3 to IFSlot(1671, 45),
        4 to IFSlot(1671, 58),
        5 to IFSlot(1671, 71),
        6 to IFSlot(1671, 84),
        7 to IFSlot(1671, 97),
        8 to IFSlot(1671, 110),
        9 to IFSlot(1671, 123),
        10 to IFSlot(1671, 136),
        11 to IFSlot(1671, 149),
        12 to IFSlot(1671, 162),
        13 to IFSlot(1671, 175),
        14 to IFSlot(1671, 188)
    ),
    4 to mapOf(
        1 to IFSlot(1672, 16),
        2 to IFSlot(1672, 29),
        3 to IFSlot(1672, 42),
        4 to IFSlot(1672, 55),
        5 to IFSlot(1672, 68),
        6 to IFSlot(1672, 81),
        7 to IFSlot(1672, 94),
        8 to IFSlot(1672, 107),
        9 to IFSlot(1672, 120),
        10 to IFSlot(1672, 133),
        11 to IFSlot(1672, 146),
        12 to IFSlot(1672, 159),
        13 to IFSlot(1672, 172),
        14 to IFSlot(1672, 185)
    ),
    5 to mapOf(
        1 to IFSlot(1673, 16),
        2 to IFSlot(1673, 29),
        3 to IFSlot(1673, 42),
        4 to IFSlot(1673, 55),
        5 to IFSlot(1673, 68),
        6 to IFSlot(1673, 81),
        7 to IFSlot(1673, 94),
        8 to IFSlot(1673, 107),
        9 to IFSlot(1673, 120),
        10 to IFSlot(1673, 133),
        11 to IFSlot(1673, 146),
        12 to IFSlot(1673, 159),
        13 to IFSlot(1673, 172),
        14 to IFSlot(1673, 185)
    )
)
