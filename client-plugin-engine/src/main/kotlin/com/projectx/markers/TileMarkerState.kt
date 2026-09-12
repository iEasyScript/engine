package com.projectx.markers

import com.projectx.ui.backend.dsl.boolState
import com.projectx.ui.backend.dsl.floatState
import com.projectx.ui.backend.dsl.intState
import com.projectx.ui.backend.dsl.stringState
import com.projectx.ui.backend.dsl.utils.ImGuiColors

object TileMarkerState {
    /**
     * The tile tool. While true, a fullscreen capture surface owns the mouse so clicks
     * place/edit markers instead of reaching the game (no walking, no native menu).
     */
    val toolActive = boolState(false)

    /** Group new marks, recolors, and bulk adds are applied to. */
    val activeGroup = stringState("", 64)

    /** Active color as editable RGB (0..1) for the color picker; packed via [activeColor]. Cyan. */
    val colorR = floatState(0f)
    val colorG = floatState(1f)
    val colorB = floatState(1f)

    fun activeColor(): Int = ImGuiColors.rgba(colorR.value, colorG.value, colorB.value)

    /** Loads a packed IM_COL32 color back into the picker (e.g. clicking a group's swatch). */
    fun loadPickerColor(packed: Int) {
        colorR.value = (packed and 0xFF) / 255f
        colorG.value = ((packed ushr 8) and 0xFF) / 255f
        colorB.value = ((packed ushr 16) and 0xFF) / 255f
    }

    val newGroupName = stringState("", 64)

    /** Bulk-rectangle corners (tile coords). */
    val bulkX1 = intState(0)
    val bulkY1 = intState(0)
    val bulkX2 = intState(0)
    val bulkY2 = intState(0)
}
