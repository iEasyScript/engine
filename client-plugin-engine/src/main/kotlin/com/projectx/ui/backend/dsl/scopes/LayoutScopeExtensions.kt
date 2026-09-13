package com.projectx.ui.backend.dsl.scopes

import org.projectx.core.game.skill.Skill
import com.projectx.script.api.getXp
import com.projectx.ui.backend.dsl.ImGuiState
import com.projectx.ui.backend.dsl.boolState
import com.projectx.ui.backend.dsl.commands.*
import com.projectx.ui.backend.dsl.utils.*
import com.projectx.ui.backend.dsl.utils.ImGuiColors.hex
import com.projectx.ui.backend.flags.InputTextFlags
import com.projectx.ui.backend.native.ImGuiTexture
import com.projectx.ui.backend.rendering.CommandRenderer
import com.projectx.util.getLevelForXp
import com.projectx.util.getXpForLevel
import world.gregs.voidps.cache.Cache
import java.util.concurrent.atomic.AtomicReference

fun LayoutScope.text(text: String) { commands.add(TextCommand(text)) }
fun LayoutScope.textWrapped(text: String) { commands.add(TextWrappedCommand(text)) }
fun LayoutScope.separator() { commands.add(SeparatorCommand) }
fun LayoutScope.sameLine() { commands.add(SameLineCommand) }
fun LayoutScope.itemTooltip(text: String) { commands.add(ItemTooltipCommand(text)) }
fun LayoutScope.newLine() { commands.add(NewLineCommand) }
fun LayoutScope.spacing() { commands.add(SpacingCommand) }

/**
 * Starts a titled group. Panels break their content the same way through this rather than by pairing a bare
 * [text] with a [separator], which is what left the spacing between groups varying panel to panel.
 *
 * Emits its own leading gap, so it wants no [spacing] or [separator] around it.
 */
fun LayoutScope.section(title: String) {
    commands.add(SpacingCommand)
    commands.add(PushStyleColorCommand(ImGuiCol.Text, ImGuiColors.TEXT_ACCENT))
    commands.add(TextCommand(title))
    commands.add(PopStyleColorCommand(1))
    commands.add(SeparatorCommand)
}

const val PROPERTY_LABEL_WIDTH = 150f

/**
 * A two-column property grid: labels in a fixed left column, controls filling the right one.
 *
 * Every [row] therefore starts and ends on the same two vertical edges, which is what stops a panel reading
 * as a pile of differently-sized controls. Pair it with [section] rather than laying widgets out freehand.
 */
@JvmOverloads
inline fun LayoutScope.properties(
    id: String,
    labelWidth: Float = PROPERTY_LABEL_WIDTH,
    block: TableScope.() -> Unit
) {
    table(id = id, columns = 2) {
        setupColumn("label", ImGuiTableColumnFlags.WidthFixed, labelWidth)
        setupColumn("value", ImGuiTableColumnFlags.WidthStretch)
        block()
    }
}

/** [properties] with the cell padding tightened, for dense read-only tables built from [valueRow]. */
@JvmOverloads
inline fun LayoutScope.readout(
    id: String,
    labelWidth: Float = PROPERTY_LABEL_WIDTH,
    block: TableScope.() -> Unit
) {
    styleVar(ImGuiStyleVar.CellPadding, 6f, 1f) {
        properties(id, labelWidth, block)
    }
}

/** One labelled row of a [properties] grid. The control fills the value column, so widths never disagree. */
inline fun TableScope.row(label: String, control: TableScope.() -> Unit) {
    nextRow()
    nextColumn()
    alignTextToFramePadding()
    text(label)
    nextColumn()
    setNextItemWidth(-1f)
    control()
}

/**
 * A read-only label/value row.
 *
 * Skips the frame-padding alignment [row] needs: that padding exists to line a label up with the taller
 * widgets beside it, so applying it to a block of plain readouts pads every line to widget height and makes
 * a short table twice as tall as its content.
 */
