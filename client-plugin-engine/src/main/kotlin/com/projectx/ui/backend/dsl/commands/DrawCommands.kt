package com.projectx.ui.backend.dsl.commands

import com.projectx.ui.backend.dsl.utils.Corner
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.native.ImGuiTexture
import com.projectx.ui.backend.native.NativeBridge
import java.lang.foreign.MemorySegment

data class BackgroundDrawListScopeCommand(
    val drawCommands: List<ImGuiDrawCommand>
) : ImGuiCommand() {
    override fun execute() {
        val backgroundDrawList = NativeBridge.getBackgroundDrawList() ?: return
        drawCommands.forEach { command ->
            try {
                command.execute(backgroundDrawList)
            } catch (e: Throwable) {
                System.err.println("Background draw command failed: ${command.javaClass.simpleName} - ${e.message}")
            }
        }
    }
}

sealed class ImGuiDrawCommand {
    abstract fun execute(drawList: MemorySegment)
}

data class DrawLineCommand(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val color: Int,
    val thickness: Float = 1f
) : ImGuiDrawCommand() {
    override fun execute(drawList: MemorySegment) {
        NativeBridge.drawListAddLine(drawList, x1, y1, x2, y2, color, thickness)
    }
}

data class DrawRectCommand(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val color: Int,
    val rounding: Float = 0f,
    val flags: Int = 0,
    val thickness: Float = 1f
) : ImGuiDrawCommand() {
    override fun execute(drawList: MemorySegment) {
        NativeBridge.drawListAddRect(drawList, x1, y1, x2, y2, color, rounding, flags, thickness)
    }
}

data class DrawRectFilledCommand(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val color: Int,
    val rounding: Float = 0f,
    val flags: Int = 0
) : ImGuiDrawCommand() {
    override fun execute(drawList: MemorySegment) {
        NativeBridge.drawListAddRectFilled(drawList, x1, y1, x2, y2, color, rounding, flags)
    }
}

data class DrawCircleCommand(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val color: Int,
    val numSegments: Int = 0,
    val thickness: Float = 1f
) : ImGuiDrawCommand() {
    override fun execute(drawList: MemorySegment) {
        NativeBridge.drawListAddCircle(drawList, centerX, centerY, radius, color, numSegments, thickness)
    }
}

data class DrawCircleFilledCommand(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val color: Int,
    val numSegments: Int = 0
) : ImGuiDrawCommand() {
    override fun execute(drawList: MemorySegment) {
        NativeBridge.drawListAddCircleFilled(drawList, centerX, centerY, radius, color, numSegments)
    }
}

data class DrawTextCommand(
    val x: Float,
    val y: Float,
    val color: Int,
    val text: String
) : ImGuiDrawCommand() {
    override fun execute(drawList: MemorySegment) {
        val safeText = text.take(1024).filter { it.code <= 0x10FFFF }
        NativeBridge.drawListAddText(drawList, x, y, color, safeText)
    }
}

data class DrawImageCommand(
    val texturePath: String,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val color: Int = -1
) : ImGuiDrawCommand() {
    override fun execute(drawList: MemorySegment) {
        val texture = NativeBridge.loadTexture(texturePath) ?: return
        NativeBridge.drawListAddImage(drawList, texture, x1, y1, x2, y2, color)
    }
}

data class DrawTextureCommand(
    val texture: ImGuiTexture,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val color: Int = -1
) : ImGuiDrawCommand() {
    override fun execute(drawList: MemorySegment) {
        NativeBridge.drawListAddImage(drawList, texture, x1, y1, x2, y2, color)
    }
}

data class DrawWatermarkCommand(
    val texture: ImGuiTexture,
    val corner: Corner = Corner.BottomRight,
    val paddingX: Float = 8f,
    val paddingY: Float = 8f,
    val alpha: Float = 1f,
    val scale: Float = 1f
) : ImGuiDrawCommand() {
    override fun execute(drawList: MemorySegment) {
        val (dw, dh) = try { NativeBridge.getDisplaySize() } catch (_: Throwable) { 0f to 0f }
        if (dw <= 0f || dh <= 0f) return

        val w = (texture.width.toFloat() * scale.coerceAtLeast(0f)).coerceAtLeast(0.5f)
        val h = (texture.height.toFloat() * scale.coerceAtLeast(0f)).coerceAtLeast(0.5f)

        val x = when (corner) {
            Corner.TopLeft -> paddingX
            Corner.TopRight -> dw - w - paddingX
            Corner.BottomLeft -> paddingX
            Corner.BottomRight -> dw - w - paddingX
        }
        val y = when (corner) {
            Corner.TopLeft -> paddingY
            Corner.TopRight -> paddingY
            Corner.BottomLeft -> dh - h - paddingY
            Corner.BottomRight -> dh - h - paddingY
        }

        val tint = ImGuiColors.withAlpha(ImGuiColors.WHITE, alpha)
        NativeBridge.drawListAddImage(drawList, texture, x, y, x + w, y + h, tint)
    }
}

data class DrawConvexPolyFilledCommand(
    val points: FloatArray,
    val color: Int
) : ImGuiDrawCommand() {
    override fun execute(drawList: MemorySegment) {
        NativeBridge.drawListAddConvexPolyFilled(drawList, points, color)
    }
}

data class DrawPolylineCommand(
    val points: FloatArray,
    val color: Int,
    val flags: Int = 0,
    val thickness: Float = 1f
) : ImGuiDrawCommand() {
    override fun execute(drawList: MemorySegment) {
        NativeBridge.drawListAddPolyline(drawList, points, color, flags, thickness)
    }
}
