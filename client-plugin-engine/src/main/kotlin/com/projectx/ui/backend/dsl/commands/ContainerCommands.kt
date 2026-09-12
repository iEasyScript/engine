package com.projectx.ui.backend.dsl.commands

import com.projectx.ui.backend.dsl.ImGuiState
import com.projectx.ui.backend.dsl.utils.ImGuiTreeNodeFlags
import com.projectx.ui.backend.native.NativeBridge
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicReference

data class BeginMenuBarCommand(val result: AtomicReference<Boolean>) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        if (ImGuiExecState.isSkipping()) {
            ImGuiExecState.beginSkipped()
            result.set(false)
            return
        }
        val open = NativeBridge.beginMenuBar()
        ImGuiExecState.begin(open)
        result.set(open)
    }
}

data class EndMenuBarCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        ImGuiExecState.end {
            NativeBridge.endMenuBar()
        }
    }
}

data class BeginMenuCommand(val label: String, val result: AtomicReference<Boolean>) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        if (ImGuiExecState.isSkipping()) {
            ImGuiExecState.beginSkipped()
            result.set(false)
            return
        }
        val open = NativeBridge.beginMenu(safeLabel)
        ImGuiExecState.begin(open)
        result.set(open)
    }
}

data class EndMenuCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        ImGuiExecState.end {
            NativeBridge.endMenu()
        }
    }
}

data class MenuItemActionCommand(
    val label: String,
    val shortcut: String,
    val selected: Boolean,
    val onClick: () -> Unit
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        val safeShortcut = shortcut.take(64)
        val clicked = NativeBridge.menuItem(safeLabel, safeShortcut, selected, true)
        if (clicked) {
            try { onClick() } catch (e: Throwable) {
                System.err.println("onClick failed for menuItem '$label': ${e.message}")
                e.printStackTrace()
            }
        }
    }
}

data class BeginComboCommand(
    val label: String,
    val preview: String,
    val result: ImGuiState<Boolean>
) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        val safePreview = preview.take(256)
        if (ImGuiExecState.isSkipping()) {
            ImGuiExecState.beginSkipped()
            result.value = false
            return
        }
        val open = NativeBridge.beginCombo(safeLabel, safePreview)
        ImGuiExecState.begin(open)
        result.value = open
    }
}

data class EndComboCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        ImGuiExecState.end {
            NativeBridge.endCombo()
        }
    }
}

data class ComboCommand(val label: String, val selectedIndex: ImGuiState<Int>, val items: List<String>, val maxItemsShown: Int) : ImGuiCommand() {
    override fun isStructural(): Boolean = false
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        NativeBridge.combo(safeLabel, selectedIndex.buffer!!, items.joinToString("\u0000", postfix = "\u0000"), maxItemsShown)
    }
}

data class ComboActionValueCommand(
    val label: String,
    val currentIndex: Int,
    val items: List<String>,
    val maxItemsShown: Int = -1,
    val onChange: (Int) -> Unit
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        val joined = items.joinToString("\u0000", postfix = "\u0000")
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(ValueLayout.JAVA_INT)
            buffer.set(ValueLayout.JAVA_INT, 0, currentIndex)
            NativeBridge.combo(safeLabel, buffer, joined, maxItemsShown)
            val newIndex = buffer.get(ValueLayout.JAVA_INT, 0)
            if (newIndex != currentIndex) {
                try { onChange(newIndex) } catch (e: Throwable) {
                    System.err.println("onChange failed for combo '$label': ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }
}

data class SelectableCommand(
    val label: String,
    val selected: ImGuiState<Boolean>,
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        NativeBridge.selectableWithBuffer(safeLabel, selected.buffer!!, 0, 0f, 0f)
    }
}

data class SelectableActionCommand(
    val label: String,
    val selected: ImGuiState<Boolean>,
    val onClick: () -> Unit
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        val clicked = NativeBridge.selectableWithBuffer(safeLabel, selected.buffer!!, 0, 0f, 0f)
        if (clicked) {
            try { onClick() } catch (e: Throwable) {
                System.err.println("onClick failed for selectable '$label': ${e.message}")
                e.printStackTrace()
            }
        }
    }
}

data class SelectableActionValueCommand(
    val label: String,
    val isSelected: Boolean,
    val onClick: () -> Unit
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(ValueLayout.JAVA_BOOLEAN)
            buffer.set(ValueLayout.JAVA_BOOLEAN, 0, isSelected)
            val clicked = NativeBridge.selectableWithBuffer(safeLabel, buffer, 0, 0f, 0f)
            if (clicked) {
                try { onClick() } catch (e: Throwable) {
                    System.err.println("onClick failed for selectable '$label': ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }
}

data class BeginChildCommand(
    val id: String,
    val width: Float,
    val height: Float,
    val result: AtomicReference<Boolean>,
    val childFlags: Int = 0,
    val windowFlags: Int = 0
) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        val safeId = id.take(256)
        if (ImGuiExecState.isSkipping()) {
            ImGuiExecState.beginSkipped()
            result.set(false)
            return
        }
        val open = NativeBridge.beginChild(safeId, width, height, childFlags, windowFlags)
        ImGuiExecState.begin(open)
        result.set(open)
    }
}

data class EndChildCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        ImGuiExecState.end {
            NativeBridge.endChild()
        }
    }
}