fun TableScope.valueRow(label: String, value: String) {
    nextRow()
    nextColumn()
    text(label)
    nextColumn()
    text(value)
}

/**
 * Checkboxes on an aligned grid instead of a ragged run of [sameLine] calls, so their boxes line up in
 * columns regardless of how long the individual labels are.
 */
@JvmOverloads
fun LayoutScope.checkboxGrid(id: String, items: List<Pair<String, ImGuiState<Boolean>>>, columns: Int = 2) {
    table(id = id, columns = columns) {
        repeat(columns) { setupColumn("c$it", ImGuiTableColumnFlags.WidthStretch) }
        items.chunked(columns).forEach { rowItems ->
            nextRow()
            rowItems.forEach { (label, state) ->
                nextColumn()
                checkbox(label, state)
            }
        }
    }
}

/**
 * [textColor] null inherits the ambient text colour, which the dark theme sets light — correct for
 * the theme's recessed button fills. Pass a dark colour only when filling a button with an accent.
 */
@JvmOverloads
fun LayoutScope.button(
    label: String,
    width: Float = 0f,
    height: Float = 0f,
    textColor: Int? = null,
    onClick: () -> Unit,
) {
    if (textColor != null) commands.add(PushStyleColorCommand(ImGuiCol.Text, textColor))
    commands.add(ButtonCommand(label, width, height, onClick))
    if (textColor != null) commands.add(PopStyleColorCommand(1))
}
fun LayoutScope.smallButton(label: String, onClick: () -> Unit) { commands.add(SmallButtonCommand(label, onClick)) }

/**
 * A directional button using ImGui's vector arrow rather than an arrow character - the default font stops at
 * U+00FF, so every arrow codepoint renders as a missing glyph on every platform.
 */
fun LayoutScope.arrowButton(id: String, dir: Int, fallbackLabel: String, onClick: () -> Unit) {
    commands.add(ArrowButtonCommand(id, dir, fallbackLabel, onClick))
}

fun LayoutScope.checkbox(label: String, state: ImGuiState<Boolean>) { commands.add(CheckboxCommand(label, state)) }
fun LayoutScope.checkbox(label: String, currentValue: Boolean, onChange: (Boolean) -> Unit) {
    commands.add(CheckboxActionValueCommand(label, currentValue, onChange))
}
fun LayoutScope.inputText(label: String, textState: ImGuiState<String>, flags: InputTextFlags = InputTextFlags.None) {
    commands.add(InputTextCommand(label, textState, flags.value))
}
fun LayoutScope.inputText(label: String, currentValue: String, maxLength: Int = 256, flags: InputTextFlags = InputTextFlags.None, onChange: (String) -> Unit) {
    commands.add(InputTextActionValueCommand(label, currentValue, maxLength, flags.value, onChange))
}
@JvmOverloads
fun LayoutScope.inputInt(label: String, state: ImGuiState<Int>, step: Int = 1, stepFast: Int = 10) {
    commands.add(InputIntCommand(label, state, step, stepFast))
}
@JvmOverloads
fun LayoutScope.inputInt(label: String, currentValue: Int, step: Int = 1, stepFast: Int = 10, onChange: (Int) -> Unit) {
    commands.add(InputIntActionValueCommand(label, currentValue, step, stepFast, onChange))
}

fun LayoutScope.sliderFloat(label: String, state: ImGuiState<Float>, min: Float, max: Float) {
    commands.add(SliderFloatCommand(label, state, min, max))
}
fun LayoutScope.sliderInt(label: String, state: ImGuiState<Int>, min: Int, max: Int) {
    commands.add(SliderIntCommand(label, state, min, max))
}

fun LayoutScope.setScrollHereY(ratio: Float) { commands.add(SetScrollHereYCommand(ratio)) }
fun LayoutScope.scrollToBottomIfPinned() { commands.add(ScrollToBottomIfPinnedCommand) }

