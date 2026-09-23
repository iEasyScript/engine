package com.projectx.ui.compose.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.compose.OverlayKeyboard
import com.projectx.ui.compose.OverlayText
import com.projectx.ui.compose.components.ActionButton
import com.projectx.ui.compose.components.Divider
import com.projectx.ui.compose.components.Glyph
import com.projectx.ui.compose.components.GlyphIcon
import com.projectx.ui.compose.components.IconButton
import com.projectx.ui.compose.components.IntSlider
import com.projectx.ui.compose.components.NumberInput
import com.projectx.ui.compose.components.PillTabs
import com.projectx.ui.compose.components.TextArea
import com.projectx.ui.compose.components.TextInput
import com.projectx.ui.compose.components.ToggleChip
import com.projectx.ui.compose.components.animatedColor
import com.projectx.ui.compose.components.press
import com.projectx.ui.compose.components.rememberHover
import com.projectx.ui.compose.theme.Fonts
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.OverlayTheme
import com.projectx.ui.compose.theme.Palette
import com.projectx.ui.compose.theme.fromImGuiColor
import kotlin.math.abs
import kotlin.math.roundToInt

private val LocalCard = staticCompositionLocalOf { "" }

private const val CARD_GAP = 12
private const val CARD_MARGIN = 20
private const val STACK_TOP = 80

/**
 * Every script window as a card. Cards stack down the right-hand edge until one is dragged, after which it stays
 * where it was put. Scripts' own window positions are not used: they were chosen around the old panel, and followed
 * here they pile every card into the corner the main panel now occupies.
 */
@Composable
fun HudCards() {
    OverlayTheme {
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    OverlayKeyboard.blurUnlessInside(down.position.x, down.position.y, "cards")
                }
            },
        ) {
            val cards = HudState.panels.map { CardSpec(it.key, it.title, null, null) { ScriptPanelContent(it) } } +
                HudState.windows.map { window -> CardSpec(window.key, window.title, window.closeKey, window.width) { Nodes(window.children) } }
            val (placed, stacked) = cards.partition { HudState.positions[it.key] != null }
            Column(
                Modifier.align(Alignment.TopEnd).padding(top = STACK_TOP.dp, end = CARD_MARGIN.dp),
                verticalArrangement = Arrangement.spacedBy(CARD_GAP.dp),
                horizontalAlignment = Alignment.End,
            ) {
                stacked.forEach { card -> key(card.key) { Card(card, Modifier) } }
            }
            placed.forEach { card ->
                val position = HudState.positions.getValue(card.key)
                key(card.key) { Card(card, Modifier.offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }) }
            }
        }
    }
}

private class CardSpec(val key: String, val title: String, val closeKey: String?, val width: Float?, val content: @Composable () -> Unit)

/** A script's own panel, marked as the one composing so a throw from it is pinned on the right script. */
@Composable
private fun ScriptPanelContent(panel: ScriptPanel) {
    HudState.composing = panel.key
    panel.panel.Panel()
    HudState.composing = null
}

