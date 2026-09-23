package com.projectx.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.projectx.ui.compose.LocalSurface
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.Palette
import kotlin.math.roundToInt

@Composable
fun <T> Dropdown(
    selected: T,
    options: List<T>,
    onSelect: (T) -> Unit,
    width: Dp = 200.dp,
    label: (T) -> String = { it.toString() },
) {
    var open by remember { mutableStateOf(false) }
    val hover = rememberHover()
    val type = LocalType.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .width(width)
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(animatedColor(if (hover.hovered || open) Palette.hover else Palette.raised))
                .border(1.dp, if (open) Palette.amber.copy(alpha = 0.7f) else Palette.line, RoundedCornerShape(8.dp))
                .press(hover) { open = !open }
                .padding(start = 11.dp, end = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(label(selected), style = type.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            GlyphIcon(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, 10.dp)
        }
        if (open) {
            Dropped(38, { open = false }) {
                Column(
                    Modifier
                        .width(width)
                        .heightIn(max = 280.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.raised)
                        .border(1.dp, Palette.lineStrong, RoundedCornerShape(10.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(4.dp),
                ) {
                    options.forEach { option ->
                        val optionHover = rememberHover()
                        val isSelected = option == selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (optionHover.hovered) Palette.hover else Color.Transparent)
                                .press(optionHover) {
                                    open = false
                                    onSelect(option)
                                }
                                .padding(horizontal = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BasicText(
                                label(option),
                                style = type.label.copy(color = if (isSelected) Palette.text else Palette.muted),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (isSelected) GlyphIcon(Glyph.Check, Palette.amber, 10.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IntSlider(value: Int, range: IntRange, onValue: (Int) -> Unit, width: Dp = 200.dp, suffix: String = "") {
    var trackWidth by remember { mutableStateOf(1) }
    val update by rememberUpdatedState(onValue)
    val span = (range.last - range.first).coerceAtLeast(1)
    val fraction = ((value - range.first).toFloat() / span).coerceIn(0f, 1f)
    fun pick(x: Float) = update((range.first + (x / trackWidth).coerceIn(0f, 1f) * span).roundToInt())

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .width(width)
                .height(22.dp)
                .onSizeChanged { trackWidth = it.width.coerceAtLeast(1) }
                .pointerInput(range) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pick(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            pick(change.position.x)
                            change.consume()
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Palette.line))
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(CircleShape).background(Palette.amber))
            Box(
                Modifier
                    .offset { IntOffset(((trackWidth - 14) * fraction).roundToInt(), 0) }
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Palette.text),
            )
        }
        BasicText("$value$suffix", style = LocalType.current.data.copy(color = Palette.text), maxLines = 1, modifier = Modifier.width(76.dp))
    }
}

@Composable
fun <T> Segmented(options: List<T>, selected: T, onSelect: (T) -> Unit, label: (T) -> String = { it.toString() }) {
    Row(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.raised)
            .border(1.dp, Palette.line, RoundedCornerShape(8.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            val hover = rememberHover()
            Box(
                Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(animatedColor(if (active) Palette.hover else Color.Transparent))
                    .press(hover) { onSelect(option) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(label(option), style = LocalType.current.label.copy(color = if (active || hover.hovered) Palette.text else Palette.muted))
            }
        }
    }
}

/** The second level of navigation: a row of pills under the header, the active one filled. */
@Composable
fun <T> PillTabs(pages: List<T>, selected: T, onSelect: (T) -> Unit, label: (T) -> String, count: (T) -> Int? = { null }) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        pages.forEach { page ->
            val hover = rememberHover()
            val active = page == selected
            val type = LocalType.current
            Row(
                Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(animatedColor(if (active) Palette.text else if (hover.hovered) Palette.hover else Color.Transparent))
                    .press(hover) { onSelect(page) }
                    .padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                BasicText(label(page), style = type.label.copy(color = if (active) Palette.ground else Palette.muted))
                count(page)?.let { BasicText("$it", style = type.dataSmall.copy(color = Palette.faint)) }
            }
        }
    }
}

private val swatches = listOf(
    0xFFF2A93B, 0xFFF7D154, 0xFF9BE15D, 0xFF3FD48E, 0xFF2EC4B6, 0xFF4CC9F0, 0xFF6FA8F0, 0xFF7B6CF6,
    0xFFB48AF0, 0xFFF06BC5, 0xFFF0716C, 0xFFE8E8E8, 0xFF9AA3B5, 0xFF5A6377, 0xFF1A1F2B, 0xFF000000,
).map { Color(it) }

/** A colour, set from a small palette - enough for markers and overlays, which is all the overlay colours. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorField(color: Color, onColor: (Color) -> Unit, withAlpha: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val hover = rememberHover()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(animatedColor(if (hover.hovered || open) Palette.hover else Palette.raised))
                .border(1.dp, Palette.line, RoundedCornerShape(8.dp))
                .press(hover) { open = !open }
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(color).border(1.dp, Palette.lineStrong, RoundedCornerShape(5.dp)))
            BasicText(hex(color, withAlpha), style = LocalType.current.data.copy(color = Palette.text))
        }
        if (open) {
            Dropped(38, { open = false }) {
                Column(
                    Modifier
                        .width(236.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.raised)
                        .border(1.dp, Palette.lineStrong, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        swatches.forEach { swatch ->
                            val swatchHover = rememberHover()
                            val chosen = swatch.copy(alpha = 1f) == color.copy(alpha = 1f)
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(swatch)
                                    .border(
                                        if (chosen || swatchHover.hovered) 2.dp else 1.dp,
                                        if (chosen) Palette.text else if (swatchHover.hovered) Palette.muted else Palette.lineStrong,
                                        RoundedCornerShape(6.dp),
                                    )
                                    .press(swatchHover) { onColor(swatch.copy(alpha = color.alpha)) },
                            )
                        }
                    }
                    if (withAlpha) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BasicText("Opacity", style = LocalType.current.label.copy(color = Palette.muted))
                            IntSlider((color.alpha * 100).roundToInt(), 0..100, { onColor(color.copy(alpha = it / 100f)) }, width = 150.dp, suffix = "%")
                        }
                    }
                }
            }
        }
    }
}

private fun hex(color: Color, withAlpha: Boolean): String {
    fun channel(v: Float) = (v * 255).roundToInt().toString(16).padStart(2, '0').uppercase()
    val rgb = "#" + channel(color.red) + channel(color.green) + channel(color.blue)
    return if (withAlpha) "$rgb  ${(color.alpha * 100).roundToInt()}%" else rgb
}

/** A button that opens a list of choices and forgets the pick once made - for "add one of these" menus. */
@Composable
fun <T> MenuButton(label: String, options: List<T>, onPick: (T) -> Unit, icon: Glyph? = Glyph.Plus, width: Dp = 220.dp, optionLabel: (T) -> String = { it.toString() }) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionButton(label, { open = !open }, icon = icon, height = 30.dp)
        if (open) {
            Dropped(34, { open = false }) {
                Column(
                    Modifier
                        .width(width)
                        .heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.raised)
                        .border(1.dp, Palette.lineStrong, RoundedCornerShape(10.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(4.dp),
                ) {
                    options.forEach { option ->
                        val hover = rememberHover()
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(30.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (hover.hovered) Palette.hover else Color.Transparent)
                                .press(hover) {
                                    open = false
                                    onPick(option)
                                }
                                .padding(horizontal = 9.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            BasicText(optionLabel(option), style = LocalType.current.label, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Where a picker's list opens: a popup over everything in the main panel, but inline below the picker on the card
 * layer, where each card shows only its own area and a popup reaching past its edge would be cut off.
 */
@Composable
private fun Dropped(offsetY: Int, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    if (LocalSurface.current == "cards") {
        content()
    } else {
        Popup(offset = IntOffset(0, offsetY), onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) { content() }
    }
}