@JvmOverloads
fun LayoutScope.colorEdit4(label: String, r: ImGuiState<Float>, g: ImGuiState<Float>, b: ImGuiState<Float>, a: ImGuiState<Float>, flags: Int = 0) {
    commands.add(ColorEdit4Command(label, r, g, b, a, flags))
}
@JvmOverloads
fun LayoutScope.colorPicker4(label: String, r: ImGuiState<Float>, g: ImGuiState<Float>, b: ImGuiState<Float>, a: ImGuiState<Float>, flags: Int = 0) {
    commands.add(ColorPicker4Command(label, r, g, b, a, flags))
}

inline fun LayoutScope.combo(label: String, preview: String, block: ComboScope.() -> Unit): Boolean {
    val stateKey = "combo_${label}"
    val isOpen = CommandRenderer.getSharedState(stateKey) { boolState(false) }
    commands.add(PushStyleColorCommand(ImGuiCol.Button, hex("#ffffff11")))
    commands.add(PushStyleColorCommand(ImGuiCol.ButtonHovered, hex("#ffffff22")))
    commands.add(PushStyleColorCommand(ImGuiCol.ButtonActive, hex("#ffffff33")))
    commands.add(BeginComboCommand(label, preview, isOpen))
    commands.add(PopStyleColorCommand(3))

    val scope = ComboScope()
    scope.block()
    commands.addAll(scope.commands)

    commands.add(EndComboCommand())
    return isOpen.value
}
@JvmOverloads
fun LayoutScope.combo(label: String, currentItem: ImGuiState<Int>, items: List<String>, maxItemsShown: Int = -1) {
    commands.add(PushStyleColorCommand(ImGuiCol.Button, hex("#ffffff11")))
    commands.add(PushStyleColorCommand(ImGuiCol.ButtonHovered, hex("#ffffff22")))
    commands.add(PushStyleColorCommand(ImGuiCol.ButtonActive, hex("#ffffff33")))
    commands.add(ComboCommand(label, currentItem, items, maxItemsShown))
    commands.add(PopStyleColorCommand(3))
}
@JvmOverloads
fun LayoutScope.combo(label: String, currentIndex: Int, items: List<String>, maxItemsShown: Int = -1, onChange: (Int) -> Unit) {
    commands.add(PushStyleColorCommand(ImGuiCol.Button, hex("#ffffff11")))
    commands.add(PushStyleColorCommand(ImGuiCol.ButtonHovered, hex("#ffffff22")))
    commands.add(PushStyleColorCommand(ImGuiCol.ButtonActive, hex("#ffffff33")))
    commands.add(ComboActionValueCommand(label, currentIndex, items, maxItemsShown, onChange))
    commands.add(PopStyleColorCommand(3))
}

@JvmOverloads
inline fun LayoutScope.table(id: String, columns: Int, flags: Int = 0, block: TableScope.() -> Unit): Boolean {
    val key = "last_scope_table_${id}_${columns}_${flags}"
    val last = CommandRenderer.getSharedState(key) { boolState(false) }
    val result = AtomicReference(false)
    commands.add(BeginTableCommand(id, columns, flags, result))

    val scope = TableScope()
    scope.block()
    commands.addAll(scope.commands)

    commands.add(EndTableCommand())
    commands.add(SetBoolStateFromRefCommand(last, result))
    return last.value
}

@JvmOverloads
fun LayoutScope.tableSetupColumn(label: String, flags: Int = 0, width: Float = 0f) { commands.add(TableSetupColumnCommand(label, flags, width)) }
fun LayoutScope.tableHeadersRow() { commands.add(TableHeadersRowCommand()) }
fun LayoutScope.tableNextRow() { commands.add(TableNextRowCommand()) }
fun LayoutScope.tableNextColumn() { commands.add(TableNextColumnCommand()) }

