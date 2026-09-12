package com.projectx.script.impl.devin.zuk

import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.native.ImGuiTexture
import com.projectx.ui.backend.native.NativeBridge
import java.lang.foreign.MemorySegment

/**
 * One immutable snapshot of everything the coach card shows, assembled on the main-logic thread
 * where game state is safe to read. [ZukHudRenderer] draws it on the render thread from live
 * window geometry, so the card follows the chromeless window the player drags and resizes.
 */
class ZukHudData(
    val prayerLabel: String,
    val prayerAccent: Int,
    val icon: ImGuiTexture?,
    val mode: Pair<String, Int>?,
    val instruction: Pair<String, Int>?,
    val abilities: String?,
    val sections: List<Section>
) {
    class Section(val title: String, val tokens: List<Pair<String, Int>>)
}

object ZukHudRenderer {

    fun draw(data: ZukHudData, drawList: MemorySegment, wx: Float, wy: Float, ww: Float) {
        val width = ww.coerceAtLeast(MIN_WIDTH)
        val right = wx + width
        val innerLeft = wx + PAD
        val innerRight = right - PAD
        val lineH = NativeBridge.calcTextSize("X").second

        val wrapped = data.sections.map { it to wrap(it.tokens, width - PAD * 2 - SECTION_INDENT) }

        var height = PAD + PRAYER_HEIGHT
        if (data.instruction != null) height += ROW_GAP + ACTION_HEIGHT
        if (data.abilities != null) height += ABILITY_HEIGHT
        if (wrapped.isNotEmpty()) {
            height += DIVIDER_GAP * 2 + 1f
            for ((_, lines) in wrapped) height += SECTION_TITLE_HEIGHT + lines.size * SECTION_LINE_HEIGHT + SECTION_GAP
            height -= SECTION_GAP
        }
        height += PAD

        NativeBridge.drawListAddRectFilled(drawList, wx, wy, right, wy + height, CARD_FILL, CARD_ROUNDING)
        NativeBridge.drawListAddRect(drawList, wx, wy, right, wy + height, CARD_EDGE, CARD_ROUNDING, 0, 1f)

        var y = wy + PAD
        y = prayerRow(data, drawList, innerLeft, innerRight, y, lineH)

        data.instruction?.let { (label, accent) ->
            y += ROW_GAP
            NativeBridge.drawListAddRectFilled(drawList, innerLeft, y, innerRight, y + ACTION_HEIGHT, accent, ROW_ROUNDING)
            centered(drawList, (innerLeft + innerRight) * 0.5f, y + (ACTION_HEIGHT - lineH) * 0.5f, label, ImGuiColors.WHITE)
            y += ACTION_HEIGHT
        }
        data.abilities?.let {
            centered(drawList, (innerLeft + innerRight) * 0.5f, y + 2f, it, ABILITY_TEXT)
            y += ABILITY_HEIGHT
        }
        if (wrapped.isEmpty()) return

        y += DIVIDER_GAP
        NativeBridge.drawListAddRectFilled(drawList, innerLeft, y, innerRight, y + 1f, DIVIDER)
        y += 1f + DIVIDER_GAP
        for ((section, lines) in wrapped) {
            NativeBridge.drawListAddText(drawList, innerLeft, y, SECTION_TITLE, section.title)
            y += SECTION_TITLE_HEIGHT
            for (line in lines) {
                var x = innerLeft + SECTION_INDENT
                for ((token, color) in line) {
                    NativeBridge.drawListAddText(drawList, x, y, color, token)
                    x += NativeBridge.calcTextSize(token).first + TOKEN_GAP
                }
                y += SECTION_LINE_HEIGHT
            }
            y += SECTION_GAP
        }
    }

