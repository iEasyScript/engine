package com.projectx.ui.compose.hud

import com.projectx.ui.backend.dsl.ImGuiState
import com.projectx.ui.backend.dsl.commands.AlignTextToFramePaddingCommand
import com.projectx.ui.backend.dsl.commands.ArrowButtonCommand
import com.projectx.ui.backend.dsl.commands.BackgroundDrawListScopeCommand
import com.projectx.ui.backend.dsl.commands.BeginChildCommand
import com.projectx.ui.backend.dsl.commands.BeginComboCommand
import com.projectx.ui.backend.dsl.commands.BeginGroupCommand
import com.projectx.ui.backend.dsl.commands.BeginListBoxCommand
import com.projectx.ui.backend.dsl.commands.BeginMenuBarCommand
import com.projectx.ui.backend.dsl.commands.BeginMenuCommand
import com.projectx.ui.backend.dsl.commands.BeginPopupCommand
import com.projectx.ui.backend.dsl.commands.BeginPopupContextItemCommand
import com.projectx.ui.backend.dsl.commands.BeginPopupContextVoidCommand
import com.projectx.ui.backend.dsl.commands.BeginPopupContextWindowCommand
import com.projectx.ui.backend.dsl.commands.BeginPopupModalCommand
import com.projectx.ui.backend.dsl.commands.BeginTabBarCommand
import com.projectx.ui.backend.dsl.commands.BeginTabItemCommand
import com.projectx.ui.backend.dsl.commands.BeginTableCommand
import com.projectx.ui.backend.dsl.commands.BeginWindowActionValueCommand
import com.projectx.ui.backend.dsl.commands.BeginWindowCommand
import com.projectx.ui.backend.dsl.commands.ButtonCommand
import com.projectx.ui.backend.dsl.commands.CallbackCommand
import com.projectx.ui.backend.dsl.commands.CheckboxActionValueCommand
import com.projectx.ui.backend.dsl.commands.CheckboxCommand
import com.projectx.ui.backend.dsl.commands.CollapsingHeaderCommand
import com.projectx.ui.backend.dsl.commands.ColorEdit4Command
import com.projectx.ui.backend.dsl.commands.ColorPicker4Command
import com.projectx.ui.backend.dsl.commands.ColumnsCommand
import com.projectx.ui.backend.dsl.commands.ComboActionValueCommand
import com.projectx.ui.backend.dsl.commands.ComboCommand
import com.projectx.ui.backend.dsl.commands.DragFloatCommand
import com.projectx.ui.backend.dsl.commands.DragIntCommand
import com.projectx.ui.backend.dsl.commands.EndChildCommand
import com.projectx.ui.backend.dsl.commands.EndCollapsingHeaderCommand
import com.projectx.ui.backend.dsl.commands.EndComboCommand
import com.projectx.ui.backend.dsl.commands.EndGroupCommand
import com.projectx.ui.backend.dsl.commands.EndListBoxCommand
import com.projectx.ui.backend.dsl.commands.EndMenuBarCommand
import com.projectx.ui.backend.dsl.commands.EndMenuCommand
import com.projectx.ui.backend.dsl.commands.EndPopupCommand
import com.projectx.ui.backend.dsl.commands.EndTabBarCommand
import com.projectx.ui.backend.dsl.commands.EndTabItemCommand
import com.projectx.ui.backend.dsl.commands.EndTableCommand
import com.projectx.ui.backend.dsl.commands.EndTreeNodeCommand
import com.projectx.ui.backend.dsl.commands.EndUnpushedTreeNodeCommand
import com.projectx.ui.backend.dsl.commands.EndWindowCommand
import com.projectx.ui.backend.dsl.commands.IconButtonCommand
import com.projectx.ui.backend.dsl.commands.ImGuiCommand
import com.projectx.ui.backend.dsl.commands.ImageButtonCommand
import com.projectx.ui.backend.dsl.commands.ImageCommand
import com.projectx.ui.backend.dsl.commands.IndentCommand
import com.projectx.ui.backend.dsl.commands.InputFloat2Command
import com.projectx.ui.backend.dsl.commands.InputFloat3Command
import com.projectx.ui.backend.dsl.commands.InputFloat4Command
import com.projectx.ui.backend.dsl.commands.InputIntActionValueCommand
import com.projectx.ui.backend.dsl.commands.InputIntCommand
import com.projectx.ui.backend.dsl.commands.InputTextActionValueCommand
import com.projectx.ui.backend.dsl.commands.InputTextCommand
import com.projectx.ui.backend.dsl.commands.InputTextMultilineActionValueCommand
import com.projectx.ui.backend.dsl.commands.InputTextMultilineCommand
import com.projectx.ui.backend.dsl.commands.IsItemClickedCommand
import com.projectx.ui.backend.dsl.commands.IsItemHoveredCommand
import com.projectx.ui.backend.dsl.commands.ItemTooltipCommand
import com.projectx.ui.backend.dsl.commands.MenuItemActionCommand
import com.projectx.ui.backend.dsl.commands.MultiSelectListBoxCommand
import com.projectx.ui.backend.dsl.commands.NewLineCommand
import com.projectx.ui.backend.dsl.commands.NextColumnCommand
import com.projectx.ui.backend.dsl.commands.PopStyleColorCommand
import com.projectx.ui.backend.dsl.commands.ProgressBarCommand
import com.projectx.ui.backend.dsl.commands.PushStyleColorCommand
import com.projectx.ui.backend.dsl.commands.RangeSliderFloatCommand
import com.projectx.ui.backend.dsl.commands.RangeSliderIntCommand
import com.projectx.ui.backend.dsl.commands.SameLineCommand
import com.projectx.ui.backend.dsl.commands.SelectableActionCommand
import com.projectx.ui.backend.dsl.commands.SelectableActionValueCommand
import com.projectx.ui.backend.dsl.commands.SelectableCommand
import com.projectx.ui.backend.dsl.commands.SeparatorCommand
import com.projectx.ui.backend.dsl.commands.SetBoolStateFromRefCommand
import com.projectx.ui.backend.dsl.commands.SetNextWindowPosCommand
import com.projectx.ui.backend.dsl.commands.SetNextWindowSizeCommand
import com.projectx.ui.backend.dsl.commands.SetTooltipCommand
import com.projectx.ui.backend.dsl.commands.SliderFloatCommand
import com.projectx.ui.backend.dsl.commands.SliderIntCommand
import com.projectx.ui.backend.dsl.commands.SmallButtonCommand
import com.projectx.ui.backend.dsl.commands.SpacingCommand
import com.projectx.ui.backend.dsl.commands.TabItemButtonActionCommand
import com.projectx.ui.backend.dsl.commands.TableHeadersRowCommand
import com.projectx.ui.backend.dsl.commands.TableNextColumnCommand
import com.projectx.ui.backend.dsl.commands.TableNextRowCommand
import com.projectx.ui.backend.dsl.commands.TableSetupColumnCommand
import com.projectx.ui.backend.dsl.commands.TextCommand
import com.projectx.ui.backend.dsl.commands.TextWrappedCommand
import com.projectx.ui.backend.dsl.commands.TreeNodeCommand
import com.projectx.ui.backend.dsl.commands.TreePopCommand
import com.projectx.ui.backend.dsl.commands.TreePushCommand
import com.projectx.ui.backend.dsl.commands.TriSliceImageButtonCommand
import com.projectx.ui.backend.dsl.commands.UnindentCommand
import com.projectx.ui.backend.dsl.commands.WindowDrawCommand
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.dsl.utils.ImGuiTreeNodeFlags
import com.projectx.ui.backend.native.ImGuiTexture
import java.util.concurrent.atomic.AtomicReference