@JvmOverloads
inline fun LayoutScope.listBox(label: String, sizeX: Float = 0f, sizeY: Float = 0f, block: ListBoxScope.() -> Unit): Boolean {
    val key = "last_listBox_${label}_${sizeX}_${sizeY}"
    val last = CommandRenderer.getSharedState(key) { boolState(false) }
    val result = AtomicReference(false)
    commands.add(BeginListBoxCommand(label, sizeX, sizeY, result))

    val scope = ListBoxScope()
    scope.block()
    commands.addAll(scope.commands)

    commands.add(EndListBoxCommand())
    commands.add(SetBoolStateFromRefCommand(last, result))
    return last.value
}

inline fun LayoutScope.group(block: ChildScope.() -> Unit) {
    commands.add(BeginGroupCommand())
    val scope = ChildScope(); scope.block(); commands.addAll(scope.commands)
    commands.add(EndGroupCommand())
}
inline fun LayoutScope.styleVar(styleVar: ImGuiStyleVar, value: Float, block: ChildScope.() -> Unit) {
    commands.add(PushStyleVarFloatCommand(styleVar, value))
    val scope = ChildScope(); scope.block(); commands.addAll(scope.commands)
    commands.add(PopStyleVarCommand(1))
}
inline fun LayoutScope.styleVar(styleVar: ImGuiStyleVar, x: Float, y: Float, block: ChildScope.() -> Unit) {
    commands.add(PushStyleVarVec2Command(styleVar, x, y))
    val scope = ChildScope(); scope.block(); commands.addAll(scope.commands)
    commands.add(PopStyleVarCommand(1))
}
inline fun LayoutScope.styleColor(colorIndex: ImGuiCol, color: Int, block: ChildScope.() -> Unit) {
    commands.add(PushStyleColorCommand(colorIndex, color))
    val scope = ChildScope(); scope.block(); commands.addAll(scope.commands)
    commands.add(PopStyleColorCommand(1))
}
inline fun LayoutScope.itemWidth(width: Float, block: ChildScope.() -> Unit) {
    commands.add(PushItemWidthCommand(width))
    val scope = ChildScope(); scope.block(); commands.addAll(scope.commands)
    commands.add(PopItemWidthCommand())
}

/**
 * Reports the previous frame's result - a command cannot answer in the frame it is recorded.
 * ! The backing slot is keyed globally, so two call sites in one frame overwrite each other; give this
 * a per-caller key before using it in more than one place.
 */
fun LayoutScope.isItemHovered(): Boolean {
    val last = CommandRenderer.getSharedState("last_isItemHovered") { boolState(false) }
    val result = AtomicReference(false)
    commands.add(IsItemHoveredCommand(result))
    commands.add(SetBoolStateFromRefCommand(last, result))
    return last.value
}

/** Previous frame's result. Keyed only by [mouseButton] - see the caveat on [isItemHovered]. */
@JvmOverloads
fun LayoutScope.isItemClicked(mouseButton: Int = 0): Boolean {
    val key = "last_isItemClicked_${mouseButton}"
    val last = CommandRenderer.getSharedState(key) { boolState(false) }
    val result = AtomicReference(false)
    commands.add(IsItemClickedCommand(mouseButton, result))
    commands.add(SetBoolStateFromRefCommand(last, result))
    return last.value
}

@JvmOverloads
fun LayoutScope.progressBar(
    fraction: Float,
    sizeX: Float = -1f,
    sizeY: Float = 0f,
    overlay: String? = null
) { commands.add(ProgressBarCommand(fraction, sizeX, sizeY, overlay)) }

@JvmOverloads
fun LayoutScope.dragFloat(
    label: String,
    state: ImGuiState<Float>,
    speed: Float = 1f,
    min: Float = 0f,
    max: Float = 0f,
    format: String = "%.3f",
    flags: Int = 0
) { commands.add(DragFloatCommand(label, state, speed, min, max, format, flags)) }

@JvmOverloads
fun LayoutScope.dragInt(
    label: String,
    state: ImGuiState<Int>,
    speed: Float = 1f,
    min: Int = 0,
    max: Int = 0,
    format: String = "%d",
    flags: Int = 0
) { commands.add(DragIntCommand(label, state, speed, min, max, format, flags)) }

