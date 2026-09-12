package com.projectx.script.impl.devin.solak

import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.native.NativeBridge
import java.lang.foreign.MemorySegment

class SolakHudData(
    val headline: String,
    val headlineAccent: Int,
    val badge: Pair<String, Int>?,
    val instruction: Pair<String, Int>?,
    val note: Pair<String, Int>?,
    val lines: List<Pair<String, Int>>,
    val bars: List<Bar>
) {
    /** A null [fraction] means the maximum is unknown, so no bar is drawn - only the raw value. */
    class Bar(val label: String, val value: String, val fraction: Float?, val color: Int)
}

object SolakHudRenderer {

    fun draw(data: SolakHudData, drawList: MemorySegment, wx: Float, wy: Float, ww: Float) {
        val width = ww.coerceAtLeast(MIN_WIDTH)
        val right = wx + width
        val innerLeft = wx + PAD
        val innerRight = right - PAD
        val lineH = NativeBridge.calcTextSize("X").second

        var height = PAD + HEADLINE_HEIGHT
        if (data.instruction != null) height += ROW_GAP + INSTRUCTION_HEIGHT
        if (data.note != null) height += NOTE_HEIGHT
        height += data.lines.size * TEXT_LINE_HEIGHT
        if (data.bars.isNotEmpty()) height += DIVIDER_GAP * 2 + 1f + data.bars.sumOf { it.blockHeight().toDouble() }.toFloat()
        height += PAD

        NativeBridge.drawListAddRectFilled(drawList, wx, wy, right, wy + height, CARD_FILL, CARD_ROUNDING)
        NativeBridge.drawListAddRect(drawList, wx, wy, right, wy + height, CARD_EDGE, CARD_ROUNDING, 0, 1f)

        var y = wy + PAD
        headlineRow(data, drawList, innerLeft, innerRight, y, lineH)
        y += HEADLINE_HEIGHT

        data.instruction?.let { (label, accent) ->
            y += ROW_GAP
            NativeBridge.drawListAddRectFilled(drawList, innerLeft, y, innerRight, y + INSTRUCTION_HEIGHT, accent, ROW_ROUNDING)
            centered(drawList, (innerLeft + innerRight) * 0.5f, y + (INSTRUCTION_HEIGHT - lineH) * 0.5f, label, ImGuiColors.WHITE)
            y += INSTRUCTION_HEIGHT
        }
        data.note?.let { (text, color) ->
            centered(drawList, (innerLeft + innerRight) * 0.5f, y + 2f, text, color)
            y += NOTE_HEIGHT
        }
        for ((text, color) in data.lines) {
            NativeBridge.drawListAddText(drawList, innerLeft, y, color, text)
            y += TEXT_LINE_HEIGHT
        }
        if (data.bars.isEmpty()) return

        y += DIVIDER_GAP
        NativeBridge.drawListAddRectFilled(drawList, innerLeft, y, innerRight, y + 1f, DIVIDER)
        y += 1f + DIVIDER_GAP
        for (bar in data.bars) {
            NativeBridge.drawListAddText(drawList, innerLeft, y, LABEL_TEXT, bar.label)
            val valueWidth = NativeBridge.calcTextSize(bar.value).first
            NativeBridge.drawListAddText(drawList, innerRight - valueWidth, y, VALUE_TEXT, bar.value)
            bar.fraction?.let { fraction ->
                val top = y + TEXT_LINE_HEIGHT
                NativeBridge.drawListAddRectFilled(drawList, innerLeft, top, innerRight, top + BAR_HEIGHT, BAR_TRACK, BAR_ROUNDING)
                val filled = innerLeft + (innerRight - innerLeft) * fraction.coerceIn(0f, 1f)
                if (filled > innerLeft) {
                    NativeBridge.drawListAddRectFilled(drawList, innerLeft, top, filled, top + BAR_HEIGHT, bar.color, BAR_ROUNDING)
                }
            }
            y += bar.blockHeight()
        }
    }

    private fun SolakHudData.Bar.blockHeight(): Float =
        if (fraction == null) TEXT_LINE_HEIGHT + BAR_GAP else TEXT_LINE_HEIGHT + BAR_HEIGHT + BAR_GAP