@Composable
private fun Card(window: CardSpec, placement: Modifier) {
    val collapsed = HudState.collapsed[window.key] == true
    val shape = RoundedCornerShape(12.dp)
    val type = LocalType.current
    CompositionLocalProvider(LocalCard provides window.key) {
        Column(
            placement
                .widthIn(min = 240.dp, max = (window.width?.coerceIn(240f, 640f) ?: 460f).dp)
                .onGloballyPositioned { HudState.cards[window.key] = it.boundsInRoot() }
                .clip(shape)
                .background(Palette.ground)
                .border(1.dp, Palette.line, shape),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(Palette.surface)
                    .pointerInput(window.key) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val from = HudState.positions[window.key] ?: HudState.cards[window.key]?.topLeft ?: Offset.Zero
                            HudState.positions[window.key] = from + drag
                        }
                    }
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(window.title, style = type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(if (collapsed) Glyph.ChevronDown else Glyph.ChevronUp, { HudState.collapsed[window.key] = !collapsed }, size = 26.dp)
                window.closeKey?.let { key -> IconButton(Glyph.Close, { click(key) }, size = 26.dp) }
            }
            if (!collapsed) {
                Divider()
                Column(
                    Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    window.content()
                }
                HudState.hints[window.key]?.let { hint ->
                    Divider()
                    BasicText(hint, style = type.label.copy(color = Palette.muted), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun Nodes(nodes: List<HudNode>) {
    nodes.forEach { Node(it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Node(node: HudNode) {
    val type = LocalType.current
    when (node) {
        is RowNode -> FlowRow(
            Modifier.padding(start = (node.indent * 14).dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) { node.items.forEach { Node(it) } }
        is TextNode -> BasicText(node.text, style = type.label.copy(color = textColor(node.color), fontSize = 13.sp))
        is ButtonNode -> ActionButton(node.label.ifEmpty { " " }, { click(node.key) }, height = if (node.small) 26.dp else 30.dp)
        is ArrowNode -> IconButton(arrow(node.direction), { click(node.key) }, size = 26.dp)
        is CheckboxNode -> Check(node.label, node.checked) { HudState.actions.booleans[node.key]?.invoke(it) }
        is TextFieldNode -> Field(node)
        is NumberNode -> Labelled(node.label) {
            NumberInput(node.value, { HudState.actions.ints[node.key]?.invoke(it) }, step = node.step.coerceAtLeast(1), width = 150.dp)
        }
        is FloatNode -> Labelled(node.label) { FloatField(node) }
        is ChoiceNode -> Labelled(node.label) { Choice(node.key, node.items, node.index) { HudState.actions.ints[node.key]?.invoke(it) } }
        is SelectableNode -> Selectable(node.label, node.selected) { click(node.key) }
        is ProgressNode -> Progress(node)
        is ImageNode -> Picture(node)
        is ColorNode -> Labelled(node.label) { Swatches(node) }
        is RangeNode -> Labelled(node.label) {
            Column {
                val span = (node.max - node.min).takeIf { it > 0f } ?: 1f
                IntSlider(((node.low - node.min) / span * 1000).roundToInt(), 0..1000, { HudState.actions.ranges[node.key]?.invoke(node.min + it / 1000f * span, node.high) }, width = 180.dp)
                IntSlider(((node.high - node.min) / span * 1000).roundToInt(), 0..1000, { HudState.actions.ranges[node.key]?.invoke(node.low, node.min + it / 1000f * span) }, width = 180.dp)
            }
        }
        is MultiSelectNode -> Labelled(node.label) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                node.items.forEachIndexed { i, item ->
                    ToggleChip(item, i in node.selected) { on -> HudState.actions.sets[node.key]?.invoke(if (on) node.selected + i else node.selected - i) }
                }
            }
        }
        is TooltipNode -> WithHint(node.tooltip) { Node(node.target) }
        SeparatorNode -> Divider(Modifier.padding(vertical = 2.dp))
        SpacingNode -> Spacer(Modifier.height(4.dp))
        is GroupNode -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Nodes(node.children) }
        is ChildNode -> Column(
            Modifier
                .then(if (node.height > 0f) Modifier.heightIn(max = node.height.dp).verticalScroll(rememberScrollState()) else Modifier)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { Nodes(node.children) }
        is FoldNode -> Fold(node)
        is TabsNode -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillTabs(node.tabs.indices.toList(), node.selected, { i ->
                val tab = node.tabs[i]
                if (tab.buttonKey != null) click(tab.buttonKey) else HudMemory.tabs[node.key] = i
            }, { node.tabs[it].label })
            node.tabs.getOrNull(node.selected)?.let { Nodes(it.children) }
        }
        is ComboNode -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Labelled(node.label) {
                ActionButton(node.preview.ifEmpty { "Choose" }, { HudMemory.open[node.key] = !node.open }, icon = if (node.open) Glyph.ChevronUp else Glyph.ChevronDown, height = 30.dp)
            }
            if (node.open) Column(Modifier.padding(start = 12.dp)) { Nodes(node.children) }
        }
        is TableNode -> Table(node)
        is DrawingNode -> Drawing(node)
    }
}

@Composable
private fun Labelled(label: String, control: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        control()
        if (label.isNotBlank()) BasicText(label, style = LocalType.current.label.copy(color = Palette.muted))
    }
}

@Composable
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val hover = rememberHover()
    Row(
        Modifier.press(hover) { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(animatedColor(if (checked) Palette.amber else if (hover.hovered) Palette.hover else Palette.raised))
                .border(1.dp, if (checked) Palette.amber else Palette.lineStrong, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) GlyphIcon(Glyph.Check, Palette.onAmber, 10.dp)
        }
        if (label.isNotBlank()) BasicText(label, style = LocalType.current.label)
    }
}

/** Text a script owns: the script's value shows until typing starts, then the typed text is sent on every key. */
@Composable
private fun Field(node: TextFieldNode) {
    var draft by remember(node.key) { mutableStateOf<String?>(null) }
    val latest by rememberUpdatedState(node.value)
    val field = remember(node.key) {
        OverlayText(
            read = { draft ?: latest },
            write = { value -> draft = value; HudState.actions.strings[node.key]?.invoke(value) },
            maxLength = 1024,
            multiline = node.multiline,
            onDone = { draft = null },
        )
    }
    Labelled(node.label) {
        if (node.multiline) TextArea(field, "", Modifier.width(300.dp)) else TextInput(field, "", 200.dp)
    }
}

@Composable
private fun FloatField(node: FloatNode) {
    if (node.slider && node.max > node.min) {
        val span = node.max - node.min
        Row(verticalAlignment = Alignment.CenterVertically) {
            IntSlider(((node.value - node.min) / span * 1000).roundToInt(), 0..1000, { HudState.actions.floats[node.key]?.invoke(node.min + it / 1000f * span) }, width = 160.dp)
        }
        return
    }
    var draft by remember(node.key) { mutableStateOf<String?>(null) }
    val latest by rememberUpdatedState(node.value)
    val field = remember(node.key) {
        OverlayText(
            read = { draft ?: "%.3f".format(latest) },
            write = { draft = it },
            maxLength = 16,
            accepts = { it.isDigit() || it == '.' || it == '-' },
            onFocus = { draft = "%.3f".format(latest) },
            onDone = {
                draft?.toFloatOrNull()?.let { HudState.actions.floats[node.key]?.invoke(it) }
                draft = null
            },
        )
    }
    TextInput(field, "", 110.dp, mono = true)
}

@Composable
private fun Choice(key: String, items: List<String>, index: Int, onPick: (Int) -> Unit) {
    var open by remember(key) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ActionButton(items.getOrNull(index) ?: "Choose", { open = !open }, icon = if (open) Glyph.ChevronUp else Glyph.ChevronDown, height = 30.dp)
        if (open) {
            // Listed inline: a card only shows its own area, so a popup list would be cut off at its edge.
            Column(Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.raised).border(1.dp, Palette.line, RoundedCornerShape(8.dp)).padding(4.dp)) {
                items.forEachIndexed { i, item ->
                    Selectable(item, i == index) {
                        open = false
                        onPick(i)
                    }
                }
            }
        }
    }
}

