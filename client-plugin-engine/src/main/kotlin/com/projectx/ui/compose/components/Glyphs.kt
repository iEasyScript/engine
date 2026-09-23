package com.projectx.ui.compose.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import kotlin.math.cos
import kotlin.math.sin

/** The handful of icons the overlay needs, drawn as geometry on a unit square so they stay sharp at any size. */
enum class Glyph { Play, Stop, Star, StarFilled, Gear, Search, Close, Refresh, ChevronDown, ChevronRight, ChevronLeft, ChevronUp, Plus, Minus, Trash, Check, Copy, Crosshair, Resize }

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { draw(glyph, tint) }
}

private fun DrawScope.draw(glyph: Glyph, tint: Color) {
    val s = size.minDimension
    val stroke = Stroke(width = s * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (glyph) {
        Glyph.Play -> drawPath(
            Path().apply { moveTo(s * 0.18f, s * 0.08f); lineTo(s * 0.92f, s * 0.5f); lineTo(s * 0.18f, s * 0.92f); close() },
            tint,
        )
        Glyph.Stop -> drawRoundRect(tint, Offset(s * 0.1f, s * 0.1f), Size(s * 0.8f, s * 0.8f), CornerRadius(s * 0.14f))
        Glyph.Star -> drawPath(star(s), tint, style = Stroke(width = s * 0.09f, join = StrokeJoin.Round))
        Glyph.StarFilled -> drawPath(star(s), tint)
        Glyph.Gear -> {
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val from = Offset(s / 2 + cos(a).toFloat() * s * 0.3f, s / 2 + sin(a).toFloat() * s * 0.3f)
                val to = Offset(s / 2 + cos(a).toFloat() * s * 0.46f, s / 2 + sin(a).toFloat() * s * 0.46f)
                drawLine(tint, from, to, s * 0.14f, StrokeCap.Round)
            }
            drawCircle(tint, s * 0.3f, style = stroke)
            drawCircle(tint, s * 0.1f)
        }
        Glyph.Search -> {
            drawCircle(tint, s * 0.3f, Offset(s * 0.42f, s * 0.42f), style = stroke)
            drawLine(tint, Offset(s * 0.65f, s * 0.65f), Offset(s * 0.9f, s * 0.9f), s * 0.12f, StrokeCap.Round)
        }
        Glyph.Close -> {
            drawLine(tint, Offset(s * 0.2f, s * 0.2f), Offset(s * 0.8f, s * 0.8f), s * 0.12f, StrokeCap.Round)
            drawLine(tint, Offset(s * 0.8f, s * 0.2f), Offset(s * 0.2f, s * 0.8f), s * 0.12f, StrokeCap.Round)
        }
        Glyph.ChevronDown -> chevron(tint, s, Offset(0.2f, 0.36f), Offset(0.5f, 0.66f), Offset(0.8f, 0.36f), stroke)
        Glyph.ChevronUp -> chevron(tint, s, Offset(0.2f, 0.64f), Offset(0.5f, 0.34f), Offset(0.8f, 0.64f), stroke)
        Glyph.ChevronRight -> chevron(tint, s, Offset(0.36f, 0.2f), Offset(0.66f, 0.5f), Offset(0.36f, 0.8f), stroke)
        Glyph.ChevronLeft -> chevron(tint, s, Offset(0.64f, 0.2f), Offset(0.34f, 0.5f), Offset(0.64f, 0.8f), stroke)
        Glyph.Plus -> {
            drawLine(tint, Offset(s * 0.5f, s * 0.14f), Offset(s * 0.5f, s * 0.86f), s * 0.13f, StrokeCap.Round)
            drawLine(tint, Offset(s * 0.14f, s * 0.5f), Offset(s * 0.86f, s * 0.5f), s * 0.13f, StrokeCap.Round)
        }
        Glyph.Minus -> drawLine(tint, Offset(s * 0.14f, s * 0.5f), Offset(s * 0.86f, s * 0.5f), s * 0.13f, StrokeCap.Round)
        Glyph.Trash -> {
            drawLine(tint, Offset(s * 0.12f, s * 0.24f), Offset(s * 0.88f, s * 0.24f), s * 0.11f, StrokeCap.Round)
            drawLine(tint, Offset(s * 0.38f, s * 0.1f), Offset(s * 0.62f, s * 0.1f), s * 0.11f, StrokeCap.Round)
            drawRoundRect(tint, Offset(s * 0.22f, s * 0.34f), Size(s * 0.56f, s * 0.58f), CornerRadius(s * 0.08f), style = stroke)
        }
        Glyph.Check -> drawPath(
            Path().apply { moveTo(s * 0.14f, s * 0.52f); lineTo(s * 0.4f, s * 0.78f); lineTo(s * 0.88f, s * 0.24f) },
            tint, style = Stroke(width = s * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        Glyph.Copy -> {
            drawRoundRect(tint, Offset(s * 0.32f, s * 0.32f), Size(s * 0.58f, s * 0.58f), CornerRadius(s * 0.1f), style = stroke)
            drawPath(
                Path().apply { moveTo(s * 0.18f, s * 0.68f); lineTo(s * 0.1f, s * 0.68f); lineTo(s * 0.1f, s * 0.1f); lineTo(s * 0.68f, s * 0.1f); lineTo(s * 0.68f, s * 0.18f) },
                tint, style = stroke,
            )
        }
        Glyph.Crosshair -> {
            drawCircle(tint, s * 0.3f, style = stroke)
            for ((from, to) in listOf(0.02f to 0.22f, 0.78f to 0.98f)) {
                drawLine(tint, Offset(s * 0.5f, s * from), Offset(s * 0.5f, s * to), s * 0.11f, StrokeCap.Round)
                drawLine(tint, Offset(s * from, s * 0.5f), Offset(s * to, s * 0.5f), s * 0.11f, StrokeCap.Round)
            }
        }
        Glyph.Resize -> for (i in 1..3) {
            val d = s * (0.3f * i)
            drawLine(tint, Offset(s - d, s * 0.96f), Offset(s * 0.96f, s - d), s * 0.09f, StrokeCap.Round)
        }
        Glyph.Refresh -> {
            drawArc(tint, -60f, 290f, false, Offset(s * 0.14f, s * 0.14f), Size(s * 0.72f, s * 0.72f), style = stroke)
            drawPath(
                Path().apply { moveTo(s * 0.62f, s * 0.02f); lineTo(s * 0.9f, s * 0.2f); lineTo(s * 0.6f, s * 0.36f); close() },
                tint,
            )
        }
    }
}

private fun DrawScope.chevron(tint: Color, s: Float, a: Offset, b: Offset, c: Offset, stroke: Stroke) = drawPath(
    Path().apply { moveTo(a.x * s, a.y * s); lineTo(b.x * s, b.y * s); lineTo(c.x * s, c.y * s) },
    tint,
    style = stroke,
)

private fun star(s: Float) = Path().apply {
    for (i in 0 until 10) {
        val radius = if (i % 2 == 0) s * 0.48f else s * 0.2f
        val a = Math.toRadians(-90.0 + i * 36.0)
        val x = s / 2 + cos(a).toFloat() * radius
        val y = s * 0.54f + sin(a).toFloat() * radius
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