    private fun prayerRow(
        data: ZukHudData,
        drawList: MemorySegment,
        innerLeft: Float,
        innerRight: Float,
        y: Float,
        lineH: Float
    ): Float {
        NativeBridge.drawListAddRectFilled(drawList, innerLeft, y, innerRight, y + PRAYER_HEIGHT, ROW_FILL, ROW_ROUNDING)
        NativeBridge.drawListAddRectFilled(drawList, innerLeft, y, innerLeft + ACCENT_WIDTH, y + PRAYER_HEIGHT, data.prayerAccent, ROW_ROUNDING)

        var textX = innerLeft + ACCENT_WIDTH + PAD
        data.icon?.let {
            val iconY = y + (PRAYER_HEIGHT - ICON_SIZE) * 0.5f
            NativeBridge.drawListAddImage(drawList, it, textX, iconY, textX + ICON_SIZE, iconY + ICON_SIZE)
            textX += ICON_SIZE + 8f
        }
        NativeBridge.drawListAddText(drawList, textX, y + (PRAYER_HEIGHT - lineH) * 0.5f, ImGuiColors.WHITE, data.prayerLabel)

        data.mode?.let { (text, accent) ->
            val badgeWidth = NativeBridge.calcTextSize(text).first + BADGE_PAD * 2f
            val badgeLeft = innerRight - PAD * 0.5f - badgeWidth
            val badgeTop = y + (PRAYER_HEIGHT - BADGE_HEIGHT) * 0.5f
            NativeBridge.drawListAddRectFilled(drawList, badgeLeft, badgeTop, badgeLeft + badgeWidth, badgeTop + BADGE_HEIGHT, accent, BADGE_HEIGHT * 0.5f)
            NativeBridge.drawListAddText(drawList, badgeLeft + BADGE_PAD, badgeTop + (BADGE_HEIGHT - lineH) * 0.5f, ImGuiColors.WHITE, text)
        }
        return y + PRAYER_HEIGHT
    }

    private fun centered(drawList: MemorySegment, centerX: Float, y: Float, text: String, color: Int) {
        NativeBridge.drawListAddText(drawList, centerX - NativeBridge.calcTextSize(text).first * 0.5f, y, color, text)
    }

    private fun wrap(tokens: List<Pair<String, Int>>, maxWidth: Float): List<List<Pair<String, Int>>> {
        val lines = ArrayList<List<Pair<String, Int>>>()
        var line = ArrayList<Pair<String, Int>>()
        var x = 0f
        for (token in tokens) {
            val tokenWidth = NativeBridge.calcTextSize(token.first).first + TOKEN_GAP
            if (line.isNotEmpty() && x + tokenWidth > maxWidth) {
                lines += line
                line = ArrayList()
                x = 0f
            }
            line += token
            x += tokenWidth
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    const val DEFAULT_WIDTH = 340f
    const val DEFAULT_HEIGHT = 250f
    private const val MIN_WIDTH = 240f
    private const val PAD = 10f
    private const val ROW_GAP = 6f
    private const val PRAYER_HEIGHT = 34f
    private const val ACTION_HEIGHT = 30f
    private const val ABILITY_HEIGHT = 18f
    private const val ACCENT_WIDTH = 4f
    private const val BADGE_HEIGHT = 17f
    private const val BADGE_PAD = 7f
    private const val CARD_ROUNDING = 8f
    private const val ROW_ROUNDING = 5f
    private const val SECTION_TITLE_HEIGHT = 16f
    private const val SECTION_LINE_HEIGHT = 16f
    private const val SECTION_GAP = 6f
    private const val SECTION_INDENT = 8f
    private const val DIVIDER_GAP = 7f
    private const val TOKEN_GAP = 12f
    private const val ICON_SIZE = 24f

    /** Brown like the game's own interfaces, with a bronze edge, so it reads as native UI. */
    private val CARD_FILL = ImGuiColors.rgba(42, 34, 24, 238)
    private val CARD_EDGE = ImGuiColors.rgba(148, 118, 66, 220)
    private val ROW_FILL = ImGuiColors.rgba(59, 48, 34, 235)
    val NEUTRAL_ACCENT = ImGuiColors.rgba(122, 100, 68, 235)
    private val ABILITY_TEXT = ImGuiColors.rgba(255, 236, 160)

    /** The game's interface orange for section labels, parchment for unstyled roster entries. */
    private val SECTION_TITLE = ImGuiColors.rgba(255, 152, 31)
    val SECTION_TEXT = ImGuiColors.rgba(255, 238, 205)
    private val DIVIDER = ImGuiColors.rgba(148, 118, 66, 120)
}