@Composable
private fun Selectable(label: String, selected: Boolean, onClick: () -> Unit) {
    val hover = rememberHover()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(animatedColor(if (selected) Palette.hover else if (hover.hovered) Palette.raised else Color.Transparent))
            .press(hover, onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(label, style = LocalType.current.label.copy(color = if (selected) Palette.amber else Palette.text), modifier = Modifier.weight(1f))
        if (selected) GlyphIcon(Glyph.Check, Palette.amber, 10.dp)
    }
}

@Composable
private fun Progress(node: ProgressNode) {
    val fraction = node.fraction.coerceIn(0f, 1f)
    val height = if (node.height > 0f) node.height.dp else 18.dp
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(5.dp)).background(Palette.raised), contentAlignment = Alignment.Center) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(fraction).height(height).background(Palette.amber.copy(alpha = 0.85f)))
        node.overlay?.let { BasicText(it, style = LocalType.current.dataSmall.copy(color = Palette.text)) }
    }
}

@Composable
private fun Picture(node: ImageNode) {
    val bitmap = TexturePixels.bitmap(node.texture)
    val size = Modifier.size(node.width.coerceAtLeast(1f).dp, node.height.coerceAtLeast(1f).dp)
    val tint = if (node.tint == -1) null else ColorFilter.tint(node.tint.fromImGuiColor(), BlendMode.Modulate)
    val hover = rememberHover()
    val clickable = node.key?.let { key -> Modifier.clip(RoundedCornerShape(6.dp)).background(if (hover.hovered) Palette.hover else Color.Transparent).press(hover) { click(key) } } ?: Modifier
    Box(clickable) {
        if (bitmap != null) Image(bitmap, contentDescription = null, modifier = size, colorFilter = tint)
        else Box(size.background(Palette.raised))
    }
}

