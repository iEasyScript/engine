package com.projectx.ui.backend.dsl.scopes

import com.projectx.ui.backend.dsl.commands.ImGuiCommand

interface LayoutScope {
    val commands: MutableList<ImGuiCommand>
}