/**
 * A script window, or anything else the ImGui DSL describes, read back as a tree Compose can draw.
 *
 * Nodes carry values, never callbacks: a click looks its callback up by [key] in the frame's [HudActions]. That keeps
 * two frames describing the same panel equal, so an unchanged panel is not redrawn every frame just because the
 * script built fresh lambdas for it.
 */
sealed interface HudNode

data class RowNode(val items: List<HudNode>, val indent: Int) : HudNode
data class TextNode(val text: String, val color: Int?, val wrapped: Boolean) : HudNode
data class ButtonNode(val key: String, val label: String, val small: Boolean, val width: Float) : HudNode
data class ArrowNode(val key: String, val direction: Int) : HudNode
data class CheckboxNode(val key: String, val label: String, val checked: Boolean) : HudNode
data class TextFieldNode(val key: String, val label: String, val value: String, val multiline: Boolean) : HudNode
data class NumberNode(val key: String, val label: String, val value: Int, val step: Int) : HudNode
data class FloatNode(val key: String, val label: String, val value: Float, val min: Float, val max: Float, val slider: Boolean) : HudNode
data class ChoiceNode(val key: String, val label: String, val items: List<String>, val index: Int) : HudNode
data class SelectableNode(val key: String, val label: String, val selected: Boolean) : HudNode
data class ProgressNode(val fraction: Float, val overlay: String?, val height: Float) : HudNode
data class ImageNode(val key: String?, val texture: ImGuiTexture, val width: Float, val height: Float, val tint: Int, val label: String?) : HudNode
data class ColorNode(val key: String, val label: String, val color: Int, val withAlpha: Boolean) : HudNode
data class RangeNode(val key: String, val label: String, val low: Float, val high: Float, val min: Float, val max: Float) : HudNode
data class MultiSelectNode(val key: String, val label: String, val items: List<String>, val selected: Set<Int>) : HudNode
data class TooltipNode(val target: HudNode, val tooltip: String) : HudNode
data object SeparatorNode : HudNode
data object SpacingNode : HudNode
data class GroupNode(val children: List<HudNode>) : HudNode
data class ChildNode(val width: Float, val height: Float, val children: List<HudNode>) : HudNode
data class FoldNode(val key: String, val label: String, val open: Boolean, val tree: Boolean, val children: List<HudNode>) : HudNode
data class TabsNode(val key: String, val tabs: List<Tab>, val selected: Int) : HudNode {
    data class Tab(val label: String, val children: List<HudNode>, val buttonKey: String?)
}
data class ComboNode(val key: String, val label: String, val preview: String, val open: Boolean, val children: List<HudNode>) : HudNode
data class TableNode(val columns: List<Column>, val headers: Boolean, val rows: List<List<List<HudNode>>>) : HudNode {
    data class Column(val label: String, val width: Float)
}
data class DrawingNode(val key: String, val shapes: List<Shape>, val height: Float) : HudNode

