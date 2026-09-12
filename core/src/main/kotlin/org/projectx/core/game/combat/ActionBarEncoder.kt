package org.projectx.core.game.combat

import org.projectx.core.model.Vars

sealed interface SlotBinding
data class AbilitySlot(val structId: Int) : SlotBinding
data class ItemSlot(val itemId: Int, val type: Int = 0, val id: Int = 0) : SlotBinding
data object EmptySlot : SlotBinding

data class ActionBarLayout(
    val bar: Int,
    val slots: Map<Int, SlotBinding>,
    val makeCurrent: Boolean = false,
)

object ActionBarEncoder {
    fun write(vars: Vars, layout: ActionBarLayout, save: Boolean = true) {
        for ((slot, binding) in layout.slots) {
            ActionBar.write(vars, layout.bar, slot, binding, save)
        }
        if (layout.makeCurrent) vars.setVarBit(CombatIds.CURRENT_BAR, layout.bar, save = save)
    }
}
