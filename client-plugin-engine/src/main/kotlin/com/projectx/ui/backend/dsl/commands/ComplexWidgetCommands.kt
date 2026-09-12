package com.projectx.ui.backend.dsl.commands

import com.projectx.ui.backend.dsl.ImGuiState
import com.projectx.ui.backend.dsl.utils.Corner
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.dsl.utils.GraphicNineSlice
import com.projectx.ui.backend.native.ImGuiTexture
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.ui.backend.native.getTexture
import world.gregs.voidps.cache.Cache
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

data class ColorEdit4Command(
    val label: String,
    val r: ImGuiState<Float>,
    val g: ImGuiState<Float>,
    val b: ImGuiState<Float>,
    val a: ImGuiState<Float>,
    val flags: Int = 0
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        Arena.ofConfined().use { arena ->
            val buf = arena.allocate(ValueLayout.JAVA_FLOAT, 4L)
            val stride = ValueLayout.JAVA_FLOAT.byteSize()
            buf.set(ValueLayout.JAVA_FLOAT, 0 * stride, r.value)
            buf.set(ValueLayout.JAVA_FLOAT, 1 * stride, g.value)
            buf.set(ValueLayout.JAVA_FLOAT, 2 * stride, b.value)
            buf.set(ValueLayout.JAVA_FLOAT, 3 * stride, a.value)
            if (NativeBridge.colorEdit4(safeLabel, buf, flags)) {
                r.value = buf.get(ValueLayout.JAVA_FLOAT, 0 * stride)
                g.value = buf.get(ValueLayout.JAVA_FLOAT, 1 * stride)
                b.value = buf.get(ValueLayout.JAVA_FLOAT, 2 * stride)
                a.value = buf.get(ValueLayout.JAVA_FLOAT, 3 * stride)
            }
        }
    }
}

data class ColorPicker4Command(
    val label: String,
    val r: ImGuiState<Float>,
    val g: ImGuiState<Float>,
    val b: ImGuiState<Float>,
    val a: ImGuiState<Float>,
    val flags: Int = 0
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        Arena.ofConfined().use { arena ->
            val buf = arena.allocate(ValueLayout.JAVA_FLOAT, 4L)
            val stride = ValueLayout.JAVA_FLOAT.byteSize()
            buf.set(ValueLayout.JAVA_FLOAT, 0 * stride, r.value)
            buf.set(ValueLayout.JAVA_FLOAT, 1 * stride, g.value)
            buf.set(ValueLayout.JAVA_FLOAT, 2 * stride, b.value)
            buf.set(ValueLayout.JAVA_FLOAT, 3 * stride, a.value)
            if (NativeBridge.colorPicker4(safeLabel, buf, flags)) {
                r.value = buf.get(ValueLayout.JAVA_FLOAT, 0 * stride)
                g.value = buf.get(ValueLayout.JAVA_FLOAT, 1 * stride)
                b.value = buf.get(ValueLayout.JAVA_FLOAT, 2 * stride)
                a.value = buf.get(ValueLayout.JAVA_FLOAT, 3 * stride)
            }
        }
    }
}

data class InputFloat2Command(
    val label: String,
    val x: ImGuiState<Float>,
    val y: ImGuiState<Float>,
    val format: String = "%.3f",
    val flags: Int = 0
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(ValueLayout.JAVA_FLOAT, 2L)
            val stride = ValueLayout.JAVA_FLOAT.byteSize()
            buffer.set(ValueLayout.JAVA_FLOAT, 0 * stride, x.value)
            buffer.set(ValueLayout.JAVA_FLOAT, 1 * stride, y.value)
            if (NativeBridge.inputFloat2(safeLabel, buffer, format, flags)) {
                x.value = buffer.get(ValueLayout.JAVA_FLOAT, 0 * stride)
                y.value = buffer.get(ValueLayout.JAVA_FLOAT, 1 * stride)
            }
        }
    }
}

