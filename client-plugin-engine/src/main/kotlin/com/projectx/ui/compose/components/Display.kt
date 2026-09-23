package com.projectx.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.Palette

/** A headline number with its label above it, for the figures a panel exists to show. */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = Palette.text) {
    val type = LocalType.current
    Column(modifier.widthIn(min = 110.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(label.uppercase(), style = type.eyebrow)
        BasicText(value, style = type.data.copy(color = color, fontSize = type.heading.fontSize))
    }
}

/** How far through something is, 0 to 1, with an optional label written across it. */
@Composable
fun ProgressBar(fraction: Float, label: String? = null, modifier: Modifier = Modifier, color: Color = Palette.amber, height: Dp = 18.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(5.dp)).background(Palette.raised), contentAlignment = Alignment.Center) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).background(color.copy(alpha = 0.85f)))
        if (label != null) BasicText(label, style = LocalType.current.dataSmall.copy(color = Palette.text))
    }
}