data class HudWindow(
    val key: String,
    val title: String,
    val closeKey: String?,
    val position: Pair<Float, Float>?,
    val width: Float?,
    val children: List<HudNode>,
)

/** Everything one frame's commands describe: script windows, world drawing, and the few commands ImGui still runs. */
data class HudFrame(val windows: List<HudWindow>, val world: List<Shape>, val native: List<ImGuiCommand>)

/** The callbacks behind one frame's nodes, by node key. Replaced every frame, so a click always runs the latest one. */
class HudActions {
    val clicks = mutableMapOf<String, () -> Unit>()
    val booleans = mutableMapOf<String, (Boolean) -> Unit>()
    val ints = mutableMapOf<String, (Int) -> Unit>()
    val floats = mutableMapOf<String, (Float) -> Unit>()
    val strings = mutableMapOf<String, (String) -> Unit>()
    val ranges = mutableMapOf<String, (Float, Float) -> Unit>()
    val sets = mutableMapOf<String, (Set<Int>) -> Unit>()
    val colors = mutableMapOf<String, (Int) -> Unit>()
    val drawings = mutableMapOf<String, (Float, Float) -> List<Shape>>()
}

/** Open folds, chosen tabs and open combos, kept per key across frames because ImGui kept them per window. */
object HudMemory {
    val open = mutableMapOf<String, Boolean>()
    val tabs = mutableMapOf<String, Int>()
    val drawingWidths = mutableMapOf<String, Float>()
}

/**
 * Reads a frame's command list as ImGui would have run it.
 *
 * Everything the DSL records is here - including the contents of folds and tabs that are closed, which ImGui would
 * have skipped at run time - so this decides open/closed itself and reports it back through the result references
 * scripts read on their next frame.
 */