data class InputFloat3Command(
    val label: String,
    val x: ImGuiState<Float>,
    val y: ImGuiState<Float>,
    val z: ImGuiState<Float>,
    val format: String = "%.3f",
    val flags: Int = 0
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(ValueLayout.JAVA_FLOAT, 3L)
            val stride = ValueLayout.JAVA_FLOAT.byteSize()
            buffer.set(ValueLayout.JAVA_FLOAT, 0 * stride, x.value)
            buffer.set(ValueLayout.JAVA_FLOAT, 1 * stride, y.value)
            buffer.set(ValueLayout.JAVA_FLOAT, 2 * stride, z.value)
            if (NativeBridge.inputFloat3(safeLabel, buffer, format, flags)) {
                x.value = buffer.get(ValueLayout.JAVA_FLOAT, 0 * stride)
                y.value = buffer.get(ValueLayout.JAVA_FLOAT, 1 * stride)
                z.value = buffer.get(ValueLayout.JAVA_FLOAT, 2 * stride)
            }
        }
    }
}

data class InputFloat4Command(
    val label: String,
    val x: ImGuiState<Float>,
    val y: ImGuiState<Float>,
    val z: ImGuiState<Float>,
    val w: ImGuiState<Float>,
    val format: String = "%.3f",
    val flags: Int = 0
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(ValueLayout.JAVA_FLOAT, 4L)
            val stride = ValueLayout.JAVA_FLOAT.byteSize()
            buffer.set(ValueLayout.JAVA_FLOAT, 0 * stride, x.value)
            buffer.set(ValueLayout.JAVA_FLOAT, 1 * stride, y.value)
            buffer.set(ValueLayout.JAVA_FLOAT, 2 * stride, z.value)
            buffer.set(ValueLayout.JAVA_FLOAT, 3 * stride, w.value)
            if (NativeBridge.inputFloat4(safeLabel, buffer, format, flags)) {
                x.value = buffer.get(ValueLayout.JAVA_FLOAT, 0 * stride)
                y.value = buffer.get(ValueLayout.JAVA_FLOAT, 1 * stride)
                z.value = buffer.get(ValueLayout.JAVA_FLOAT, 2 * stride)
                w.value = buffer.get(ValueLayout.JAVA_FLOAT, 3 * stride)
            }
        }
    }
}

/**
 * Draw a full-window background overlay using a provided texture tinted by alpha.
 * Renders on the current window's draw list so it is clipped to the window bounds.
 */
data class WindowBackgroundOverlayCommand(
    val texture: ImGuiTexture,
    val alpha: Float,
    val tiled: Boolean
) : ImGuiCommand() {
    override fun execute() {
        val drawList = NativeBridge.getWindowDrawList() ?: return
        val (wx, wy) = NativeBridge.getWindowPos()
        val (ww, wh) = NativeBridge.getWindowSize()
        if (ww <= 0f || wh <= 0f) return

        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        val tint = ImGuiColors.withAlpha(ImGuiColors.WHITE, a)
        if (!tiled || texture.width <= 0 || texture.height <= 0) {
            NativeBridge.drawListAddImage(drawList, texture, wx, wy, wx + ww, wy + wh, tint)
            return
        }

        val tw = texture.width.toFloat().coerceAtLeast(1f)
        val th = texture.height.toFloat().coerceAtLeast(1f)
        var y = wy
        val yEnd = wy + wh
        while (y < yEnd - 0.5f) {
            var x = wx
            val xEnd = wx + ww
            while (x < xEnd - 0.5f) {
                NativeBridge.drawListAddImage(drawList, texture, x, y, x + tw, y + th, tint)
                x += tw
            }
            y += th
        }
    }
}

/**
 * Place a texture as a watermark at one of the current window's corners.
 * Draws via the window draw list so it clips to window bounds and doesn't affect layout.
 */