data class BeginTableCommand(
    val id: String,
    val columns: Int,
    val flags: Int,
    val result: AtomicReference<Boolean>
) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        val safeId = id.take(256)
        if (ImGuiExecState.isSkipping()) {
            ImGuiExecState.beginSkipped()
            result.set(false)
            return
        }
        val open = NativeBridge.beginTable(safeId, columns, flags)
        ImGuiExecState.begin(open)
        result.set(open)
    }
}

data class EndTableCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        ImGuiExecState.end {
            NativeBridge.endTable()
        }
    }
}

data class TableSetupColumnCommand(
    val label: String,
    val flags: Int,
    val width: Float
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256)
        NativeBridge.tableSetupColumn(safeLabel, flags, width)
    }
}

data class TableHeadersRowCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.tableHeadersRow()
    }
}

data class TableNextRowCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.tableNextRow()
    }
}

data class TableNextColumnCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.tableNextColumn()
    }
}

data class TreeNodeCommand(
    val label: String,
    val flags: Int = ImGuiTreeNodeFlags.None,
    val result: AtomicReference<Boolean>
) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        if (ImGuiExecState.isSkipping()) {
            ImGuiExecState.beginSkipped()
            result.set(false)
            return
        }
        val open = NativeBridge.treeNodeEx(safeLabel, flags)
        ImGuiExecState.begin(open)
        result.set(open)
    }
}

data class TreePushCommand(val id: String) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.treePush(id.take(256))
    }
}

data class TreePopCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.treePop()
    }
}

data class EndTreeNodeCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        ImGuiExecState.end {
            NativeBridge.treePop()
        }
    }
}

/**
 * Closes a [TreeNodeCommand] opened with `NoTreePushOnOpen`: the recorded exec state still has to unwind, but
 * ImGui pushed no tree level, so popping one here would unbalance its stack.
 */
object EndUnpushedTreeNodeCommand : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        ImGuiExecState.end { }
    }
}

data class CollapsingHeaderCommand(
    val label: String,
    val flags: Int = 0,
    val result: AtomicReference<Boolean>
) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        if (ImGuiExecState.isSkipping()) {
            ImGuiExecState.beginSkipped()
            result.set(false)
            return
        }
        val open = NativeBridge.collapsingHeader(safeLabel, flags)
        ImGuiExecState.begin(open)
        result.set(open)
    }
}

data class EndCollapsingHeaderCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        ImGuiExecState.end {
        }
    }
}

data class BeginTabBarCommand(
    val id: String,
    val flags: Int = 0,
    val result: AtomicReference<Boolean>
) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        val safeId = id.take(256)
        if (ImGuiExecState.isSkipping()) {
            ImGuiExecState.beginSkipped()
            result.set(false)
            return
        }
        val open = NativeBridge.beginTabBar(safeId, flags)
        ImGuiExecState.begin(open)
        result.set(open)
    }
}

data class EndTabBarCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        ImGuiExecState.end {
            NativeBridge.endTabBar()
        }
    }
}

data class BeginTabItemCommand(
    val label: String,
    val open: AtomicReference<Boolean>? = null,
    val flags: Int = 0,
    val result: AtomicReference<Boolean>
) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        if (ImGuiExecState.isSkipping()) {
            ImGuiExecState.beginSkipped()
            result.set(false)
            return
        }
        
        val tabOpen = if (open != null) {
            Arena.ofConfined().use { arena ->
                val buffer = arena.allocate(ValueLayout.JAVA_BOOLEAN)
                buffer.set(ValueLayout.JAVA_BOOLEAN, 0, open.get())
                val opened = NativeBridge.beginTabItem(safeLabel, buffer, flags)
                open.set(buffer.get(ValueLayout.JAVA_BOOLEAN, 0))
                opened
            }
        } else {
            NativeBridge.beginTabItem(safeLabel, null, flags)
        }
        
        ImGuiExecState.begin(tabOpen)
        result.set(tabOpen)
    }
}

data class EndTabItemCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        ImGuiExecState.end {
            NativeBridge.endTabItem()
        }
    }
}

data class TabItemButtonActionCommand(
    val label: String,
    val flags: Int,
    val onClick: () -> Unit
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        val clicked = NativeBridge.tabItemButton(safeLabel, flags)
        if (clicked) {
            try { onClick() } catch (e: Throwable) {
                System.err.println("onClick failed for tabItemButton '$label': ${e.message}")
                e.printStackTrace()
            }
        }
    }
}

data class BeginListBoxCommand(
    val label: String,
    val sizeX: Float = 0f,
    val sizeY: Float = 0f,
    val result: AtomicReference<Boolean>
) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        if (ImGuiExecState.isSkipping()) {
            ImGuiExecState.beginSkipped()
            result.set(false)
            return
        }
        val open = NativeBridge.beginListBox(safeLabel, sizeX, sizeY)
        ImGuiExecState.begin(open)
        result.set(open)
    }
}

data class EndListBoxCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        ImGuiExecState.end {
            NativeBridge.endListBox()
        }
    }
}