class HudParser(private val commands: List<ImGuiCommand>, private val actions: HudActions) {
    private var index = 0
    private val native = mutableListOf<ImGuiCommand>()
    private val world = mutableListOf<Shape>()
    private val windows = mutableListOf<HudWindow>()
    private var windowKey = ""
    private val keyCounts = mutableMapOf<String, Int>()
    private val colors = ArrayDeque<Pair<ImGuiCol, Int>>()

    fun parse(): HudFrame {
        var pendingPosition: Pair<Float, Float>? = null
        var pendingWidth: Float? = null
        val pendingNative = mutableListOf<ImGuiCommand>()
        while (index < commands.size) {
            when (val command = commands[index]) {
                is SetNextWindowPosCommand -> {
                    pendingPosition = command.x to command.y
                    pendingNative += command
                    index++
                }
                is SetNextWindowSizeCommand -> {
                    pendingWidth = command.width.takeIf { it > 0f }
                    pendingNative += command
                    index++
                }
                is BeginWindowCommand, is BeginWindowActionValueCommand -> {
                    val title = if (command is BeginWindowCommand) command.title else (command as BeginWindowActionValueCommand).title
                    if (title.startsWith("##")) {
                        // Engine-internal hosts - an invisible surface that captures clicks - stay ImGui windows.
                        native += pendingNative
                        native += nativeWindow()
                    } else {
                        windows += window(command, title, pendingPosition, pendingWidth)
                    }
                    pendingNative.clear()
                    pendingPosition = null
                    pendingWidth = null
                }
                is BackgroundDrawListScopeCommand -> {
                    world += DrawRecorder.record { list -> command.drawCommands.forEach { it.execute(list) } }
                    index++
                }
                else -> {
                    runSideEffect(command)
                    index++
                }
            }
        }
        return HudFrame(windows, world, native)
    }

    private fun nativeWindow(): List<ImGuiCommand> {
        val start = index
        var depth = 0
        while (index < commands.size) {
            val command = commands[index++]
            if (command is BeginWindowCommand || command is BeginWindowActionValueCommand) depth++
            if (command === EndWindowCommand && --depth == 0) break
        }
        return commands.subList(start, index)
    }

    private fun window(begin: ImGuiCommand, title: String, position: Pair<Float, Float>?, width: Float?): HudWindow {
        index++
        // "Title###id" keeps its identity while the shown title changes; plain "Title##id" is identified by all of it.
        windowKey = if ("###" in title) title.substringAfter("###") else title
        keyCounts.clear()
        colors.clear()
        val closeKey = when (begin) {
            is BeginWindowCommand -> begin.open?.let { state -> key("close").also { actions.clicks[it] = { state.value = false } } }
            is BeginWindowActionValueCommand -> key("close").also { actions.clicks[it] = { begin.onOpenChange(false) } }
            else -> null
        }
        val children = block { it === EndWindowCommand }
        return HudWindow(windowKey, display(title), closeKey, position, width, children)
    }