data class WindowWatermarkCommand(
    val texture: ImGuiTexture,
    val corner: Corner = Corner.BottomRight,
    val paddingX: Float = 0f,
    val paddingY: Float = 0f,
    val alpha: Float = 1f,
    val scale: Float = 1f
) : ImGuiCommand() {
    override fun execute() {
        val drawList = NativeBridge.getWindowDrawList() ?: return
        val (wx, wy) = NativeBridge.getWindowPos()
        val (ww, wh) = NativeBridge.getWindowSize()
        if (ww <= 0f || wh <= 0f) return

        val w = (texture.width.toFloat() * scale.coerceAtLeast(0f)).coerceAtLeast(0.5f)
        val h = (texture.height.toFloat() * scale.coerceAtLeast(0f)).coerceAtLeast(0.5f)

        val x = when (corner) {
            Corner.TopLeft -> wx + paddingX
            Corner.TopRight -> wx + ww - w - paddingX
            Corner.BottomLeft -> wx + paddingX
            Corner.BottomRight -> wx + ww - w - paddingX
        }
        val y = when (corner) {
            Corner.TopLeft -> wy + paddingY
            Corner.TopRight -> wy + paddingY
            Corner.BottomLeft -> wy + wh - h - paddingY
            Corner.BottomRight -> wy + wh - h - paddingY
        }

        val tint = ImGuiColors.withAlpha(ImGuiColors.WHITE, alpha)
        NativeBridge.drawListAddImage(drawList, texture, x, y, x + w, y + h, tint)
    }
}

/**
 * Image button composed of 3 graphics: left cap, middle (tile/stretch), right cap.
 * Draws a custom background and uses a transparent ImGui button for interaction.
 */
data class TriSliceImageButtonCommand(
    val id: String,
    val left: ImGuiTexture,
    val middle: ImGuiTexture,
    val right: ImGuiTexture,
    val totalWidth: Float,
    val totalHeight: Float = 0f,
    val tileMiddle: Boolean = true,
    val label: String? = null,
    val labelColor: Int = ImGuiColors.WHITE,
    val onClick: () -> Unit
) : ImGuiCommand() {
    override fun execute() {
        val drawList = NativeBridge.getWindowDrawList() ?: return

        val lw = left.width.toFloat().coerceAtLeast(1f)
        val mw = middle.width.toFloat().coerceAtLeast(1f)
        val rw = right.width.toFloat().coerceAtLeast(1f)
        val naturalH = maxOf(left.height, middle.height, right.height).toFloat().coerceAtLeast(1f)
        val h = if (totalHeight > 0f) totalHeight else naturalH
        val w = totalWidth.coerceAtLeast(lw + rw + (if (tileMiddle) mw else 1f))

        // Make default button visuals invisible
        NativeBridge.pushStyleColor(ImGuiCol.Button, ImGuiColors.TRANSPARENT)
        NativeBridge.pushStyleColor(ImGuiCol.ButtonHovered, ImGuiColors.TRANSPARENT)
        NativeBridge.pushStyleColor(ImGuiCol.ButtonActive, ImGuiColors.TRANSPARENT)
        try {
            val clicked = NativeBridge.button("##$id", w, h)

            val (x0, y0) = NativeBridge.getItemRectMin()
            val (x1, y1) = NativeBridge.getItemRectMax()

            // Draw left cap
            val leftX0 = x0
            val leftX1 = (x0 + lw).coerceAtMost(x1)
            if (leftX1 > leftX0) {
                NativeBridge.drawListAddImage(drawList, left, leftX0, y0, leftX1, y1)
            }

            // Draw right cap
            val rightX1 = x1
            val rightX0 = (x1 - rw).coerceAtLeast(x0)
            if (rightX1 > rightX0) {
                NativeBridge.drawListAddImage(drawList, right, rightX0, y0, rightX1, y1)
            }

            // Draw middle segment
            val midX0 = (x0 + lw).coerceAtMost(x1)
            val midX1 = (x1 - rw).coerceAtLeast(x0)
            if (midX1 > midX0) {
                if (tileMiddle) {
                    var cx = midX0
                    while (cx < midX1 - 0.5f) {
                        val segW = min(mw, midX1 - cx)
                        NativeBridge.drawListAddImage(drawList, middle, cx, y0, cx + segW, y1)
                        cx += segW
                    }
                } else {
                    NativeBridge.drawListAddImage(drawList, middle, midX0, y0, midX1, y1)
                }
            }

            // Optional centered label over the composed image
            label?.let { text ->
                val (tw, th) = try { NativeBridge.calcTextSize(text) } catch (_: Throwable) { Pair(0f, 0f) }
                val tx = (x0 + x1 - tw) * 0.5f
                val ty = (y0 + y1 - th) * 0.5f
                NativeBridge.drawListAddText(drawList, tx, ty, labelColor, text)
            }

            if (clicked) {
                try { onClick() } catch (e: Throwable) {
                    System.err.println("onClick failed for tri-slice button '$id': ${e.message}")
                    e.printStackTrace()
                }
            }
        } finally {
            NativeBridge.popStyleColor(3)
        }
    }
}

