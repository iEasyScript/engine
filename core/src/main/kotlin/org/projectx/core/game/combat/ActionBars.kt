package org.projectx.core.game.combat

import world.gregs.voidps.gameval.Gameval

/**
 * A visible action-bar window. [currentBarVar] selects which of the 1..18 data bars it currently
 * displays; [slotBase] is component id of slot 1's primary drag layer (the +3 copy is also draggable);
 * [onScreenId] is the render-script (7681) window argument (CS2 `script11799` maps it to [interfaceId]).
 *
 * Desktop bars have a regular `slotBase + 13·k` slot layout. The mobile bars use irregular component
 * ids, so they supply [slotComponents] (componentId → 1-based slot) built from gameval names instead.
 */
data class ActionBarUi(
    val interfaceId: Int,
    val currentBarVar: Int,
    val slotBase: Int,
    val onScreenId: Int,
    val slotComponents: Map<Int, Int>? = null,
)

object ActionBars {
    private fun iface(name: String) = Gameval.requireId(Gameval.INTERFACE, name)

    const val SLOT_STRIDE = 13
    private const val COPY_OFFSET = 3

    private fun mobileSlots(iface: String): Map<Int, Int> = buildMap {
        for (slot in 1..ActionBarModel.SLOTS) {
            for (layer in arrayOf("graphic_", "button_target_layer_")) {
                Gameval.componentId("$iface:$layer$slot")?.let { put(it, slot) }
            }
        }
    }

    val bars: List<ActionBarUi> = listOf(
        ActionBarUi(iface("toplevel_v2_combat_bar"), CombatIds.CURRENT_BAR, slotBase = 66, onScreenId = 1003),
        ActionBarUi(iface("toplevel_v2_combat_bar2"), CombatIds.ADDITIONAL_BARS[0], slotBase = 21, onScreenId = 1032),
        ActionBarUi(iface("toplevel_v2_combat_bar3"), CombatIds.ADDITIONAL_BARS[1], slotBase = 19, onScreenId = 1033),
        ActionBarUi(
            iface("toplevel_v2_combat_bar_mobile_buttons"), CombatIds.MOBILE_BARS[0], slotBase = 0, onScreenId = 1043,
            slotComponents = mobileSlots("toplevel_v2_combat_bar_mobile_buttons"),
        ),
        ActionBarUi(
            iface("toplevel_v2_combat_bar_mobile_revo"), CombatIds.MOBILE_BARS[1], slotBase = 0, onScreenId = 1044,
            slotComponents = mobileSlots("toplevel_v2_combat_bar_mobile_revo"),
        ),
    )

    private val byInterface = bars.associateBy { it.interfaceId }

    fun forInterface(interfaceId: Int): ActionBarUi? = byInterface[interfaceId]

    /** Resolves a drag/click component to a 1-based slot: explicit [ActionBarUi.slotComponents] map when
     * present (mobile), else the regular `slotBase + 13·k` layout or its `+3` copy (desktop). */
    fun slotForComponent(bar: ActionBarUi, componentId: Int): Int? {
        bar.slotComponents?.let { return it[componentId] }
        val offset = componentId - bar.slotBase
        if (offset < 0) return null
        val local = offset % SLOT_STRIDE
        if (local != 0 && local != COPY_OFFSET) return null
        val slot = offset / SLOT_STRIDE + 1
        return slot.takeIf { it in 1..ActionBarModel.SLOTS }
    }
}
