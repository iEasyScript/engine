package com.projectx.script.api

import com.projectx.game.interfaces.IFSlot
import com.projectx.util.FeatureIds
import com.projectx.util.componentIdFromHash
import com.projectx.util.interfaceIdFromHash
import world.gregs.voidps.gameval.Gameval

private class ExtraActionIds(val button: IFSlot)

private fun extraActionComponent(name: String): Int =
    Gameval.requireComponentHash("toplevel_v2_extra_action_button:$name")

private val extraActionIds = FeatureIds("Extra action button") {
    val click = extraActionComponent("click_layer")
    ExtraActionIds(IFSlot(interfaceIdFromHash(click), componentIdFromHash(click)))
}

/**
 * Whether the extra action button is on screen: the encounter-specific button some fights show beside the action
 * bar, such as Igneous Vengeance against TzKal-Zuk.
 */
val extraActionVisible: Boolean
    get() {
        // The button's interface only loads while an action is on offer, and its icon carries no text.
        val ids = extraActionIds.get() ?: return false
        return interfaces.getComponent(ids.button.interfaceId, ids.button.componentId)?.visible == true
    }

/** Presses the extra action button; false, without pressing, when it is not on screen. Confirm on the fight's own outcome. */
fun clickExtraAction(): Boolean {
    val ids = extraActionIds.get() ?: return false
    return extraActionVisible && ids.button.click(1)
}