/**
 * Square icon button carrying its own id. [ImageButtonCommand] cannot be used more than once per
 * frame: its native binding passes a constant empty `str_id`, so every instance hashes to the same
 * ImGui id and they collide. This composes an id-carrying button with a draw-list image instead.
 */
data class IconButtonCommand(
    val id: String,
    val texture: ImGuiTexture,
    val iconSize: Float,
    val buttonWidth: Float,
    val tintColor: Int = ImGuiColors.WHITE,
    val onClick: () -> Unit
) : ImGuiCommand() {
    override fun execute() {
        val drawList = NativeBridge.getWindowDrawList() ?: return
        // A zero height takes ImGui's standard frame height, so the tile matches the text and buttons
        // sharing its row instead of hugging the top. The icon is then centred in the rect that gives.
        val clicked = NativeBridge.button("##$id", buttonWidth, 0f)
        val (x0, y0) = NativeBridge.getItemRectMin()
        val (x1, y1) = NativeBridge.getItemRectMax()
        val half = iconSize * 0.5f
        val centerX = (x0 + x1) * 0.5f
        val centerY = (y0 + y1) * 0.5f
        NativeBridge.drawListAddImage(
            drawList,
            texture,
            centerX - half,
            centerY - half,
            centerX + half,
            centerY + half,
            tintColor,
        )
        if (clicked) {
            try {
                onClick()
            } catch (e: Throwable) {
                System.err.println("onClick failed for icon button '$id': ${e.message}")
                e.printStackTrace()
            }
        }
    }
}

/**
 * Convenience: tri-slice image button from graphic IDs.
 */
data class TriSliceImageButtonFromIdsCommand(
    val id: String,
    val leftGraphicId: Int,
    val middleGraphicId: Int,
    val rightGraphicId: Int,
    val totalWidth: Float,
    val totalHeight: Float = 0f,
    val tileMiddle: Boolean = true,
    val label: String? = null,
    val labelColor: Int = ImGuiColors.WHITE,
    val onClick: () -> Unit
) : ImGuiCommand() {
    override fun execute() {
        val leftGraphic = Cache.graphic(leftGraphicId) ?: return
        val midGraphic = Cache.graphic(middleGraphicId) ?: return
        val rightGraphic = Cache.graphic(rightGraphicId) ?: return
        val leftTex = leftGraphic.getTexture()
        val midTex = midGraphic.getTexture()
        val rightTex = rightGraphic.getTexture()

        TriSliceImageButtonCommand(
            id = id,
            left = leftTex,
            middle = midTex,
            right = rightTex,
            totalWidth = totalWidth,
            totalHeight = totalHeight,
            tileMiddle = tileMiddle,
            label = label,
            labelColor = labelColor,
            onClick = onClick
        ).execute()
    }
}

