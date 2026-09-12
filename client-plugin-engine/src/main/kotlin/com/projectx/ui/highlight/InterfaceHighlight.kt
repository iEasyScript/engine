package com.projectx.ui.highlight

import com.projectx.game.interfaces.IFSlot
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import java.util.concurrent.ConcurrentHashMap

object InterfaceHighlight {
    internal val entries: MutableMap<String, Entry> = ConcurrentHashMap()

    fun add(key: String, slot: IFSlot, style: HighlightStyle = HighlightStyle.DEFAULT) {
        entries[key] = Entry(slot, style)
    }

    fun add(
        key: String,
        slot: IFSlot,
        color: Int = ImGuiColors.CYAN,
        label: String? = null,
        pulse: Boolean = true,
        chevron: Boolean = true,
    ) {
        entries[key] = Entry(slot, HighlightStyle(color = color, label = label, pulse = pulse, chevron = chevron))
    }

    fun remove(key: String) { entries.remove(key) }

    fun clear() { entries.clear() }

    fun isHighlighted(key: String): Boolean = entries.containsKey(key)

    internal data class Entry(val slot: IFSlot, val style: HighlightStyle)
}

data class HighlightStyle(
    val color: Int = ImGuiColors.CYAN,
    val label: String? = null,
    val pulse: Boolean = true,
    val chevron: Boolean = true,
    val thickness: Float = 2.5f,
) {
    companion object { val DEFAULT = HighlightStyle() }
}