    private fun headlineRow(
        data: SolakHudData,
        drawList: MemorySegment,
        innerLeft: Float,
        innerRight: Float,
        y: Float,
        lineH: Float
    ) {
        NativeBridge.drawListAddRectFilled(drawList, innerLeft, y, innerRight, y + HEADLINE_HEIGHT, ROW_FILL, ROW_ROUNDING)
        NativeBridge.drawListAddRectFilled(drawList, innerLeft, y, innerLeft + ACCENT_WIDTH, y + HEADLINE_HEIGHT, data.headlineAccent, ROW_ROUNDING)
        NativeBridge.drawListAddText(drawList, innerLeft + ACCENT_WIDTH + PAD, y + (HEADLINE_HEIGHT - lineH) * 0.5f, ImGuiColors.WHITE, data.headline)

        data.badge?.let { (text, accent) ->
            val badgeWidth = NativeBridge.calcTextSize(text).first + BADGE_PAD * 2f
            val badgeLeft = innerRight - PAD * 0.5f - badgeWidth
            val badgeTop = y + (HEADLINE_HEIGHT - BADGE_HEIGHT) * 0.5f
            NativeBridge.drawListAddRectFilled(drawList, badgeLeft, badgeTop, badgeLeft + badgeWidth, badgeTop + BADGE_HEIGHT, accent, BADGE_HEIGHT * 0.5f)
            NativeBridge.drawListAddText(drawList, badgeLeft + BADGE_PAD, badgeTop + (BADGE_HEIGHT - lineH) * 0.5f, ImGuiColors.WHITE, text)
        }
    }

    private fun centered(drawList: MemorySegment, centerX: Float, y: Float, text: String, color: Int) {
        NativeBridge.drawListAddText(drawList, centerX - NativeBridge.calcTextSize(text).first * 0.5f, y, color, text)
    }

    const val DEFAULT_WIDTH = 320f
    const val DEFAULT_HEIGHT = 240f
    private const val MIN_WIDTH = 240f
    private const val PAD = 10f
    private const val ROW_GAP = 6f
    private const val HEADLINE_HEIGHT = 34f
    private const val INSTRUCTION_HEIGHT = 30f
    private const val NOTE_HEIGHT = 18f
    private const val TEXT_LINE_HEIGHT = 16f
    private const val BAR_HEIGHT = 6f
    private const val BAR_GAP = 8f
    private const val ACCENT_WIDTH = 4f
    private const val BADGE_HEIGHT = 17f
    private const val BADGE_PAD = 7f
    private const val CARD_ROUNDING = 8f
    private const val ROW_ROUNDING = 5f
    private const val BAR_ROUNDING = 3f
    private const val DIVIDER_GAP = 7f

    private val CARD_FILL = ImGuiColors.rgba(26, 36, 26, 238)
    private val CARD_EDGE = ImGuiColors.rgba(104, 148, 82, 220)
    private val ROW_FILL = ImGuiColors.rgba(40, 54, 38, 235)
    private val DIVIDER = ImGuiColors.rgba(104, 148, 82, 120)
    private val BAR_TRACK = ImGuiColors.rgba(18, 24, 18, 220)
    private val LABEL_TEXT = ImGuiColors.rgba(178, 214, 160)
    private val VALUE_TEXT = ImGuiColors.rgba(238, 246, 226)

    val NEUTRAL_ACCENT = ImGuiColors.rgba(104, 148, 82, 235)
    val CRITICAL = ImGuiColors.rgba(226, 44, 74, 240)
    val WARNING = ImGuiColors.rgba(214, 142, 30, 235)
    val INFO = ImGuiColors.rgba(64, 132, 96, 225)
    val GOOD = ImGuiColors.rgba(120, 226, 140, 255)
    val MUTED = ImGuiColors.rgba(150, 168, 142, 255)
    val BOSS_BAR = ImGuiColors.rgba(198, 82, 62, 240)
    val LIMB_BAR = ImGuiColors.rgba(126, 178, 96, 240)
    val TRACKED_BAR = ImGuiColors.rgba(226, 186, 76, 240)
}
