package com.projectx.script.impl.devin.aiobozocombat

import com.projectx.game.interfaces.actionbarSlots
import com.projectx.script.api.interfaces
import world.gregs.voidps.gameval.Gameval

/**
 * Resolves the keybind label the game itself draws on a bar slot, for every bar rather than just the
 * main one.
 *
 * The label component is located by dev-name instead of a fixed offset from the slot's click target,
 * because the offset is not constant across bars — bar 1 puts the label 9 components after the
 * anchor, bar 2 puts it 7.
 */
object BarLayout {
    private val interfaceNames = mapOf(
        1430 to "toplevel_v2_combat_bar",
        1670 to "toplevel_v2_combat_bar2",
        1671 to "toplevel_v2_combat_bar3",
        1672 to "toplevel_v2_combat_bar4",
        1673 to "toplevel_v2_combat_bar5"
    )

    private val slotByComponent: Map<Long, Pair<Int, Int>> by lazy {
        buildMap {
            for ((barLoc, slots) in actionbarSlots) {
                for ((slot, ifSlot) in slots) put(key(ifSlot.interfaceId, ifSlot.componentId), barLoc to slot)
            }
        }
    }

    /** Bar number and slot, for ordering rows the way the bars are actually laid out on screen. */
    fun location(interfaceId: Int, componentId: Int): Pair<Int, Int>? =
        slotByComponent[key(interfaceId, componentId)]

    private val namedComponents = HashMap<String, Int>()

    fun keybind(interfaceId: Int, componentId: Int): String? {
        val label = named(interfaceId, componentId, "text") ?: return null
        return runCatching {
            interfaces.getComponent(interfaceId, label)?.text?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * The graphic the client is currently drawing in the slot. Authoritative by construction — it is
     * what the player is looking at — where the ability struct's icon param resolves to nothing for
     * a good third of abilities.
     */
    fun iconGraphic(interfaceId: Int, componentId: Int): Int {
        val graphic = named(interfaceId, componentId, "graphic") ?: return -1
        return runCatching { interfaces.getComponent(interfaceId, graphic)?.graphicId ?: -1 }.getOrDefault(-1)
    }

    /**
     * The slot's clickable button.
     *
     * The engine's `actionbarSlots` table anchors on the wrong component for the current layout — bar 1
     * on `prayer_bg_1`, bar 2 on `graphic_1` — so a click lands on a background or graphic layer rather
     * than the button. The inconsistent direction of the error (+1 and −1) marks it as a hardcoded
     * table left behind by an interface change, which is why ability presses stopped working while
     * prayer clicks, which use a different interface, still do.
     */
    fun buttonComponent(interfaceId: Int, componentId: Int): Int? =
        named(interfaceId, componentId, "button")

    fun describe(interfaceId: Int, componentId: Int): String {
        val (bar, slot) = location(interfaceId, componentId) ?: return "?"
        return "$bar.$slot"
    }

    /** Resolves a sibling component of the same slot by its dev-name role, e.g. `text` or `graphic`. */
    @Synchronized
    private fun named(interfaceId: Int, componentId: Int, role: String): Int? {
        val (_, slot) = location(interfaceId, componentId) ?: return null
        val prefix = interfaceNames[interfaceId] ?: return null
        val name = "$prefix:${role}_$slot"
        val resolved = namedComponents.getOrPut(name) { Gameval.componentId(name) ?: -1 }
        return resolved.takeIf { it >= 0 }
    }

    private fun key(interfaceId: Int, componentId: Int): Long =
        (interfaceId.toLong() shl 32) or componentId.toLong()
}
