package com.projectx.ui.backend.dsl.scopes

import com.projectx.ui.backend.dsl.BooleanState
import com.projectx.ui.backend.dsl.ImGuiState
import com.projectx.ui.backend.dsl.boolState
import com.projectx.ui.backend.dsl.commands.*
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiStyleVar
import com.projectx.ui.backend.dsl.utils.ImGuiTreeNodeFlags
import com.projectx.ui.backend.flags.WindowFlags
import com.projectx.ui.backend.rendering.CommandRenderer
import java.util.concurrent.atomic.AtomicReference

fun WindowScope.menuItem(label: String, shortcut: String = "", selected: Boolean = false, onClick: () -> Unit) {
    commands.add(MenuItemActionCommand(label, shortcut, selected, onClick))
}

fun WindowScope.selectable(label: String, selected: BooleanState): Boolean {
    commands.add(SelectableCommand(label, selected))
    return selected.value
}

fun WindowScope.setItemDefaultFocus() {
    commands.add(SetItemDefaultFocusCommand())
}

fun WindowScope.setTooltip(text: String) {
    commands.add(SetTooltipCommand(text))
}

fun WindowScope.rangeSliderFloat(
    label: String,
    minState: ImGuiState<Float>,
    maxState: ImGuiState<Float>,
    rangeMin: Float,
    rangeMax: Float,
    format: String = "%.3f",
    flags: Int = 0
): Boolean {
    val changed = AtomicReference(false)
    commands.add(RangeSliderFloatCommand(label, minState, maxState, rangeMin, rangeMax, format, flags, changed))
    return changed.get()
}

fun WindowScope.rangeSliderInt(
    label: String,
    minState: ImGuiState<Int>,
    maxState: ImGuiState<Int>,
    rangeMin: Int,
    rangeMax: Int,
    format: String = "%d",
    flags: Int = 0
): Boolean {
    val changed = AtomicReference(false)
    commands.add(RangeSliderIntCommand(label, minState, maxState, rangeMin, rangeMax, format, flags, changed))
    return changed.get()
}

inline fun WindowScope.child(
    id: String,
    width: Float = 0f,
    height: Float = 0f,
    childFlags: Int = 0,
    windowFlags: Int = 0,
    block: ChildScope.() -> Unit
) {
    val result = AtomicReference(false)
    commands.add(BeginChildCommand(id, width, height, result, childFlags, windowFlags))
    val scope = ChildScope()
    scope.block()
    commands.addAll(scope.commands)
    commands.add(EndChildCommand())
}

inline fun WindowScope.menu(
    label: String,
    enabled: Boolean = true,
    block: MenuScope.() -> Unit
): Boolean {
    val key = "last_window_menu_${label}"
    val last = CommandRenderer.getSharedState(key) { boolState(false) }
    val result = AtomicReference(false)
    commands.add(BeginMenuCommand(label, result))
    val scope = MenuScope()
    scope.block()
    commands.addAll(scope.commands)
    commands.add(EndMenuCommand())
    commands.add(SetBoolStateFromRefCommand(last, result))
    return last.value
}

inline fun WindowScope.menuBar(block: MenuScope.() -> Unit): Boolean {
    val last = CommandRenderer.getSharedState("last_menuBar") { boolState(false) }
    val result = AtomicReference(false)
    commands.add(BeginMenuBarCommand(result))
    val scope = MenuScope()
    scope.block()
    commands.addAll(scope.commands)
    commands.add(EndMenuBarCommand())
    commands.add(SetBoolStateFromRefCommand(last, result))
    return last.value
}

inline fun WindowScope.combo(
    label: String,
    preview: String,
    block: ComboScope.() -> Unit
): Boolean {
    val stateKey = "combo_${label}"
    val isOpen = CommandRenderer.getSharedState(stateKey) { boolState(false) }
    commands.add(BeginComboCommand(label, preview, isOpen))
    val scope = ComboScope()
    scope.block()
    commands.addAll(scope.commands)
    commands.add(EndComboCommand())
    return isOpen.value
}

fun WindowScope.combo(
    label: String,
    currentItem: ImGuiState<Int>,
    items: List<String>,
    maxItemsShown: Int = -1
) {
    commands.add(ComboCommand(label, currentItem, items, maxItemsShown))
}

