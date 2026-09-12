package com.projectx.quest.overlay

import com.projectx.game.math.Vector2f
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

fun pulseAlpha(baseColor: Int, periodMs: Long = 900L, minAlpha: Int = 96, maxAlpha: Int = 255): Int {
    val t = (System.currentTimeMillis() % periodMs).toDouble() / periodMs.toDouble()
    val phase = sin(t * Math.PI * 2.0) * 0.5 + 0.5
    val a = (minAlpha + (maxAlpha - minAlpha) * phase).toInt().coerceIn(0, 255)
    return (baseColor and 0x00FFFFFF) or (a shl 24)
}

fun BackgroundDrawListScope.arrow(
    from: Vector2f,
    to: Vector2f,
    color: Int,
    thickness: Float = 3f,
    headSize: Float = 16f,
) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < 1e-3f) return
    val nx = dx / len
    val ny = dy / len
    val shaftEnd = Vector2f(to.x - nx * headSize * 0.6f, to.y - ny * headSize * 0.6f)
    line(from, shaftEnd, color, thickness)
    val px = -ny
    val py = nx
    val baseX = to.x - nx * headSize
    val baseY = to.y - ny * headSize
    val left = Vector2f(baseX + px * headSize * 0.5f, baseY + py * headSize * 0.5f)
    val right = Vector2f(baseX - px * headSize * 0.5f, baseY - py * headSize * 0.5f)
    convexPolyFilled(floatArrayOf(to.x, to.y, left.x, left.y, right.x, right.y), color)
}

fun BackgroundDrawListScope.offscreenBearingArrow(
    center: Vector2f,
    screenW: Float,
    screenH: Float,
    bearingRad: Float,
    color: Int,
    margin: Float = 60f,
    arrowLen: Float = 32f,
) {
    // Screen Y grows downward, so flip the Y component of the world bearing.
    val sx = cos(bearingRad)
    val sy = -sin(bearingRad)
    val minX = margin
    val maxX = screenW - margin
    val minY = margin
    val maxY = screenH - margin
    val ts = mutableListOf<Float>()
    if (sx > 1e-3f) ts += (maxX - center.x) / sx
    if (sx < -1e-3f) ts += (minX - center.x) / sx
    if (sy > 1e-3f) ts += (maxY - center.y) / sy
    if (sy < -1e-3f) ts += (minY - center.y) / sy
    val t = ts.filter { it > 0f }.minOrNull() ?: return
    val edgeX = (center.x + sx * t).coerceIn(minX, maxX)
    val edgeY = (center.y + sy * t).coerceIn(minY, maxY)
    val tip = Vector2f(edgeX, edgeY)
    val tailX = edgeX - sx * arrowLen
    val tailY = edgeY - sy * arrowLen
    arrow(Vector2f(tailX, tailY), tip, color, thickness = 4f, headSize = 18f)
}

fun bearingRad(fromX: Int, fromY: Int, toX: Int, toY: Int): Float =
    atan2((toY - fromY).toDouble(), (toX - fromX).toDouble()).toFloat()

fun stripHtml(s: String): String =
    s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</li>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<li>", RegexOption.IGNORE_CASE), "- ")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