private val palette = listOf(0xFFF2A93B, 0xFFF7D154, 0xFF3FD48E, 0xFF2EC4B6, 0xFF6FA8F0, 0xFFB48AF0, 0xFFF0716C, 0xFFFFFFFF, 0xFF8C95A8, 0xFF000000)

@Composable
private fun Swatches(node: ColorNode) {
    val current = node.color.fromImGuiColor()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        palette.forEach { argb ->
            val swatch = Color(argb)
            val hover = rememberHover()
            val chosen = swatch.copy(alpha = 1f) == current.copy(alpha = 1f)
            Box(
                Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(swatch)
                    .border(if (chosen || hover.hovered) 2.dp else 1.dp, if (chosen) Palette.text else Palette.lineStrong, RoundedCornerShape(4.dp))
                    .press(hover) { HudState.actions.colors[node.key]?.invoke(ImGuiColors.rgba(swatch.red, swatch.green, swatch.blue, current.alpha)) },
            )
        }
    }
}

@Composable
private fun Fold(node: FoldNode) {
    val hover = rememberHover()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(animatedColor(if (hover.hovered) Palette.raised else if (node.tree) Color.Transparent else Palette.surface))
                .press(hover) { HudMemory.open[node.key] = !node.open }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlyphIcon(if (node.open) Glyph.ChevronDown else Glyph.ChevronRight, Palette.muted, 9.dp)
            BasicText(node.label, style = LocalType.current.bodyStrong)
        }
        if (node.open && node.children.isNotEmpty()) {
            Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Nodes(node.children) }
        }
    }
}

@Composable
private fun Table(node: TableNode) {
    val type = LocalType.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (node.headers) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                node.columns.forEach { column ->
                    BasicText(column.label.uppercase(), style = type.eyebrow, modifier = columnModifier(column))
                }
            }
        }
        node.rows.forEach { cells ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                node.columns.forEachIndexed { i, column ->
                    Column(columnModifier(column), verticalArrangement = Arrangement.spacedBy(4.dp)) { Nodes(cells.getOrNull(i).orEmpty()) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.columnModifier(column: TableNode.Column): Modifier =
    if (column.width > 0f) Modifier.width(column.width.dp) else Modifier.weight(1f)

@Composable
private fun Drawing(node: DrawingNode) {
    val measurer = rememberTextMeasurer()
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(node.height.coerceAtLeast(8f).dp)
            .onSizeChanged { HudMemory.drawingWidths[node.key] = it.width.toFloat() },
    ) { node.shapes.forEach { drawShape(it, measurer) } }
}

@Composable
private fun WithHint(hint: String, content: @Composable () -> Unit) {
    val card = LocalCard.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) HudState.hints[card] = hint else if (HudState.hints[card] == hint) HudState.hints.remove(card)
    }
    Box(Modifier.hoverable(source)) { content() }
}

