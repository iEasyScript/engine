package com.projectx.ui.backend.dsl.scopes

import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.commands.ImGuiCommand

@ImGuiDsl.ImGuiDsl
class ChildScope : LayoutScope {
    override val commands = mutableListOf<ImGuiCommand>()
}