// Range sliders
data class RangeSliderFloatCommand(
    val label: String,
    val minState: ImGuiState<Float>,
    val maxState: ImGuiState<Float>,
    val rangeMin: Float,
    val rangeMax: Float,
    val format: String = "%.3f",
    val flags: Int = 0,
    val changed: AtomicReference<Boolean>
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        var hasChanged = false
        
        // Ensure min <= max
        val currentMin = minState.value.coerceIn(rangeMin, rangeMax)
        val currentMax = maxState.value.coerceIn(rangeMin, rangeMax)
        val adjustedMin = minOf(currentMin, currentMax)
        val adjustedMax = maxOf(currentMin, currentMax)
        
        // Draw label
        NativeBridge.text("$safeLabel: $adjustedMin - $adjustedMax")
        
        // Draw min slider
        val minLabel = "$safeLabel Min"
        Arena.ofConfined().use { arena ->
            val minBuffer = arena.allocate(ValueLayout.JAVA_FLOAT)
            minBuffer.set(ValueLayout.JAVA_FLOAT, 0, adjustedMin)
            val minChanged = NativeBridge.sliderFloat(minLabel, minBuffer, rangeMin, adjustedMax, format, flags)
            val newMin = minBuffer.get(ValueLayout.JAVA_FLOAT, 0)
            
            if (minChanged && newMin != minState.value) {
                minState.value = newMin
                hasChanged = true
            }
        }
        
        // Draw max slider (ensure it's at least as large as min)
        val maxLabel = "$safeLabel Max"
        Arena.ofConfined().use { arena ->
            val maxBuffer = arena.allocate(ValueLayout.JAVA_FLOAT)
            maxBuffer.set(ValueLayout.JAVA_FLOAT, 0, maxOf(adjustedMax, minState.value))
            val maxChanged = NativeBridge.sliderFloat(maxLabel, maxBuffer, minState.value, rangeMax, format, flags)
            val newMax = maxBuffer.get(ValueLayout.JAVA_FLOAT, 0)
            
            if (maxChanged && newMax != maxState.value) {
                maxState.value = newMax
                hasChanged = true
            }
        }
        
        changed.set(hasChanged)
    }
}

data class RangeSliderIntCommand(
    val label: String,
    val minState: ImGuiState<Int>,
    val maxState: ImGuiState<Int>,
    val rangeMin: Int,
    val rangeMax: Int,
    val format: String = "%d",
    val flags: Int = 0,
    val changed: AtomicReference<Boolean>
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        var hasChanged = false
        
        // Ensure min <= max
        val currentMin = minState.value.coerceIn(rangeMin, rangeMax)
        val currentMax = maxState.value.coerceIn(rangeMin, rangeMax)
        val adjustedMin = minOf(currentMin, currentMax)
        val adjustedMax = maxOf(currentMin, currentMax)
        
        // Draw label
        NativeBridge.text("$safeLabel: $adjustedMin - $adjustedMax")
        
        // Draw min slider
        val minLabel = "$safeLabel Min"
        Arena.ofConfined().use { arena ->
            val minBuffer = arena.allocate(ValueLayout.JAVA_INT)
            minBuffer.set(ValueLayout.JAVA_INT, 0, adjustedMin)
            val minChanged = NativeBridge.sliderInt(minLabel, minBuffer, rangeMin, adjustedMax, format, flags)
            val newMin = minBuffer.get(ValueLayout.JAVA_INT, 0)
            
            if (minChanged && newMin != minState.value) {
                minState.value = newMin
                hasChanged = true
            }
        }
        
        // Draw max slider (ensure it's at least as large as min)
        val maxLabel = "$safeLabel Max"
        Arena.ofConfined().use { arena ->
            val maxBuffer = arena.allocate(ValueLayout.JAVA_INT)
            maxBuffer.set(ValueLayout.JAVA_INT, 0, maxOf(adjustedMax, minState.value))
            val maxChanged = NativeBridge.sliderInt(maxLabel, maxBuffer, minState.value, rangeMax, format, flags)
            val newMax = maxBuffer.get(ValueLayout.JAVA_INT, 0)
            
            if (maxChanged && newMax != maxState.value) {
                maxState.value = newMax
                hasChanged = true
            }
        }
        
        changed.set(hasChanged)
    }
}