    /** Reads items until [isEnd] matches (consumed), laying them out the way ImGui's cursor would. */
    private fun block(isEnd: (ImGuiCommand) -> Boolean): List<HudNode> {
        val rows = mutableListOf<HudNode>()
        var sameLine = false
        var indent = 0

        fun emit(node: HudNode) {
            val last = rows.lastOrNull()
            if (sameLine && last != null) {
                rows[rows.lastIndex] = if (last is RowNode) last.copy(items = last.items + node) else RowNode(listOf(last, node), indent)
            } else {
                rows += if (indent > 0) RowNode(listOf(node), indent) else node
            }
            sameLine = false
        }

        fun attachTooltip(text: String) {
            val last = rows.removeLastOrNull() ?: return
            rows += when (last) {
                is RowNode -> last.copy(items = last.items.dropLast(1) + TooltipNode(last.items.last(), text))
                else -> TooltipNode(last, text)
            }
        }

        while (index < commands.size) {
            val command = commands[index]
            if (isEnd(command)) {
                index++
                break
            }
            index++
            when (command) {
                is TextCommand -> emit(TextNode(command.text, textColor(), wrapped = false))
                is TextWrappedCommand -> emit(TextNode(command.text, textColor(), wrapped = true))
                is ButtonCommand -> emit(ButtonNode(click(command.label, command.onClick), display(command.label), small = false, command.width))
                is SmallButtonCommand -> emit(ButtonNode(click(command.label, command.onClick), display(command.label), small = true, 0f))
                is ArrowButtonCommand -> emit(ArrowNode(click(command.id, command.onClick), command.dir))
                is CheckboxCommand -> emit(CheckboxNode(bool(command.label, command.state), display(command.label), command.state.value))
                is CheckboxActionValueCommand ->
                    emit(CheckboxNode(key(command.label).also { actions.booleans[it] = command.onChange }, display(command.label), command.currentValue))
                is InputTextCommand -> emit(TextFieldNode(string(command.label, command.state), display(command.label), command.state.value, multiline = false))
                is InputTextMultilineCommand -> emit(TextFieldNode(string(command.label, command.state), display(command.label), command.state.value, multiline = true))
                is InputTextActionValueCommand ->
                    emit(TextFieldNode(key(command.label).also { actions.strings[it] = command.onChange }, display(command.label), command.currentValue, multiline = false))
                is InputTextMultilineActionValueCommand ->
                    emit(TextFieldNode(key(command.label).also { actions.strings[it] = command.onChange }, display(command.label), command.currentValue, multiline = true))
                is InputIntCommand -> emit(NumberNode(int(command.label, command.state), display(command.label), command.state.value, command.step))
                is InputIntActionValueCommand ->
                    emit(NumberNode(key(command.label).also { actions.ints[it] = command.onChange }, display(command.label), command.currentValue, command.step))
                is DragIntCommand -> emit(NumberNode(int(command.label, command.state), display(command.label), command.state.value, 1))
                is SliderIntCommand ->
                    emit(FloatNode(key(command.label).also { k -> actions.floats[k] = { command.state.value = it.toInt() } }, display(command.label), command.state.value.toFloat(), command.min.toFloat(), command.max.toFloat(), slider = true))
                is SliderFloatCommand -> emit(FloatNode(float(command.label, command.state), display(command.label), command.state.value, command.min, command.max, slider = true))
                is DragFloatCommand -> emit(FloatNode(float(command.label, command.state), display(command.label), command.state.value, command.min, command.max, slider = command.max > command.min))
                is ComboCommand ->
                    emit(ChoiceNode(int(command.label, command.selectedIndex), display(command.label), command.items, command.selectedIndex.value))
                is ComboActionValueCommand ->
                    emit(ChoiceNode(key(command.label).also { actions.ints[it] = command.onChange }, display(command.label), command.items, command.currentIndex))
                is SelectableCommand -> emit(SelectableNode(key(command.label).also { k -> actions.clicks[k] = { command.selected.value = !command.selected.value } }, display(command.label), command.selected.value))
                is SelectableActionCommand -> emit(SelectableNode(click(command.label, command.onClick), display(command.label), command.selected.value))
                is SelectableActionValueCommand -> emit(SelectableNode(click(command.label, command.onClick), display(command.label), command.isSelected))
                is MenuItemActionCommand -> emit(SelectableNode(click(command.label, command.onClick), display(command.label), command.selected))
                is ProgressBarCommand -> emit(ProgressNode(command.fraction, command.overlay?.takeIf { it.isNotEmpty() }, command.sizeY))
                is ImageCommand -> emit(ImageNode(null, command.texture, command.sizeX, command.sizeY, command.tintColor, null))
                is ImageButtonCommand -> emit(ImageNode(click("image", command.onClick), command.texture, command.sizeX, command.sizeY, command.tintColor, null))
                is IconButtonCommand -> emit(ImageNode(click(command.id, command.onClick), command.texture, command.iconSize, command.iconSize, command.tintColor, null))
                is TriSliceImageButtonCommand -> emit(ButtonNode(click(command.id, command.onClick), command.label ?: display(command.id), small = false, command.totalWidth))
                is ColorEdit4Command -> emit(color(command.label, command.r, command.g, command.b, command.a, withAlpha = true))
                is ColorPicker4Command -> emit(color(command.label, command.r, command.g, command.b, command.a, withAlpha = true))
                is InputFloat2Command -> listOf(command.x, command.y).forEachIndexed { i, s -> emit(FloatNode(float("${command.label}$i", s), if (i == 0) display(command.label) else "", s.value, 0f, 0f, slider = false)) }
                is InputFloat3Command -> listOf(command.x, command.y, command.z).forEachIndexed { i, s -> emit(FloatNode(float("${command.label}$i", s), if (i == 0) display(command.label) else "", s.value, 0f, 0f, slider = false)) }
                is InputFloat4Command -> listOf(command.x, command.y, command.z, command.w).forEachIndexed { i, s -> emit(FloatNode(float("${command.label}$i", s), if (i == 0) display(command.label) else "", s.value, 0f, 0f, slider = false)) }
                is RangeSliderFloatCommand -> emit(RangeNode(key(command.label).also { k -> actions.ranges[k] = { lo, hi -> command.minState.value = lo; command.maxState.value = hi } }, display(command.label), command.minState.value, command.maxState.value, command.rangeMin, command.rangeMax))
                is RangeSliderIntCommand -> emit(RangeNode(key(command.label).also { k -> actions.ranges[k] = { lo, hi -> command.minState.value = lo.toInt(); command.maxState.value = hi.toInt() } }, display(command.label), command.minState.value.toFloat(), command.maxState.value.toFloat(), command.rangeMin.toFloat(), command.rangeMax.toFloat()))
                is MultiSelectListBoxCommand -> emit(MultiSelectNode(key(command.label).also { k -> actions.sets[k] = { command.selectedIndices.value = it } }, display(command.label), command.items, command.selectedIndices.value))
                is ItemTooltipCommand -> attachTooltip(command.text)
                is SetTooltipCommand -> Unit
                SeparatorCommand -> emit(SeparatorNode)
                SpacingCommand, NewLineCommand -> emit(SpacingNode)
                SameLineCommand -> sameLine = true
                is IndentCommand -> indent++
                is UnindentCommand -> indent = (indent - 1).coerceAtLeast(0)
                is TreePushCommand -> indent++
                is TreePopCommand -> indent = (indent - 1).coerceAtLeast(0)
                is PushStyleColorCommand -> colors.addLast(command.colorIndex to command.color)
                is PopStyleColorCommand -> repeat(command.count) { colors.removeLastOrNull() }
                is BeginGroupCommand -> emit(GroupNode(block { it is EndGroupCommand }))
                is BeginChildCommand -> {
                    command.result.set(true)
                    emit(ChildNode(command.width, command.height, block { it is EndChildCommand }))
                }
                is BeginListBoxCommand -> {
                    command.result.set(true)
                    emit(ChildNode(command.sizeX, command.sizeY, block { it is EndListBoxCommand }))
                }
                is CollapsingHeaderCommand -> {
                    val key = key("fold:${command.label}")
                    val open = HudMemory.open.getOrPut(key) { command.flags and ImGuiTreeNodeFlags.DefaultOpen != 0 }
                    command.result.set(open)
                    emit(FoldNode(key, display(command.label), open, tree = false, block { it is EndCollapsingHeaderCommand }))
                }
                is TreeNodeCommand -> {
                    val key = key("tree:${command.label}")
                    val open = HudMemory.open.getOrPut(key) { command.flags and ImGuiTreeNodeFlags.DefaultOpen != 0 }
                    command.result.set(open)
                    val unpushed = command.flags and ImGuiTreeNodeFlags.NoTreePushOnOpen != 0
                    val children = if (unpushed) {
                        if (index < commands.size && commands[index] === EndUnpushedTreeNodeCommand) index++
                        emptyList()
                    } else {
                        block { it is EndTreeNodeCommand }
                    }
                    emit(FoldNode(key, display(command.label), open, tree = true, children))
                }
                is BeginTabBarCommand -> {
                    command.result.set(true)
                    emit(tabs(command.id))
                }
                is BeginComboCommand -> {
                    val key = key("combo:${command.label}")
                    val open = HudMemory.open[key] ?: false
                    command.result.value = open
                    emit(ComboNode(key, display(command.label), command.preview, open, block { it is EndComboCommand }))
                }
                is BeginTableCommand -> {
                    command.result.set(true)
                    emit(table(command.columns))
                }
                is ColumnsCommand -> if (command.count > 1) emit(columns(command.count))
                is WindowDrawCommand -> emit(drawing(command))
                is BeginPopupCommand -> skip(command.result) { it is EndPopupCommand }
                is BeginPopupModalCommand -> skip(command.result) { it is EndPopupCommand }
                is BeginPopupContextItemCommand -> skip(command.result) { it is EndPopupCommand }
                is BeginPopupContextWindowCommand -> skip(command.result) { it is EndPopupCommand }
                is BeginPopupContextVoidCommand -> skip(command.result) { it is EndPopupCommand }
                is BeginMenuBarCommand -> skip(command.result) { it is EndMenuBarCommand }
                is BeginMenuCommand -> skip(command.result) { it is EndMenuCommand }
                is BeginWindowCommand, is BeginWindowActionValueCommand -> {
                    // A window opened inside another is its own card, as ImGui would have drawn it.
                    val title = if (command is BeginWindowCommand) command.title else (command as BeginWindowActionValueCommand).title
                    index--
                    val outerKey = windowKey
                    windows += window(command, title, null, null)
                    windowKey = outerKey
                }
                is BackgroundDrawListScopeCommand -> world += DrawRecorder.record { list -> command.drawCommands.forEach { it.execute(list) } }
                is AlignTextToFramePaddingCommand -> Unit
                else -> runSideEffect(command)
            }
        }
        return rows
    }

