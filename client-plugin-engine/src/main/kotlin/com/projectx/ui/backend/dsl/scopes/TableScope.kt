package com.projectx.ui.backend.dsl.scopes

import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.commands.*

@ImGuiDsl.ImGuiDsl
class TableScope : LayoutScope {
    override val commands = mutableListOf<ImGuiCommand>()

    @JvmOverloads
    fun setupColumn(label: String, flags: Int = 0, width: Float = 0f) {
        commands.add(TableSetupColumnCommand(label, flags, width))
    }
    
    fun headersRow() {
        commands.add(TableHeadersRowCommand())
    }
    
    fun nextRow() {
        commands.add(TableNextRowCommand())
    }
    
    fun nextColumn() {
        commands.add(TableNextColumnCommand())
    }
    
    fun text(text: String) {
        commands.add(TextCommand(text))
    }
    
    @JvmOverloads
    fun button(label: String, width: Float = 0f, height: Float = 0f, onClick: () -> Unit) {
        commands.add(ButtonCommand(label, width, height, onClick))
    }
    
    fun smallButton(label: String, onClick: () -> Unit) {
        commands.add(SmallButtonCommand(label, onClick))
    }
    
    fun sameLine() {
        commands.add(SameLineCommand)
    }
}