// Multi-selection listbox command using child window and selectables
data class MultiSelectListBoxCommand(
    val label: String,
    val items: List<String>,
    val selectedIndices: ImGuiState<Set<Int>>,
    val sizeX: Float = 0f,
    val sizeY: Float = 0f,
    val changed: AtomicReference<Boolean>
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        var hasChanged = false
        val currentSelection = selectedIndices.value.toMutableSet()
        
        // First draw the label
        NativeBridge.text(safeLabel)
        
        // For simplicity, just render items as individual selectables
        // without using a child window (since many functions are missing)
        items.forEachIndexed { index, item ->
            val isSelected = index in currentSelection
            val clicked = NativeBridge.selectable(item, isSelected)
            
            if (clicked) {
                if (isSelected) {
                    currentSelection.remove(index)
                } else {
                    currentSelection.add(index)
                }
                hasChanged = true
            }
        }
        
        if (hasChanged) {
            selectedIndices.value = currentSelection
        }
        changed.set(hasChanged)
    }
}

// Images
data class ImageCommand(
    val texture: ImGuiTexture,
    val sizeX: Float,
    val sizeY: Float,
    val uv0X: Float = 0f,
    val uv0Y: Float = 0f,
    val uv1X: Float = 1f,
    val uv1Y: Float = 1f,
    val tintColor: Int = -1,
    val borderColor: Int = 0
) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.image(texture, sizeX, sizeY, uv0X, uv0Y, uv1X, uv1Y, tintColor, borderColor)
    }
}

