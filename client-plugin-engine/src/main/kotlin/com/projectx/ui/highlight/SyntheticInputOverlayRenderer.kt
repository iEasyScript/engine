package com.projectx.ui.highlight

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Priority
import com.projectx.game.input.SyntheticInputShadow
import com.projectx.game.input.SyntheticInputShadow.TrailKind
import com.projectx.game.math.Vector2f
import com.projectx.game.nxt.MainState
import com.projectx.profiling.PlayerProfiles
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.rendering.ImGUIRender
import kotlin.math.max
import kotlin.math.min

object SyntheticInputOverlayRenderer {
    private const val MOVE_FADE_NANOS = 2_000_000_000L
    private const val CLICK_FADE_NANOS = 3_500_000_000L
    private const val CLICK_PULSE_NANOS = 350_000_000L
    private const val CLICK_PULSE_MAX_RADIUS = 26f
    private const val CLICK_RING_RADIUS = 10f
    private const val MOVE_DOT_RADIUS = 2.8f
    private const val CURSOR_DOT_RADIUS = 4.5f
    private const val TRAIL_BASE_THICKNESS = 1.6f

    @JvmStatic
    @ImGUIRender(priority = Priority.LOW)
    fun render() {
        val profile = try { PlayerProfiles.get() } catch (_: Throwable) { return }
        if (!profile.synthInputEnabled || !profile.synthVisualizerEnabled) return
        try {
            if (Bootstrap.client.mainState != MainState.LOGGED_IN) return
        } catch (_: Throwable) { return }

        val trail = SyntheticInputShadow.trailSnapshot()
        if (trail.isEmpty()) return
        val now = System.nanoTime()

        backgroundDrawList {
            drawTrail(trail, now)
            drawClicksAndCursor(trail, now)
            text(Vector2f(8f, 8f), imColor(0x33, 0xCC, 0xFF, 220), "PACKET DATA (server-bound, not real cursor)")
        }
    }

    private fun BackgroundDrawListScope.drawTrail(trail: List<SyntheticInputShadow.TrailPoint>, now: Long) {
        var prev: SyntheticInputShadow.TrailPoint? = null
        for (point in trail) {
            if (point.kind == TrailKind.MOVE) {
                val alpha = fade(now - point.timestampNanos, MOVE_FADE_NANOS)
                if (alpha > 0f) {
                    val color = imColor(0x33, 0xCC, 0xFF, (alpha * 220f).toInt())
                    val p = Vector2f(point.x, point.y)
                    val prevPoint = prev
                    if (prevPoint != null && prevPoint.kind == TrailKind.MOVE) {
                        val prevAlpha = fade(now - prevPoint.timestampNanos, MOVE_FADE_NANOS)
                        val blended = min(alpha, prevAlpha)
                        val lineColor = imColor(0x33, 0xCC, 0xFF, (blended * 200f).toInt())
                        line(
                            Vector2f(prevPoint.x, prevPoint.y),
                            p,
                            lineColor,
                            TRAIL_BASE_THICKNESS + blended * 0.8f
                        )
                    }
                    circleFilled(p, MOVE_DOT_RADIUS, color)
                }
            }
            prev = point
        }
    }

    private fun BackgroundDrawListScope.drawClicksAndCursor(trail: List<SyntheticInputShadow.TrailPoint>, now: Long) {
        for (point in trail) {
            if (point.kind != TrailKind.CLICK) continue
            val age = now - point.timestampNanos
            val alpha = fade(age, CLICK_FADE_NANOS)
            if (alpha <= 0f) continue
            val center = Vector2f(point.x, point.y)

            if (age < CLICK_PULSE_NANOS) {
                val pulseProgress = age.toFloat() / CLICK_PULSE_NANOS
                val pulseRadius = CLICK_RING_RADIUS + pulseProgress * CLICK_PULSE_MAX_RADIUS
                val pulseAlpha = ((1f - pulseProgress) * 255f).toInt().coerceIn(0, 255)
                circle(center, pulseRadius, imColor(0xFF, 0x55, 0xAA, pulseAlpha), thickness = 2f)
            }

            val ringColor = imColor(0xFF, 0x55, 0xAA, (alpha * 230f).toInt())
            val coreColor = imColor(0xFF, 0xCC, 0xDD, (alpha * 255f).toInt())
            circle(center, CLICK_RING_RADIUS, ringColor, thickness = 2.4f)
            circleFilled(center, 3.2f, coreColor)
        }

        val latest = trail.lastOrNull() ?: return
        val ageLatest = now - latest.timestampNanos
        if (ageLatest > MOVE_FADE_NANOS) return
        val cursor = Vector2f(latest.x, latest.y)
        val cursorAlpha = max(0.35f, 1f - ageLatest.toFloat() / MOVE_FADE_NANOS)
        circleFilled(cursor, CURSOR_DOT_RADIUS, imColor(0xFF, 0xFF, 0xFF, (cursorAlpha * 255f).toInt()))
        circle(cursor, CURSOR_DOT_RADIUS + 2.5f, imColor(0x33, 0xCC, 0xFF, (cursorAlpha * 200f).toInt()), thickness = 1.4f)
    }

    private fun fade(ageNanos: Long, lifetimeNanos: Long): Float {
        if (ageNanos <= 0L) return 1f
        if (ageNanos >= lifetimeNanos) return 0f
        return 1f - ageNanos.toFloat() / lifetimeNanos
    }

    private fun imColor(r: Int, g: Int, b: Int, a: Int): Int {
        val ac = a.coerceIn(0, 255)
        val rc = r.coerceIn(0, 255)
        val gc = g.coerceIn(0, 255)
        val bc = b.coerceIn(0, 255)
        return (ac shl 24) or (bc shl 16) or (gc shl 8) or rc
    }
}