fun LayoutScope.inputTextMultiline(
    label: String,
    textState: ImGuiState<String>,
    sizeX: Float = 0f,
    sizeY: Float = 0f,
    flags: InputTextFlags = InputTextFlags.None
) { commands.add(InputTextMultilineCommand(label, textState, sizeX, sizeY, flags.value)) }

fun LayoutScope.inputTextMultiline(
    label: String,
    currentValue: String,
    maxLength: Int = 1024,
    sizeX: Float = 0f,
    sizeY: Float = 0f,
    flags: InputTextFlags = InputTextFlags.None,
    onChange: (String) -> Unit
) { commands.add(InputTextMultilineActionValueCommand(label, currentValue, maxLength, sizeX, sizeY, flags.value, onChange)) }

fun LayoutScope.inputFloat2(
    label: String,
    x: ImGuiState<Float>,
    y: ImGuiState<Float>,
    format: String = "%.3f",
    flags: InputTextFlags = InputTextFlags.None
) { commands.add(InputFloat2Command(label, x, y, format, flags.value)) }

fun LayoutScope.inputFloat3(
    label: String,
    x: ImGuiState<Float>,
    y: ImGuiState<Float>,
    z: ImGuiState<Float>,
    format: String = "%.3f",
    flags: InputTextFlags = InputTextFlags.None
) { commands.add(InputFloat3Command(label, x, y, z, format, flags.value)) }

fun LayoutScope.inputFloat4(
    label: String,
    x: ImGuiState<Float>,
    y: ImGuiState<Float>,
    z: ImGuiState<Float>,
    w: ImGuiState<Float>,
    format: String = "%.3f",
    flags: InputTextFlags = InputTextFlags.None
) { commands.add(InputFloat4Command(label, x, y, z, w, format, flags.value)) }

@JvmOverloads
fun LayoutScope.colorEdit3(
    label: String,
    r: ImGuiState<Float>,
    g: ImGuiState<Float>,
    b: ImGuiState<Float>,
    flags: Int = 0
) {
    val dummyAlpha = object : ImGuiState<Float>() { override var value: Float = 1f; override val buffer = null }
    commands.add(ColorEdit4Command(label, r, g, b, dummyAlpha, flags))
}

@JvmOverloads
fun LayoutScope.image(
    texture: ImGuiTexture,
    sizeX: Float,
    sizeY: Float,
    uv0X: Float = 0f,
    uv0Y: Float = 0f,
    uv1X: Float = 1f,
    uv1Y: Float = 1f,
    tintColor: Int = -1,
    borderColor: Int = 0
) { commands.add(ImageCommand(texture, sizeX, sizeY, uv0X, uv0Y, uv1X, uv1Y, tintColor, borderColor)) }

@JvmOverloads
fun LayoutScope.imageButton(
    texture: ImGuiTexture,
    sizeX: Float,
    sizeY: Float,
    uv0X: Float = 0f,
    uv0Y: Float = 0f,
    uv1X: Float = 1f,
    uv1Y: Float = 1f,
    framePadding: Int = -1,
    bgColor: Int = 0,
    tintColor: Int = -1,
    onClick: () -> Unit
) { commands.add(ImageButtonCommand(texture, sizeX, sizeY, uv0X, uv0Y, uv1X, uv1Y, framePadding, bgColor, tintColor, onClick)) }

/**
 * Framed icon button, one standard frame tall so it aligns with widgets beside it. Unlike
 * [imageButton] it carries an id, so many can coexist in one frame.
 */
@JvmOverloads
fun LayoutScope.iconButton(
    id: String,
    texture: ImGuiTexture,
    iconSize: Float,
    buttonWidth: Float = iconSize + 8f,
    tintColor: Int = ImGuiColors.WHITE,
    onClick: () -> Unit
) { commands.add(IconButtonCommand(id, texture, iconSize, buttonWidth, tintColor, onClick)) }

