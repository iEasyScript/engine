package com.projectx.ui.compose.components

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.Palette

/** A column: fixed [width] when given, otherwise a [weight] share of what is left. [mono] sets numbers in the data face. */
class TableColumn(val title: String, val weight: Float = 1f, val width: Dp? = null, val mono: Boolean = false)

/**
 * The cells of one row, in column order. Plain text covers almost every cell; [cell] takes anything else - a
 * button, a swatch - in the next column's slot.
 */
class TableRowScope internal constructor(private val columns: List<TableColumn>, private val row: RowScope) {
    private var index = 0

    private fun next(): Pair<TableColumn, Modifier> {
        val column = columns[index++.coerceAtMost(columns.lastIndex)]
        val modifier = column.width?.let { Modifier.width(it) } ?: with(row) { Modifier.weight(column.weight) }
        return column to modifier
    }

    @Composable
    fun text(value: String, color: Color? = null) {
        val (column, modifier) = next()
        val type = LocalType.current
        val style: TextStyle = if (column.mono) type.data else type.label
        BasicText(
            value,
            style = style.copy(color = color ?: if (column.mono) Palette.muted else Palette.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.padding(end = 10.dp),
        )
    }

    @Composable
    fun cell(content: @Composable RowScope.() -> Unit) {
        val (_, modifier) = next()
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

/**
 * Rows under a header. [fill] lets the table take the height it is given and scroll inside it, which is what a long
 * live list wants; otherwise every row is laid out and the screen around it scrolls.
 */
@Composable
fun <R> DataTable(
    columns: List<TableColumn>,
    rows: List<R>,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    emptyText: String = "Nothing to show.",
    key: ((R) -> Any)? = null,
    rowContent: @Composable TableRowScope.(R) -> Unit,
) {
    val type = LocalType.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Palette.line, RoundedCornerShape(10.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().background(Palette.raised).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val scope = TableRowScope(columns, this)
            columns.forEach { scope.text(it.title.uppercase(), Palette.faint) }
        }
        if (rows.isEmpty()) {
            BasicText(emptyText, style = type.body, modifier = Modifier.padding(14.dp))
            return@Column
        }
        if (fill) {
            val state = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rows, key = key?.let { k -> { _, r -> k(r) } }) { index, row -> TableRow(columns, index, row, rowContent) }
                }
                CompositionLocalProvider(LocalScrollbarStyle provides overlayScrollbarStyle()) {
                    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp))
                }
            }
        } else {
            rows.forEachIndexed { index, row -> TableRow(columns, index, row, rowContent) }
        }
    }
}

@Composable
private fun <R> TableRow(columns: List<TableColumn>, index: Int, row: R, rowContent: @Composable TableRowScope.(R) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .background(if (index % 2 == 1) Color.White.copy(alpha = 0.018f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TableRowScope(columns, this).rowContent(row)
    }
}