fun WindowScope.combo(
    label: String,
    currentIndex: Int,
    items: List<String>,
    maxItemsShown: Int = -1,
    onChange: (Int) -> Unit
) {
    commands.add(ComboActionValueCommand(label, currentIndex, items, maxItemsShown, onChange))
}

fun WindowScope.tooltip(text: String) {
    commands.add(SetTooltipCommand(text))
}

fun WindowScope.itemTooltip(text: String) {
    commands.add(ItemTooltipCommand(text))
}

inline fun WindowScope.tabBar(
    id: String,
    flags: Int = 0,
    block: TabBarScope.() -> Unit
): Boolean {
    val key = "last_tabBar_${id}_${flags}"
    val last = CommandRenderer.getSharedState(key) { boolState(false) }
    val result = AtomicReference(false)
    commands.add(BeginTabBarCommand(id, flags, result))
    val scope = TabBarScope()
    scope.block()
    commands.addAll(scope.commands)
    commands.add(EndTabBarCommand())
    commands.add(SetBoolStateFromRefCommand(last, result))
    return last.value
}

inline fun WindowScope.treeNode(
    label: String,
    flags: Int = ImGuiTreeNodeFlags.None,
    block: TreeScope.() -> Unit
): Boolean {
    val key = "last_window_treeNode_${label}_${flags}"
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

fun WindowScope.multiSelectListBox(
    label: String,
    items: List<String>,
    selectedIndices: ImGuiState<Set<Int>>,
    sizeX: Float = 0f,
    sizeY: Float = 0f
): Boolean {
    val changed = AtomicReference(false)
    commands.add(MultiSelectListBoxCommand(label, items, selectedIndices, sizeX, sizeY, changed))
    return changed.get()
}

inline fun WindowScope.group(block: ChildScope.() -> Unit) {
    commands.add(BeginGroupCommand())
    val scope = ChildScope()
    scope.block()
    commands.addAll(scope.commands)
    commands.add(EndGroupCommand())
}

inline fun WindowScope.styleVar(styleVar: ImGuiStyleVar, value: Float, block: ChildScope.() -> Unit) {
    commands.add(PushStyleVarFloatCommand(styleVar, value))
    val scope = ChildScope()
    scope.block()
    commands.addAll(scope.commands)
    commands.add(PopStyleVarCommand(1))
}

inline fun WindowScope.styleVar(styleVar: ImGuiStyleVar, x: Float, y: Float, block: ChildScope.() -> Unit) {
    commands.add(PushStyleVarVec2Command(styleVar, x, y))
    val scope = ChildScope()
    scope.block()
    commands.addAll(scope.commands)
    commands.add(PopStyleVarCommand(1))
}

inline fun WindowScope.styleColor(colorIndex: ImGuiCol, color: Int, block: ChildScope.() -> Unit) {
    commands.add(PushStyleColorCommand(colorIndex, color))
    val scope = ChildScope()
    scope.block()
    commands.addAll(scope.commands)
    commands.add(PopStyleColorCommand(1))
}

inline fun WindowScope.itemWidth(width: Float, block: ChildScope.() -> Unit) {
    commands.add(PushItemWidthCommand(width))
    val scope = ChildScope()
    scope.block()
    commands.addAll(scope.commands)
    commands.add(PopItemWidthCommand())
}

inline fun WindowScope.popup(
    id: String,
    flags: Int = 0,
    block: PopupScope.() -> Unit
): Boolean {
    val last = CommandRenderer.getSharedState("last_window_popup_${id}_${flags}") { boolState(false) }
    val result = AtomicReference(false)
    commands.add(BeginPopupCommand(id, flags, result))
    val scope = PopupScope()
    scope.block()
    commands.addAll(scope.commands)
    commands.add(EndPopupCommand())
    commands.add(SetBoolStateFromRefCommand(last, result))
    return last.value
}

inline fun WindowScope.popupModal(
    name: String,
    open: ImGuiState<Boolean>? = null,
    flags: WindowFlags = WindowFlags.None,
    block: ModalScope.() -> Unit
): Boolean {
    val last = CommandRenderer.getSharedState("last_window_popupModal_${name}_${flags.value}") { boolState(false) }
    val result = AtomicReference(false)
    val openRef = open?.let { AtomicReference(it.value) }
    commands.add(BeginPopupModalCommand(name, openRef, flags.value, result))
    val scope = ModalScope()
    scope.block()
    commands.addAll(scope.commands)
    commands.add(EndPopupCommand())
    open?.let { openRef?.let { ref -> it.value = ref.get() } }
    commands.add(SetBoolStateFromRefCommand(last, result))
    return last.value
}

fun WindowScope.popupModal(
    name: String,
    open: Boolean,
    flags: WindowFlags = WindowFlags.None,
    onOpenChange: (Boolean) -> Unit,
    block: ModalScope.() -> Unit
): Boolean {
    val last = CommandRenderer.getSharedState("last_window_popupModal_${name}_${flags.value}") { boolState(false) }
    val result = AtomicReference(false)
    val openRef = AtomicReference(open)
    commands.add(BeginPopupModalCommand(name, openRef, flags.value, result))

    val scope = ModalScope()
    scope.block()
    commands.addAll(scope.commands)

    commands.add(EndPopupCommand())
    val proxyState = object : ImGuiState<Boolean>() {
        override var value: Boolean = open
            set(v) {
                field = v
                try { onOpenChange(v) } catch (e: Throwable) {
                    System.err.println("onOpenChange failed for popupModal '$name': ${e.message}")
                    e.printStackTrace()
                }
            }
    }
    commands.add(SetBoolStateFromRefCommand(proxyState, openRef))
    commands.add(SetBoolStateFromRefCommand(last, result))
    return last.value
}

inline fun WindowScope.contextMenuItem(
    id: String? = null,
    mouseButton: Int = 1,
    block: ContextMenuScope.() -> Unit
): Boolean {
    val result = AtomicReference(false)
    commands.add(BeginPopupContextItemCommand(id, mouseButton, result))
    val scope = ContextMenuScope()
    scope.block()
    commands.addAll(scope.commands)
    commands.add(EndPopupCommand())
    return result.get()
}

inline fun WindowScope.contextMenuWindow(
    id: String? = null,
    mouseButton: Int = 1,
    alsoOverItems: Boolean = true,
    block: ContextMenuScope.() -> Unit
): Boolean {
    val result = AtomicReference(false)
    commands.add(BeginPopupContextWindowCommand(id, mouseButton, result))
    val scope = ContextMenuScope()
    scope.block()
    commands.addAll(scope.commands)
    commands.add(EndPopupCommand())
    return result.get()
}

inline fun WindowScope.contextMenuVoid(
    id: String? = null,
    mouseButton: Int = 1,
    block: ContextMenuScope.() -> Unit
): Boolean {
    val result = AtomicReference(false)
    commands.add(BeginPopupContextVoidCommand(id, mouseButton, result))
    val scope = ContextMenuScope()
    scope.block()
    commands.addAll(scope.commands)
    commands.add(EndPopupCommand())
    return result.get()
}

inline fun WindowScope.confirmationDialog(
    title: String,
    message: String,
    open: ImGuiState<Boolean>,
    crossinline onConfirm: () -> Unit = {},
    crossinline onCancel: () -> Unit = {}
): Boolean {
    return popupModal(title, open, WindowFlags.AlwaysAutoResize) {
        text(message)
        separator()
        spacing()
        okCancelButtons(
            onOk = { onConfirm() },
            onCancel = { onCancel() }
        )
    }
}

inline fun WindowScope.inputDialog(
    title: String,
    label: String,
    textState: ImGuiState<String>,
    open: ImGuiState<Boolean>,
    crossinline onOk: (String) -> Unit = {},
    crossinline onCancel: () -> Unit = {}
): Boolean {
    return popupModal(title, open, WindowFlags.AlwaysAutoResize) {
        inputText(label, textState)
        separator()
        spacing()
        okCancelButtons(
            onOk = { onOk(textState.value) },
            onCancel = { onCancel() }
        )
    }
}

inline fun WindowScope.choiceDialog(
    title: String,
    message: String,
    open: ImGuiState<Boolean>,
    choices: List<String>,
    crossinline onChoice: (Int, String) -> Unit = { _, _ -> }
): Boolean {
    return popupModal(title, open, WindowFlags.AlwaysAutoResize) {
        text(message)
        separator()
        spacing()
        choices.forEachIndexed { index, choice ->
            button(choice) {
                onChoice(index, choice)
                closeCurrentPopup()
            }
            if (index < choices.size - 1) {
                sameLine()
            }
        }
    }
}
