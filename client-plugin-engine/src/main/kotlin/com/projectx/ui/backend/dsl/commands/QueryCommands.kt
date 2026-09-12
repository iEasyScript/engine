package com.projectx.ui.backend.dsl.commands

import com.projectx.ui.backend.native.NativeBridge
import java.util.concurrent.atomic.AtomicReference

data class IsItemHoveredCommand(val result: AtomicReference<Boolean>) : ImGuiCommand() {
    override fun execute() {
        result.set(NativeBridge.isItemHovered())
    }
}

data class IsItemClickedCommand(
    val mouseButton: Int = 0,
    val result: AtomicReference<Boolean>
) : ImGuiCommand() {
    override fun execute() {
        result.set(NativeBridge.isItemClicked(mouseButton))
    }
}

data class SetScrollHereYCommand(val ratio: Float) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.setScrollHereY(ratio)
    }
}

/**
 * Keeps the view pinned to the bottom only while it is already there, so a reader who scrolled up is
 * not yanked back down. Both the query and the decision have to happen here: commands are recorded a
 * whole frame before they run, so a scope helper that queued a scroll query and read its result would
 * only ever see the pre-execution default.
 */
object ScrollToBottomIfPinnedCommand : ImGuiCommand() {
    override fun execute() {
        if (NativeBridge.getScrollY() >= NativeBridge.getScrollMaxY()) {
            NativeBridge.setScrollHereY(1.0f)
        }
    }
}