    /** The text colour a pushed style would give the next item; other pushed colours are only counted for pops. */
    private fun textColor(): Int? = colors.lastOrNull { it.first == ImGuiCol.Text }?.second

    private fun tabs(id: String): TabsNode {
        val key = key("tabs:$id")
        val tabs = mutableListOf<TabsNode.Tab>()
        val selected = HudMemory.tabs[key] ?: 0
        val results = mutableListOf<AtomicReference<Boolean>>()
        while (index < commands.size) {
            val command = commands[index++]
            when (command) {
                is EndTabBarCommand -> break
                is BeginTabItemCommand -> {
                    results += command.result
                    tabs += TabsNode.Tab(display(command.label), block { it is EndTabItemCommand }, null)
                }
                is TabItemButtonActionCommand -> tabs += TabsNode.Tab(display(command.label), emptyList(), click(command.label, command.onClick))
                else -> runSideEffect(command)
            }
        }
        val chosen = selected.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        results.forEachIndexed { i, result -> result.set(i == chosen) }
        return TabsNode(key, tabs, chosen)
    }

    private fun table(columnCount: Int): TableNode {
        val columns = mutableListOf<TableNode.Column>()
        var headers = false
        val rows = mutableListOf<MutableList<List<HudNode>>>()
        while (index < commands.size) {
            when (val command = commands[index]) {
                is EndTableCommand -> {
                    index++
                    break
                }
                is TableSetupColumnCommand -> {
                    columns += TableNode.Column(display(command.label), command.width)
                    index++
                }
                is TableHeadersRowCommand -> {
                    headers = true
                    index++
                }
                is TableNextRowCommand -> {
                    rows += mutableListOf<List<HudNode>>()
                    index++
                }
                is TableNextColumnCommand -> {
                    index++
                    if (rows.isEmpty()) rows += mutableListOf<List<HudNode>>()
                    rows.last() += block { it is TableNextColumnCommand || it is TableNextRowCommand || it is EndTableCommand }
                    // The ending command belongs to the table, not the cell; hand it back.
                    index--
                }
                else -> {
                    // Content before any column: ImGui puts it in the first cell.
                    if (rows.isEmpty()) rows += mutableListOf<List<HudNode>>()
                    rows.last() += block { it is TableNextColumnCommand || it is TableNextRowCommand || it is EndTableCommand }
                    index--
                }
            }
        }
        while (columns.size < columnCount) columns += TableNode.Column("", 0f)
        return TableNode(columns.take(columnCount.coerceAtLeast(1)), headers && columns.any { it.label.isNotBlank() }, rows.filter { it.isNotEmpty() })
    }

