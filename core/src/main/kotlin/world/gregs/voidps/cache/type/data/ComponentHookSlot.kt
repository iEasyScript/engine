package world.gregs.voidps.cache.type.data

object ComponentHookSlot {
    const val CLICK = 0
    const val CLICK_REPEAT = 1
    const val RELEASE = 2
    const val HOLD = 3
    const val MOUSE_OVER = 4
    const val MOUSE_REPEAT = 5
    const val MOUSE_LEAVE = 6
    const val DRAG = 7
    const val DRAG_COMPLETE = 8
    const val SCROLL_WHEEL = 9
    const val TARGET_ENTER = 15
    const val TARGET_LEAVE = 16
    const val VAR_TRANSMIT = 18
    const val INV_TRANSMIT = 19
    const val STAT_TRANSMIT = 20
    const val VARC_TRANSMIT = 21
    const val VARC_STRING_TRANSMIT = 22
    const val LOAD = 37
    const val TIMER = 38
    const val OP = 39
    const val OPT = 40
    const val CRM_VIEW_UPDATED = 54

    private val NAMES = mapOf(
        CLICK to "Click",
        CLICK_REPEAT to "Click repeat",
        RELEASE to "Release",
        HOLD to "Hold",
        MOUSE_OVER to "Mouse over",
        MOUSE_REPEAT to "Mouse repeat",
        MOUSE_LEAVE to "Mouse leave",
        DRAG to "Drag",
        DRAG_COMPLETE to "Drag complete",
        SCROLL_WHEEL to "Scroll wheel",
        TARGET_ENTER to "Target enter",
        TARGET_LEAVE to "Target leave",
        VAR_TRANSMIT to "Var transmit",
        INV_TRANSMIT to "Inventory transmit",
        STAT_TRANSMIT to "Stat transmit",
        VARC_TRANSMIT to "Varc transmit",
        VARC_STRING_TRANSMIT to "Varc string transmit",
        LOAD to "Load",
        TIMER to "Timer",
        OP to "Op",
        OPT to "Op target",
        CRM_VIEW_UPDATED to "Crm view updated",
    )

    fun name(slot: Int) = NAMES[slot] ?: "Hook $slot"
}
