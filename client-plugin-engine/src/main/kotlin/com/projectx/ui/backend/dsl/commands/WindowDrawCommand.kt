package com.projectx.ui.backend.dsl.commands

import com.projectx.ui.backend.native.NativeBridge
import java.lang.foreign.MemorySegment

/**
 * Escape hatch for custom drawing into the current window's draw list with its live geometry, so a
 * caller can lay out against the window the user drags and resizes. [draw] runs on the render
 * thread — capture immutable data only, never live game state.
 */
data class WindowDrawCommand(
    val draw: (drawList: MemorySegment, x: Float, y: Float, width: Float, height: Float) -> Unit
) : ImGuiCommand() {
    override fun execute() {
        val drawList = NativeBridge.getWindowDrawList() ?: return
        val (x, y) = NativeBridge.getWindowPos()
        val (w, h) = NativeBridge.getWindowSize()
        draw(drawList, x, y, w, h)
    }
}