    /** The old Columns API: cells run until the next NextColumn, rows wrap every [count] cells, Columns(1) ends it. */
    private fun columns(count: Int): TableNode {
        val cells = mutableListOf<List<HudNode>>()
        while (index < commands.size) {
            cells += block { it is NextColumnCommand || (it is ColumnsCommand && it.count <= 1) }
            val ended = commands.getOrNull(index - 1).let { it is ColumnsCommand }
            if (ended || index >= commands.size) break
        }
        return TableNode(List(count) { TableNode.Column("", 0f) }, false, cells.chunked(count))
    }

    private fun drawing(command: WindowDrawCommand): DrawingNode {
        val key = key("draw")
        val width = HudMemory.drawingWidths[key] ?: 320f
        val record: (Float, Float) -> List<Shape> = { w, h -> DrawRecorder.record { list -> command.draw(list, 0f, 0f, w, h) } }
        actions.drawings[key] = record
        val shapes = record(width, DRAWING_HEIGHT)
        val bottom = shapes.maxOfOrNull { shapeBottom(it) } ?: 0f
        return DrawingNode(key, shapes, bottom.coerceIn(0f, 2000f))
    }

    private fun skip(result: AtomicReference<Boolean>, isEnd: (ImGuiCommand) -> Boolean) {
        result.set(false)
        var depth = 0
        while (index < commands.size) {
            val command = commands[index++]
            if (command is BeginPopupCommand || command is BeginPopupModalCommand || command is BeginMenuCommand) depth++
            if (isEnd(command)) {
                if (depth == 0) return
                depth--
            }
        }
    }

