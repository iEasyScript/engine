package com.projectx.ui.backend.dsl.scopes

import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.ImGuiState
import com.projectx.ui.backend.dsl.boolState
import com.projectx.ui.backend.dsl.commands.*
import com.projectx.ui.backend.dsl.utils.ImGuiTreeNodeFlags
import com.projectx.ui.backend.rendering.CommandRenderer
import java.util.concurrent.atomic.AtomicReference

@ImGuiDsl.ImGuiDsl
class TabBarScope {
    @PublishedApi
    internal val commands = mutableListOf<ImGuiCommand>()

    inline fun tabItem(label: String, block: ChildScope.() -> Unit): Boolean {
        val key = "last_tabItem_${label}"
        val last = CommandRenderer.getSharedState(key) { boolState(false) }
        val result = AtomicReference(false)
        commands.add(BeginTabItemCommand(label, null, 0, result))

        val scope = ChildScope()
        scope.block()
        commands.addAll(scope.commands)

        commands.add(EndTabItemCommand())
        commands.add(SetBoolStateFromRefCommand(last, result))
        return last.value
    }

    inline fun tabItemButton(label: String, flags: Int = 0, crossinline onClick: () -> Unit) {
        commands.add(TabItemButtonActionCommand(label, flags) { onClick() })
    }
}

@ImGuiDsl.ImGuiDsl
class TreeScope : LayoutScope {
    override val commands = mutableListOf<ImGuiCommand>()

    inline fun treeNode(label: String, flags: Int = ImGuiTreeNodeFlags.None, block: TreeScope.() -> Unit): Boolean {
        val key = "last_tree_treeNode_${label}_${flags}"
        val last = CommandRenderer.getSharedState(key) { boolState(false) }
        val result = AtomicReference(false)
        commands.add(TreeNodeCommand(label, flags, result))

        val scope = TreeScope()
        scope.block()
        commands.addAll(scope.commands)

        commands.add(EndTreeNodeCommand())
        commands.add(SetBoolStateFromRefCommand(last, result))
        return last.value
    }
}

@ImGuiDsl.ImGuiDsl
class ListBoxScope : LayoutScope {
    override val commands = mutableListOf<ImGuiCommand>()

    fun selectable(label: String, selected: ImGuiState<Boolean>): Boolean {
        commands.add(SelectableCommand(label, selected))
        return selected.value
    }
}

@ImGuiDsl.ImGuiDsl
class ComboScope : LayoutScope {
    override val commands = mutableListOf<ImGuiCommand>()

    fun selectable(label: String, selected: ImGuiState<Boolean>): Boolean {
        commands.add(SelectableCommand(label, selected))
        return selected.value
    }

    fun selectable(
        label: String,
        selected: ImGuiState<Boolean>,
        onClick: () -> Unit
    ) {
        commands.add(SelectableActionCommand(label, selected, onClick))
    }

    fun setItemDefaultFocus() {
        commands.add(SetItemDefaultFocusCommand())
    }
}