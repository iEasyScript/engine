package com.projectx.ui.compose.hud

import com.projectx.ui.backend.native.ImGuiTexture
import java.lang.foreign.MemorySegment

/** One primitive drawn onto the world or into a window, in screen or card coordinates, colour in ImGui's ABGR. */
sealed class Shape {
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: Int, val thickness: Float) : Shape()
    data class Rect(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: Int, val rounding: Float, val thickness: Float, val filled: Boolean) : Shape()
    data class Circle(val x: Float, val y: Float, val radius: Float, val color: Int, val thickness: Float, val filled: Boolean) : Shape()
    data class Text(val x: Float, val y: Float, val color: Int, val text: String) : Shape()
    data class Image(val texture: ImGuiTexture, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val tint: Int) : Shape()
    data class Poly(val points: List<Float>, val color: Int, val thickness: Float, val filled: Boolean, val closed: Boolean) : Shape()
}

/**
 * Stands in for an ImGui draw list so existing drawing code can be replayed into Compose.
 *
 * Every draw-list call in the engine goes through NativeBridge; handed [MARKER]
 * instead of a real draw list, those calls land here as [Shape]s rather than reaching ImGui. That keeps world
 * overlays and scripts that draw straight into a window working untouched, whatever route they draw through.
 */
object DrawRecorder {
    val MARKER: MemorySegment = MemorySegment.ofAddress(0x5043_4F4D_5031L)

    private val sink = ThreadLocal<MutableList<Shape>?>()

    fun isMarker(drawList: MemorySegment): Boolean = drawList.address() == MARKER.address()

    fun record(block: (MemorySegment) -> Unit): List<Shape> {
        val shapes = mutableListOf<Shape>()
        val previous = sink.get()
        sink.set(shapes)
        try {
            block(MARKER)
        } catch (t: Throwable) {
            println("[Overlay] drawing failed: ${t::class.simpleName}: ${t.message}")
        } finally {
            sink.set(previous)
        }
        return shapes
    }

    fun add(shape: Shape) {
        sink.get()?.add(shape)
    }
}