/** The world layer: everything drawn into the scene, behind every window and never taking a click. */
@Composable
fun WorldLayer() {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) { HudState.world.forEach { drawShape(it, measurer) } }
}

private val shadow = Color(0xB3000000)

fun DrawScope.drawShape(shape: Shape, measurer: TextMeasurer) {
    when (shape) {
        is Shape.Line -> drawLine(shape.color.fromImGuiColor(), Offset(shape.x1, shape.y1), Offset(shape.x2, shape.y2), shape.thickness.coerceAtLeast(1f))
        is Shape.Rect -> {
            val topLeft = Offset(minOf(shape.x1, shape.x2), minOf(shape.y1, shape.y2))
            val size = Size(abs(shape.x2 - shape.x1), abs(shape.y2 - shape.y1))
            val corner = CornerRadius(shape.rounding, shape.rounding)
            if (shape.filled) drawRoundRect(shape.color.fromImGuiColor(), topLeft, size, corner)
            else drawRoundRect(shape.color.fromImGuiColor(), topLeft, size, corner, style = Stroke(shape.thickness.coerceAtLeast(1f)))
        }
        is Shape.Circle ->
            if (shape.filled) drawCircle(shape.color.fromImGuiColor(), shape.radius, Offset(shape.x, shape.y))
            else drawCircle(shape.color.fromImGuiColor(), shape.radius, Offset(shape.x, shape.y), style = Stroke(shape.thickness.coerceAtLeast(1f)))
        is Shape.Text -> {
            val layout = measurer.measure(shape.text, TextStyle(fontFamily = Fonts.sans, fontSize = 13.sp, color = shadow))
            // A soft shadow keeps labels readable over any part of the game.
            drawText(layout, color = shadow, topLeft = Offset(shape.x + 1f, shape.y + 1f))
            drawText(layout, color = shape.color.fromImGuiColor(), topLeft = Offset(shape.x, shape.y))
        }
        is Shape.Image -> TexturePixels.bitmap(shape.texture)?.let { bitmap ->
            drawImage(
                bitmap,
                dstOffset = IntOffset(shape.x1.roundToInt(), shape.y1.roundToInt()),
                dstSize = IntSize((shape.x2 - shape.x1).roundToInt().coerceAtLeast(1), (shape.y2 - shape.y1).roundToInt().coerceAtLeast(1)),
                colorFilter = if (shape.tint == -1) null else ColorFilter.tint(shape.tint.fromImGuiColor(), BlendMode.Modulate),
            )
        }
        is Shape.Poly -> {
            if (shape.points.size < 4) return
            val path = Path().apply {
                moveTo(shape.points[0], shape.points[1])
                var i = 2
                while (i + 1 < shape.points.size) {
                    lineTo(shape.points[i], shape.points[i + 1])
                    i += 2
                }
                if (shape.closed || shape.filled) close()
            }
            if (shape.filled) drawPath(path, shape.color.fromImGuiColor())
            else drawPath(path, shape.color.fromImGuiColor(), style = Stroke(shape.thickness.coerceAtLeast(1f)))
        }
    }
}

private fun click(key: String) {
    HudState.actions.clicks[key]?.invoke()
}

private fun arrow(direction: Int) = when (direction) {
    0 -> Glyph.ChevronLeft
    1 -> Glyph.ChevronRight
    2 -> Glyph.ChevronUp
    else -> Glyph.ChevronDown
}

/** Scripts colour text with the old theme's constants; those map onto this theme, anything else is used as given. */
private fun textColor(color: Int?): Color = when (color) {
    null -> Palette.text
    ImGuiColors.TEXT_ACCENT, ImGuiColors.ACCENT_PRIMARY -> Palette.amber
    ImGuiColors.ACCENT_SUCCESS -> Palette.running
    ImGuiColors.ACCENT_ERROR -> Palette.stop
    ImGuiColors.ACCENT_WARNING -> Palette.amber
    ImGuiColors.TEXT_DISABLED, ImGuiColors.TEXT_SECONDARY -> Palette.muted
    else -> color.fromImGuiColor()
}
