package com.projectx.ui.compose.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.projectx.ui.backend.dsl.ImGuiState
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.Palette

/** A screen's body: padded, scrolling, with a slim scrollbar that only takes space when there is more to see. */
@Composable
fun ScreenScroll(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 20.dp, end = 24.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
        CompositionLocalProvider(LocalScrollbarStyle provides overlayScrollbarStyle()) {
            VerticalScrollbar(
                rememberScrollbarAdapter(scroll),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp, horizontal = 4.dp),
            )
        }
    }
}

@Composable
fun overlayScrollbarStyle() = remember {
    ScrollbarStyle(
        minimalHeight = 32.dp,
        thickness = 6.dp,
        shape = RoundedCornerShape(3.dp),
        hoverDurationMillis = 150,
        unhoverColor = Palette.lineStrong,
        hoverColor = Palette.faint,
    )
}

@Composable
fun Card(modifier: Modifier = Modifier, padding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.surface)
            .border(1.dp, Palette.line, RoundedCornerShape(12.dp))
            .padding(padding.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** A titled group of controls, with room on the right for the group's own actions. */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(title, style = LocalType.current.heading)
                if (note != null) BasicText(note, style = LocalType.current.body.copy(fontSize = LocalType.current.label.fontSize))
            }
            actions()
        }
        content()
    }
}

/** A label, optional explanation, and the control that sets it, on one line. */
@Composable
fun SettingRow(label: String, description: String? = null, control: @Composable RowScope.() -> Unit) {
    val type = LocalType.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(label, style = type.bodyStrong)
            if (!description.isNullOrBlank()) BasicText(description, style = type.body.copy(fontSize = type.label.fontSize))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = control)
    }
}

@Composable
fun ToggleRow(label: String, setting: ImGuiState<Boolean>, description: String? = null, onChange: (Boolean) -> Unit = {}) {
    SettingRow(label, description) {
        Toggle(setting.value) {
            setting.value = it
            onChange(it)
        }
    }
}

@Composable
fun Toggle(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val hover = rememberHover()
    val knob by animateFloatAsState(if (checked) 1f else 0f, tween(140))
    val track = when {
        !enabled -> Palette.raised
        checked -> Palette.amber
        hover.hovered -> Palette.lineStrong
        else -> Palette.line
    }
    Box(
        Modifier
            .size(36.dp, 20.dp)
            .clip(CircleShape)
            .background(animatedColor(track))
            .then(if (enabled) Modifier.press(hover) { onChange(!checked) } else Modifier)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = (16 * knob).dp)
                .size(14.dp)
                .clip(CircleShape)
                .background(if (checked) Palette.onAmber else Palette.muted),
        )
    }
}

/** A pill that switches something on or off - for a cluster of related flags that would be a wall of rows. */
@Composable
fun ToggleChip(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val hover = rememberHover()
    Row(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(animatedColor(if (checked) Palette.amber.copy(alpha = 0.14f) else if (hover.hovered) Palette.hover else Palette.raised))
            .border(1.dp, if (checked) Palette.amber.copy(alpha = 0.5f) else Palette.line, RoundedCornerShape(15.dp))
            .press(hover) { onChange(!checked) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (checked) GlyphIcon(Glyph.Check, Palette.amber, 10.dp)
        BasicText(label, style = LocalType.current.label.copy(color = if (checked) Palette.text else Palette.muted))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipGroup(settings: List<Pair<String, ImGuiState<Boolean>>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        settings.forEach { (label, setting) -> ToggleChip(label, setting.value) { setting.value = it } }
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = Palette.muted) {
    BasicText(text, style = LocalType.current.body.copy(color = color, fontSize = LocalType.current.label.fontSize), modifier = modifier)
}

/** A label and its value, the value in the data face so numbers and paths line up. */
@Composable
fun Readout(rows: List<Pair<String, String>>) {
    val type = LocalType.current
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        rows.forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicText(label, style = type.label.copy(color = Palette.muted), modifier = Modifier.width(150.dp))
                BasicText(value, style = type.data.copy(color = Palette.text), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val type = LocalType.current
    Column(
        modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(title, style = type.heading)
        BasicText(body, style = type.body.copy(textAlign = TextAlign.Center), modifier = Modifier.width(420.dp))
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            action()
        }
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.line))
}