@JvmOverloads
fun LayoutScope.watermark(
    texture: ImGuiTexture,
    corner: Corner = Corner.BottomRight,
    paddingX: Float = 0f,
    paddingY: Float = 0f,
    alpha: Float = 1f,
    scale: Float = 1f
) {
    commands.add(WindowWatermarkCommand(texture, corner, paddingX, paddingY, alpha, scale))
}

@JvmOverloads
fun LayoutScope.triSliceImageButton(
    id: String,
    left: ImGuiTexture,
    middle: ImGuiTexture,
    right: ImGuiTexture,
    width: Float,
    height: Float = 0f,
    tileMiddle: Boolean = true,
    label: String? = null,
    labelColor: Int = ImGuiColors.WHITE,
    onClick: () -> Unit
) {
    commands.add(TriSliceImageButtonCommand(id, left, middle, right, width, height, tileMiddle, label, labelColor, onClick))
}

@JvmOverloads
fun LayoutScope.triSliceImageButtonFromGraphics(
    id: String,
    leftGraphicId: Int,
    middleGraphicId: Int,
    rightGraphicId: Int,
    width: Float,
    height: Float = 0f,
    tileMiddle: Boolean = true,
    label: String? = null,
    labelColor: Int = ImGuiColors.WHITE,
    onClick: () -> Unit
) {
    commands.add(TriSliceImageButtonFromIdsCommand(id, leftGraphicId, middleGraphicId, rightGraphicId, width, height, tileMiddle, label, labelColor, onClick))
}

@JvmOverloads
fun LayoutScope.windowGraphicSkin(skin: GraphicNineSlice, tileEdges: Boolean = true, tileCenter: Boolean = true) {
    commands.add(WindowNineSliceCommand(skin, tileEdges, tileCenter))
}

@JvmOverloads
fun LayoutScope.windowGraphicSkinFromIds(
    topLeft: Int,
    top: Int,
    topRight: Int,
    left: Int,
    center: Int,
    right: Int,
    bottomLeft: Int,
    bottom: Int,
    bottomRight: Int,
    tileEdges: Boolean = true,
    tileCenter: Boolean = true
) {
    val skin = GraphicNineSlice.fromGraphicIds(topLeft, top, topRight, left, center, right, bottomLeft, bottom, bottomRight)
    commands.add(WindowNineSliceCommand(skin, tileEdges, tileCenter))
}

@JvmOverloads
inline fun LayoutScope.windowGraphicSkinContent(
    skin: GraphicNineSlice,
    tileEdges: Boolean = true,
    tileCenter: Boolean = true,
    block: ChildScope.() -> Unit
) {
    commands.add(WindowNineSliceCommand(skin, tileEdges, tileCenter))
    commands.add(SetCursorPosCommand(skin.left.width.toFloat(), skin.top.height.toFloat()))
    val scope = ChildScope();
    commands.add(BeginChildCommand(
        id = "__skin_content__",
        width = -skin.right.width.toFloat(),
        height = -skin.bottom.height.toFloat(),
        result = AtomicReference(false),
        childFlags = 0,
        windowFlags = 0
    ))
    scope.block()
    commands.addAll(scope.commands)
    commands.add(EndChildCommand())
}

@JvmOverloads
fun LayoutScope.applyBackgroundOverlay(
    overlay: ImGuiTexture,
    alpha: Float,
    tiled: Boolean = true
) {
    commands.add(WindowBackgroundOverlayCommand(overlay, alpha, tiled))
}

@JvmOverloads
inline fun LayoutScope.windowGraphicSkinContentFromIds(
    topLeft: Int,
    top: Int,
    topRight: Int,
    left: Int,
    center: Int,
    right: Int,
    bottomLeft: Int,
    bottom: Int,
    bottomRight: Int,
    tileEdges: Boolean = true,
    tileCenter: Boolean = true,
    block: ChildScope.() -> Unit
) {
    val skin = GraphicNineSlice.fromGraphicIds(topLeft, top, topRight, left, center, right, bottomLeft, bottom, bottomRight)
    windowGraphicSkinContent(skin, tileEdges, tileCenter, block)
}

