package com.projectx.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.projectx.ui.backend.dsl.ImGuiState
import com.projectx.ui.compose.LocalSurface
import com.projectx.ui.compose.OverlayClock
import com.projectx.ui.compose.OverlayKeyboard
import com.projectx.ui.compose.OverlayText
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.Palette

@Composable
fun SearchField(field: OverlayText, placeholder: String, width: Dp, modifier: Modifier = Modifier) =
    TextInput(field, placeholder, width, modifier, leading = Glyph.Search, clearable = true)

@Composable
fun SearchField(setting: ImGuiState<String>, placeholder: String, width: Dp, modifier: Modifier = Modifier) =
    SearchField(remember(setting) { OverlayText.of(setting) }, placeholder, width, modifier)

@Composable
fun TextInput(
    field: OverlayText,
    placeholder: String,
    width: Dp,
    modifier: Modifier = Modifier,
    leading: Glyph? = null,
    clearable: Boolean = false,
    mono: Boolean = false,
) {
    val focused = OverlayKeyboard.focused === field
    val surface = LocalSurface.current
    val hover = rememberHover()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val type = LocalType.current
    val style = if (mono) type.data.copy(color = Palette.text) else type.label

    Row(
        modifier = modifier
            .width(width)
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Palette.surface else Palette.raised)
            .border(1.dp, animatedColor(if (focused) Palette.amber.copy(alpha = 0.7f) else if (hover.hovered) Palette.lineStrong else Palette.line), RoundedCornerShape(8.dp))
            .onGloballyPositioned { bounds = it.boundsInRoot(); OverlayKeyboard.updateBounds(field, bounds) }
            .press(hover) { OverlayKeyboard.focus(field, bounds, surface) }
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (leading != null) GlyphIcon(leading, if (focused) Palette.text else Palette.faint, 13.dp)
        Box(Modifier.weight(1f)) {
            if (field.text.isEmpty() && !focused) {
                BasicText(placeholder, style = type.label.copy(color = Palette.faint), maxLines = 1)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Only the end is shown once the text outgrows the field, since that is where typing happens.
                    BasicText(field.text, style = style, maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f, fill = false))
                    if (focused) Caret()
                }
            }
        }
        if (clearable && field.text.isNotEmpty()) IconButton(Glyph.Close, { field.text = "" }, size = 22.dp)
    }
}

/**
 * A whole number typed in, with a step either side. The typed text is only taken once typing ends and only when it
 * is a number inside [range], so half-typed values never reach whatever reads it.
 */
@Composable
fun NumberInput(value: Int, onValue: (Int) -> Unit, range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE, width: Dp = 132.dp, step: Int = 1) {
    var draft by remember { mutableStateOf<String?>(null) }
    val current by rememberUpdatedState(value)
    val commit by rememberUpdatedState(onValue)
    val field = remember(range) {
        OverlayText(
            read = { draft ?: current.toString() },
            write = { draft = it },
            maxLength = 11,
            accepts = { it.isDigit() || it == '-' },
            onFocus = { draft = current.toString() },
            onDone = {
                draft?.toIntOrNull()?.let { if (it in range && it != current) commit(it) }
                draft = null
            },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StepButton(Glyph.Minus, enabled = value - step >= range.first) { onValue(value - step) }
        TextInput(field, "", width - 64.dp, mono = true)
        StepButton(Glyph.Plus, enabled = value + step <= range.last) { onValue(value + step) }
    }
}

@Composable
private fun StepButton(glyph: Glyph, enabled: Boolean, onClick: () -> Unit) {
    val hover = rememberHover()
    Box(
        Modifier
            .width(28.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(animatedColor(if (hover.hovered && enabled) Palette.hover else Palette.raised))
            .border(1.dp, Palette.line, RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.press(hover, onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (enabled) Palette.muted else Palette.faint.copy(alpha = 0.5f), 10.dp)
    }
}

@Composable
private fun Caret() {
    Box(
        Modifier
            .padding(start = 1.dp)
            .width(1.5.dp)
            .height(15.dp)
            .background(if (OverlayClock.caretOn) Palette.amber else Color.Transparent),
    )
}

/** Several lines of text; Enter starts a new line and a click elsewhere ends typing. */
@Composable
fun TextArea(field: OverlayText, placeholder: String, modifier: Modifier = Modifier, minHeight: Dp = 72.dp) {
    val focused = OverlayKeyboard.focused === field
    val surface = LocalSurface.current
    val hover = rememberHover()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val type = LocalType.current
    Box(
        modifier
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Palette.surface else Palette.raised)
            .border(1.dp, animatedColor(if (focused) Palette.amber.copy(alpha = 0.7f) else if (hover.hovered) Palette.lineStrong else Palette.line), RoundedCornerShape(8.dp))
            .onGloballyPositioned { bounds = it.boundsInRoot(); OverlayKeyboard.updateBounds(field, bounds) }
            .press(hover) { OverlayKeyboard.focus(field, bounds, surface) }
            .padding(horizontal = 11.dp, vertical = 8.dp),
    ) {
        if (field.text.isEmpty() && !focused) {
            BasicText(placeholder, style = type.label.copy(color = Palette.faint))
        } else {
            BasicText(field.text + if (focused && OverlayClock.caretOn) "|" else "", style = type.label.copy(lineHeight = type.body.lineHeight))
        }
    }
}