    private fun runSideEffect(command: ImGuiCommand) {
        when (command) {
            is CallbackCommand -> command.execute()
            is SetBoolStateFromRefCommand -> command.execute()
            is IsItemHoveredCommand -> command.result.set(false)
            is IsItemClickedCommand -> command.result.set(false)
            else -> Unit
        }
    }

    private fun color(label: String, r: ImGuiState<Float>, g: ImGuiState<Float>, b: ImGuiState<Float>, a: ImGuiState<Float>, withAlpha: Boolean): ColorNode {
        val key = key(label)
        actions.colors[key] = { packed ->
            r.value = (packed and 0xFF) / 255f
            g.value = (packed ushr 8 and 0xFF) / 255f
            b.value = (packed ushr 16 and 0xFF) / 255f
            if (withAlpha) a.value = (packed ushr 24 and 0xFF) / 255f
        }
        return ColorNode(key, display(label), ImGuiColors.rgba(r.value, g.value, b.value, a.value), withAlpha)
    }

    private fun click(label: String, onClick: () -> Unit) = key(label).also { actions.clicks[it] = onClick }
    private fun bool(label: String, state: ImGuiState<Boolean>) = key(label).also { k -> actions.booleans[k] = { state.value = it } }
    private fun int(label: String, state: ImGuiState<Int>) = key(label).also { k -> actions.ints[k] = { state.value = it } }
    private fun float(label: String, state: ImGuiState<Float>) = key(label).also { k -> actions.floats[k] = { state.value = it } }
    private fun string(label: String, state: ImGuiState<String>) = key(label).also { k -> actions.strings[k] = { state.value = it } }

    /** Stable across frames: the window, the widget's id, and how many before it in this window share that id. */
    private fun key(label: String): String {
        val id = "$windowKey/$label"
        val n = keyCounts.merge(id, 1, Int::plus)!!
        return "$id#$n"
    }

    private companion object {
        const val DRAWING_HEIGHT = 400f

        /** ImGui's "label##id" shows only the part before "##". */
        fun display(label: String) = label.substringBefore("##")

        fun shapeBottom(shape: Shape): Float = when (shape) {
            is Shape.Line -> maxOf(shape.y1, shape.y2)
            is Shape.Rect -> maxOf(shape.y1, shape.y2)
            is Shape.Circle -> shape.y + shape.radius
            is Shape.Text -> shape.y + 18f
            is Shape.Image -> maxOf(shape.y1, shape.y2)
            is Shape.Poly -> shape.points.filterIndexed { i, _ -> i % 2 == 1 }.maxOrNull() ?: 0f
        }
    }
}