@JvmOverloads
inline fun LayoutScope.tabBar(
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

@JvmOverloads
inline fun LayoutScope.scrollableWithGraphicScrollbar(
    id: String,
    width: Float = 0f,
    height: Float = 0f,
    trackGraphicId: Int,
    thumbGraphicId: Int,
    tileTrack: Boolean = true,
    minThumbPx: Float = 24f,
    childFlags: Int = 0,
    windowFlags: Int = 0,
    block: ChildScope.() -> Unit
) {
    val trackW = try { (Cache.graphic(trackGraphicId)?.maxWidth ?: 1).toFloat().coerceAtLeast(1f) } catch (_: Throwable) { 12f }
    commands.add(PushStyleVarFloatCommand(ImGuiStyleVar.ScrollbarSize, trackW))

    val result = AtomicReference(false)
    commands.add(BeginChildCommand(id, width, height, result, childFlags, windowFlags))
    val scope = ChildScope(); scope.block(); commands.addAll(scope.commands)
    commands.add(OverlayVScrollbarCommand(trackGraphicId, thumbGraphicId, tileTrack, minThumbPx, drawAboveAll = true))
    commands.add(EndChildCommand())
    commands.add(PopStyleVarCommand(1))
}

@JvmOverloads
fun LayoutScope.indent(width: Float = 0f) { commands.add(IndentCommand(width)) }
@JvmOverloads
fun LayoutScope.unindent(width: Float = 0f) { commands.add(UnindentCommand(width)) }
fun LayoutScope.setNextItemWidth(width: Float) { commands.add(SetNextItemWidthCommand(width)) }
fun LayoutScope.setCursorPos(x: Float, y: Float) { commands.add(SetCursorPosCommand(x, y)) }
fun LayoutScope.setCursorPosX(x: Float) { commands.add(SetCursorPosXCommand(x)) }
fun LayoutScope.setCursorPosY(y: Float) { commands.add(SetCursorPosYCommand(y)) }
fun LayoutScope.alignTextToFramePadding() { commands.add(AlignTextToFramePaddingCommand()) }

@JvmOverloads
fun LayoutScope.columns(count: Int = 1, id: String? = null, border: Boolean = true) { commands.add(ColumnsCommand(count, id, border)) }
fun LayoutScope.nextColumn() { commands.add(NextColumnCommand()) }
fun LayoutScope.setColumnWidth(columnIndex: Int, width: Float) { commands.add(SetColumnWidthCommand(columnIndex, width)) }

fun LayoutScope.treePush(id: String) { commands.add(TreePushCommand(id)) }
fun LayoutScope.treePop() { commands.add(TreePopCommand()) }

/**
 * A disclosure header that reports its open state instead of owning its body, for content that cannot live
 * where the header sits - a table cell, say, which ImGui clips to its column.
 *
 * Draws ImGui's own arrow, which is vector geometry rather than a font glyph, so it survives the default
 * font's limited range. Reports the previous frame's state: a command cannot answer in the frame it is
 * recorded, and the state write is queued after the header closes so a shut node can still be reopened.
 */
@JvmOverloads
fun LayoutScope.treeNodeToggle(label: String, flags: Int = ImGuiTreeNodeFlags.None): Boolean {
    val last = CommandRenderer.getSharedState("treeToggle_$label") { boolState(false) }
    val result = AtomicReference(false)
    commands.add(TreeNodeCommand(label, flags or ImGuiTreeNodeFlags.NoTreePushOnOpen, result))
    commands.add(EndUnpushedTreeNodeCommand)
    commands.add(SetBoolStateFromRefCommand(last, result))
    return last.value
}

/**
 * Frameless disclosure: an arrow + label whose body renders only while open. Unlike the
 * [collapsingHeader] overload below it draws no frame and opens no bordered child, so rows stay flush
 * with their container instead of nesting a box inside a box.
 */
@JvmOverloads
inline fun LayoutScope.treeNode(label: String, flags: Int = ImGuiTreeNodeFlags.None, block: ChildScope.() -> Unit) {
    commands.add(TreeNodeCommand(label, flags, AtomicReference(false)))
    val scope = ChildScope()
    scope.block()
    commands.addAll(scope.commands)
    commands.add(EndTreeNodeCommand())
}

/**
 * Disclosure with its body indented under the header.
 *
 * The header already draws its own frame, so the body is only indented rather than boxed - wrapping it in a
 * bordered child as well nests one frame inside another and reads as two unrelated containers.
 */
@JvmOverloads
inline fun LayoutScope.collapsingHeader(label: String, flags: Int = 0, block: ChildScope.() -> Unit) {
    commands.add(CollapsingHeaderCommand(label, flags, AtomicReference(false)))
    commands.add(IndentCommand())

    val scope = ChildScope()
    scope.block()
    commands.addAll(scope.commands)

    commands.add(UnindentCommand())
    commands.add(EndCollapsingHeaderCommand())
}

fun LayoutScope.pushStyleVar(styleVar: ImGuiStyleVar, value: Float) { commands.add(PushStyleVarFloatCommand(styleVar, value)) }
fun LayoutScope.pushStyleVar(styleVar: ImGuiStyleVar, x: Float, y: Float) { commands.add(PushStyleVarVec2Command(styleVar, x, y)) }
@JvmOverloads
fun LayoutScope.popStyleVar(count: Int = 1) { commands.add(PopStyleVarCommand(count)) }
fun LayoutScope.pushStyleColor(colorIndex: ImGuiCol, color: Int) { commands.add(PushStyleColorCommand(colorIndex, color)) }
@JvmOverloads
fun LayoutScope.popStyleColor(count: Int = 1) { commands.add(PopStyleColorCommand(count)) }

fun LayoutScope.selectable(label: String, isSelected: Boolean, onClick: () -> Unit) {
    commands.add(SelectableActionValueCommand(label, isSelected, onClick))
}
fun LayoutScope.pushItemWidth(width: Float) { commands.add(PushItemWidthCommand(width)) }
fun LayoutScope.popItemWidth() { commands.add(PopItemWidthCommand()) }

@JvmOverloads
inline fun LayoutScope.child(
    id: String,
    width: Float = 0f,
    height: Float = 0f,
    childFlags: Int = 0,
    windowFlags: Int = 0,
    block: ChildScope.() -> Unit
) {
    val result = AtomicReference(false)
    commands.add(BeginChildCommand(id, width, height, result, childFlags, windowFlags))
    val scope = ChildScope(); scope.block(); commands.addAll(scope.commands)
    commands.add(EndChildCommand())
}

@JvmOverloads
fun LayoutScope.xpProgressBar(
    skill: Skill,
    width: Float = -1f,
    height: Float = 19f,
    padding: Float = 0f
) {
    // A negative width is resolved by ImGui against the live content region at draw time. Measuring it
    // here instead would read a deferred query before it has run, and every bar would come out 100px.
    val actualWidth = if (width < 0) -(padding * 2f).coerceAtLeast(1f) else width

    val currentXp = getXp(skill)
    val currentLevel = getLevelForXp(currentXp)
    val xpForCurrentLevel = getXpForLevel(currentLevel)
    val xpForNextLevel = getXpForLevel(currentLevel + 1)
    val progressXp = currentXp - xpForCurrentLevel
    val totalXpNeeded = xpForNextLevel - xpForCurrentLevel
    val progressPercent = if (totalXpNeeded > 0) progressXp.toFloat() / totalXpNeeded else 0f

    text(if (currentLevel >= 120) "Maxed Level" else "Level $currentLevel")
    if (currentLevel < 120)
        progressBar(progressPercent, actualWidth, height)
}