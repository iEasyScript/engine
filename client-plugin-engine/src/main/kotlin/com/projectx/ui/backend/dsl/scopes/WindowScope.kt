package com.projectx.ui.backend.dsl.scopes

import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.commands.CloseCurrentPopupCommand
import com.projectx.ui.backend.dsl.commands.ImGuiCommand
import com.projectx.ui.backend.dsl.commands.OpenPopupCommand
import com.projectx.ui.backend.dsl.commands.WindowDrawCommand
import com.projectx.ui.backend.flags.PopupFlags
import java.lang.foreign.MemorySegment

@ImGuiDsl.ImGuiDsl
class WindowScope : LayoutScope {
    override val commands = mutableListOf<ImGuiCommand>()

    /** Custom drawing into this window's draw list with live geometry - see [WindowDrawCommand]. */
    fun windowDraw(draw: (drawList: MemorySegment, x: Float, y: Float, width: Float, height: Float) -> Unit) {
        commands.add(WindowDrawCommand(draw))
    }

    fun openPopup(id: String, flags: PopupFlags = PopupFlags.None) {
        commands.add(OpenPopupCommand(id, flags.value))
    }
    
    fun closeCurrentPopup() {
        commands.add(CloseCurrentPopupCommand())
    }
}