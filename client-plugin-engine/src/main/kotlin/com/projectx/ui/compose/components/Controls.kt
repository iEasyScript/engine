package com.projectx.ui.compose.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.Palette

class Hover(val source: MutableInteractionSource, hovered: State<Boolean>) {
    val hovered by hovered
}

@Composable
fun rememberHover(): Hover {
    val source = remember { MutableInteractionSource() }
    return Hover(source, source.collectIsHoveredAsState())
}

fun Modifier.press(hover: Hover, onClick: () -> Unit): Modifier =
    clickable(interactionSource = hover.source, indication = null, onClick = onClick)

@Composable
fun animatedColor(target: Color) = animateColorAsState(target, tween(120)).value

enum class ButtonTone { Primary, Danger, Quiet }

@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.Quiet,
    icon: Glyph? = null,
    height: Dp = 36.dp,
) {
    val hover = rememberHover()
    val (fill, fillHover, content, border) = when (tone) {
        ButtonTone.Primary -> listOf(Palette.amber, Palette.amberHover, Palette.onAmber, Color.Transparent)
        ButtonTone.Danger -> listOf(Palette.stop.copy(alpha = 0.12f), Palette.stop.copy(alpha = 0.2f), Palette.stop, Palette.stop.copy(alpha = 0.45f))
        ButtonTone.Quiet -> listOf(Palette.raised, Palette.hover, Palette.text, Palette.lineStrong)
    }
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(animatedColor(if (hover.hovered) fillHover else fill))
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .press(hover, onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) GlyphIcon(icon, content, 12.dp)
        BasicText(label, style = LocalType.current.label.copy(color = content, fontSize = if (height >= 40.dp) 13.5.sp else 12.5.sp))
    }
}

@Composable
fun IconButton(glyph: Glyph, onClick: () -> Unit, tint: Color = Palette.muted, activeTint: Color = Palette.text, size: Dp = 28.dp) {
    val hover = rememberHover()
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(7.dp))
            .background(animatedColor(if (hover.hovered) Palette.hover else Color.Transparent))
            .press(hover, onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (hover.hovered) activeTint else tint, size * 0.46f)
    }
}

/** A script's badge: its initials on its category's tint. */
@Composable
fun Monogram(name: String, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    val letters = monogramOf(name)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.26f))
            .background(tint.copy(alpha = 0.14f))
            .border(1.dp, tint.copy(alpha = 0.32f), RoundedCornerShape(size * 0.26f)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            letters,
            style = LocalType.current.heading.copy(color = tint, fontSize = (size.value * 0.36f).sp, textAlign = TextAlign.Center),
        )
    }
}

private val fillerWords = setOf("aio", "of", "the", "and")

/** Two letters that tell scripts apart: initials of the telling words, or the start of a lone one ("AIO Agility" is "Ag"). */
fun monogramOf(name: String): String {
    val words = name.split(' ', '-').filter { it.isNotBlank() && it.lowercase() !in fillerWords }.ifEmpty { listOf(name) }
    return if (words.size == 1) words[0].take(2).replaceFirstChar { it.uppercase() }
    else words.take(2).joinToString("") { it.first().uppercase() }
}

@Composable
fun Pill(label: String, color: Color, dot: Boolean = false) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        BasicText(label, style = LocalType.current.label.copy(color = color, fontSize = 11.5.sp))
    }
}
