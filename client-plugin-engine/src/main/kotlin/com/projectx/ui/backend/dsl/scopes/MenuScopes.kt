package com.projectx.ui.backend.dsl.scopes

import com.projectx.ui.backend.dsl.BooleanState
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.boolState
import com.projectx.ui.backend.dsl.commands.*
import com.projectx.ui.backend.rendering.CommandRenderer
import java.util.concurrent.atomic.AtomicReference

@ImGuiDsl.ImGuiDsl
class MenuScope : LayoutScope {
    override val commands = mutableListOf<ImGuiCommand>()

    fun menuItem(label: String, shortcut: String = "", selected: Boolean = false, onClick: () -> Unit) {
        commands.add(MenuItemActionCommand(label, shortcut, selected, onClick))
    }

    inline fun menu(label: String, block: MenuScope.() -> Unit): Boolean {
        val key = "last_child_menu_${label}"
        val last = CommandRenderer.getSharedState(key) { boolState(false) }
        val menuResult = AtomicReference(false)
        commands.add(BeginMenuCommand(label, menuResult))
        
        val scope = MenuScope()
        scope.block()
        commands.addAll(scope.commands)
        
        commands.add(EndMenuCommand())
        commands.add(SetBoolStateFromRefCommand(last, menuResult))
        return last.value
    }
}

@ImGuiDsl.ImGuiDsl
class ContextMenuScope : LayoutScope {
    override val commands = mutableListOf<ImGuiCommand>()

    fun menuItem(label: String, shortcut: String = "", selected: Boolean = false, onClick: () -> Unit) {
        commands.add(MenuItemActionCommand(label, shortcut, selected, onClick))
    }

    /** Runs [block] each frame the popup is actually open (skipped while closed). */
    fun whileShown(block: () -> Unit) {
        commands.add(CallbackCommand(block))
    }

    inline fun menu(label: String, block: ContextMenuScope.() -> Unit): Boolean {
        val key = "last_context_menu_${label}"
        val last = CommandRenderer.getSharedState(key) { boolState(false) }
        val menuResult = AtomicReference(false)
        commands.add(BeginMenuCommand(label, menuResult))

        val scope = ContextMenuScope()
        scope.block()
        commands.addAll(scope.commands)

        commands.add(EndMenuCommand())
        commands.add(SetBoolStateFromRefCommand(last, menuResult))
        return last.value
    }

    fun closeCurrentPopup() {
        commands.add(CloseCurrentPopupCommand())
    }
}