data class ImageButtonCommand(
    val texture: ImGuiTexture,
    val sizeX: Float,
    val sizeY: Float,
    val uv0X: Float = 0f,
    val uv0Y: Float = 0f,
    val uv1X: Float = 1f,
    val uv1Y: Float = 1f,
    val framePadding: Int = -1,
    val bgColor: Int = 0,
    val tintColor: Int = -1,
    val onClick: () -> Unit
) : ImGuiCommand() {
    override fun execute() {
        if (NativeBridge.imageButton(texture, sizeX, sizeY, uv0X, uv0Y, uv1X, uv1Y, framePadding, bgColor, tintColor)) {
            try { onClick() } catch (e: Throwable) {
                System.err.println("onClick failed for image button: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}

/**
 * Draw a nine-slice graphic skin on the current window's draw list.
 * Call this early in your window block so content is drawn on top.
 * Edges can be tiled or stretched; center is stretched for simplicity.
 */
data class WindowNineSliceCommand(
    val skin: GraphicNineSlice,
    val tileEdges: Boolean = true,
    val tileCenter: Boolean = true
) : ImGuiCommand() {
    override fun execute() {
        val drawList = NativeBridge.getWindowDrawList() ?: return
        val (wx, wy) = NativeBridge.getWindowPos()
        val (ww, wh) = NativeBridge.getWindowSize()
        if (ww <= 0f || wh <= 0f) return

        // Corner sizes
        val tlW = skin.topLeft.width.toFloat();     val tlH = skin.topLeft.height.toFloat()
        val trW = skin.topRight.width.toFloat();    val trH = skin.topRight.height.toFloat()
        val blW = skin.bottomLeft.width.toFloat();  val blH = skin.bottomLeft.height.toFloat()
        val brW = skin.bottomRight.width.toFloat(); val brH = skin.bottomRight.height.toFloat()

        // Edge nominal sizes
        val tH = skin.top.height.toFloat()
        val bH = skin.bottom.height.toFloat()
        val lW = skin.left.width.toFloat()
        val rW = skin.right.width.toFloat()

        // Compute inner rect
        val x0 = wx
        val y0 = wy
        val x1 = wx + ww
        val y1 = wy + wh

        val topY = y0
        val bottomY = y1 - bH
        val leftX = x0
        val rightX = x1 - rW

        val topEdgeX0 = x0 + tlW
        val topEdgeX1 = x1 - trW
        val leftEdgeY0 = y0 + tlH
        val leftEdgeY1 = y1 - blH

        // Clamp in case window is very small
        fun clampSize(v: Float) = if (v < 0f) 0f else v

        val topEdgeW = clampSize(topEdgeX1 - topEdgeX0)
        val leftEdgeH = clampSize(leftEdgeY1 - leftEdgeY0)

        // Draw corners
        if (tlW > 0f && tlH > 0f) NativeBridge.drawListAddImage(drawList, skin.topLeft.texture, x0, y0, x0 + tlW, y0 + tlH)
        if (trW > 0f && trH > 0f) NativeBridge.drawListAddImage(drawList, skin.topRight.texture, x1 - trW, y0, x1, y0 + trH)
        if (blW > 0f && blH > 0f) NativeBridge.drawListAddImage(drawList, skin.bottomLeft.texture, x0, y1 - blH, x0 + blW, y1)
        if (brW > 0f && brH > 0f) NativeBridge.drawListAddImage(drawList, skin.bottomRight.texture, x1 - brW, y1 - brH, x1, y1)

        // Draw top edge (tile or stretch)
        if (topEdgeW > 0f && tH > 0f) {
            val pieceW = skin.top.width.toFloat()
            if (tileEdges && pieceW > 0f) {
                var cx = topEdgeX0
                while (cx < topEdgeX1 - 0.5f) {
                    val w = min(pieceW, topEdgeX1 - cx)
                    NativeBridge.drawListAddImage(drawList, skin.top.texture, cx, topY, cx + w, topY + tH)
                    cx += w
                }
            } else {
                NativeBridge.drawListAddImage(drawList, skin.top.texture, topEdgeX0, topY, topEdgeX1, topY + tH)
            }
        }

        // Bottom edge
        if (topEdgeW > 0f && bH > 0f) {
            val pieceW = skin.bottom.width.toFloat()
            val y = y1 - bH
            if (tileEdges && pieceW > 0f) {
                var cx = topEdgeX0
                while (cx < topEdgeX1 - 0.5f) {
                    val w = min(pieceW, topEdgeX1 - cx)
                    NativeBridge.drawListAddImage(drawList, skin.bottom.texture, cx, y, cx + w, y + bH)
                    cx += w
                }
            } else {
                NativeBridge.drawListAddImage(drawList, skin.bottom.texture, topEdgeX0, y, topEdgeX1, y + bH)
            }
        }

        // Left edge
        if (leftEdgeH > 0f && lW > 0f) {
            val pieceH = skin.left.height.toFloat()
            if (tileEdges && pieceH > 0f) {
                var cy = leftEdgeY0
                while (cy < leftEdgeY1 - 0.5f) {
                    val h = min(pieceH, leftEdgeY1 - cy)
                    NativeBridge.drawListAddImage(drawList, skin.left.texture, leftX, cy, leftX + lW, cy + h)
                    cy += h
                }
            } else {
                NativeBridge.drawListAddImage(drawList, skin.left.texture, leftX, leftEdgeY0, leftX + lW, leftEdgeY1)
            }
        }

        // Right edge
        if (leftEdgeH > 0f && rW > 0f) {
            val pieceH = skin.right.height.toFloat()
            val x = rightX
            if (tileEdges && pieceH > 0f) {
                var cy = leftEdgeY0
                while (cy < leftEdgeY1 - 0.5f) {
                    val h = min(pieceH, leftEdgeY1 - cy)
                    NativeBridge.drawListAddImage(drawList, skin.right.texture, x, cy, x + rW, cy + h)
                    cy += h
                }
            } else {
                NativeBridge.drawListAddImage(drawList, skin.right.texture, x, leftEdgeY0, x + rW, leftEdgeY1)
            }
        }

        // Center fill: tile or stretch
        val innerX0 = x0 + lW
        val innerY0 = y0 + tH
        val innerX1 = x1 - rW
        val innerY1 = y1 - bH
        if (innerX1 > innerX0 && innerY1 > innerY0) {
            if (tileCenter) {
                val cw = skin.center.width.toFloat().coerceAtLeast(1f)
                val ch = skin.center.height.toFloat().coerceAtLeast(1f)
                var cy = innerY0
                while (cy < innerY1 - 0.5f) {
                    val h = min(ch, innerY1 - cy)
                    var cx = innerX0
                    while (cx < innerX1 - 0.5f) {
                        val w = min(cw, innerX1 - cx)
                        NativeBridge.drawListAddImage(drawList, skin.center.texture, cx, cy, cx + w, cy + h)
                        cx += w
                    }
                    cy += h
                }
            } else {
                NativeBridge.drawListAddImage(drawList, skin.center.texture, innerX0, innerY0, innerX1, innerY1)
            }
        }
    }
}

/**
 * Overlay a vertical scrollbar styled with graphics for track and thumb.
 * Assumes it runs inside the scrollable child so getWindowPos/Size and scroll values refer to it.
 * Keeps default ImGui scrolling behavior; this purely draws visuals over the reserved scrollbar area.
 */
data class OverlayVScrollbarCommand(
    val trackGraphicId: Int,
    val thumbGraphicId: Int,
    val tileTrack: Boolean = true,
    val minThumbPx: Float = 24f,
    val drawAboveAll: Boolean = true
) : ImGuiCommand() {
    override fun execute() {
        val drawList = if (drawAboveAll) {
            NativeBridge.getForegroundDrawList() ?: return
        } else {
            NativeBridge.getWindowDrawList() ?: return
        }

        val (wx, wy) = NativeBridge.getWindowPos()
        val (ww, wh) = NativeBridge.getWindowSize()
        if (ww <= 0f || wh <= 0f) return

        val scrollY = NativeBridge.getScrollY()
        val scrollMaxY = NativeBridge.getScrollMaxY().coerceAtLeast(0f)

        val trackGraphic = Cache.graphic(trackGraphicId) ?: return
        val trackTex = trackGraphic.getTexture()
        val trackW = trackGraphic.maxWidth.toFloat().coerceAtLeast(1f)
        val trackH = trackGraphic.maxHeight.toFloat().coerceAtLeast(1f)

        val thumbGraphic = Cache.graphic(thumbGraphicId) ?: return
        val thumbTex = thumbGraphic.getTexture()
        val thumbW = thumbGraphic.maxWidth.toFloat().coerceAtLeast(1f)
        val thumbBaseH = thumbGraphic.maxHeight.toFloat().coerceAtLeast(1f)

        // Bar rect on the right side of the child window
        val barX1 = wx + ww
        val barX0 = barX1 - trackW
        val barY0 = wy
        val barY1 = wy + wh

        // Draw track (tile vertically)
        if (tileTrack) {
            var cy = barY0
            while (cy < barY1 - 0.5f) {
                val h = min(trackH, barY1 - cy)
                NativeBridge.drawListAddImage(drawList, trackTex, barX0, cy, barX1, cy + h)
                cy += h
            }
        } else {
            NativeBridge.drawListAddImage(drawList, trackTex, barX0, barY0, barX1, barY1)
        }

        // Compute thumb size proportional to visible fraction
        val visibleRatio = if (scrollMaxY <= 0f) 1f else (wh / (wh + scrollMaxY)).coerceIn(0.1f, 1f)
        val thumbH = max(minThumbPx, thumbBaseH * visibleRatio)

        // Compute thumb position from scroll ratio
        val travel = (barY1 - barY0 - thumbH).coerceAtLeast(0f)
        val ratio = if (scrollMaxY <= 0f) 0f else (scrollY / scrollMaxY).coerceIn(0f, 1f)
        val thumbY0 = barY0 + travel * ratio
        val thumbY1 = thumbY0 + thumbH
        val thumbX0 = barX1 - thumbW // align thumb to right inside track
        val thumbX1 = thumbX0 + thumbW

        NativeBridge.drawListAddImage(drawList, thumbTex, thumbX0, thumbY0, thumbX1, thumbY1)
    }
}
