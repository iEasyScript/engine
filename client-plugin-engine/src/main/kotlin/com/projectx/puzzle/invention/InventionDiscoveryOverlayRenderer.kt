package com.projectx.puzzle.invention

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Priority
import com.projectx.game.math.Vector2f
import com.projectx.game.nxt.MainState
import com.projectx.puzzle.invention.InventionDiscoveryOverlayState.Companion.FIRST_ICON_COMPONENT
import com.projectx.puzzle.invention.InventionDiscoveryOverlayState.Companion.INTERFACE_ID
import com.projectx.quest.overlay.pulseAlpha
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.rendering.ImGUIRender

/**
 * Draws the Invention discovery advisory: an outline on each track module (green = keep, yellow =
 * likely, cyan "1"/"2" = the next swap to make) plus a guidance panel. Advisory only — never clicks.
 *
 * Auto-discovered via [ImGUIRender]; reads only the snapshot published by [InventionDiscoveryFeature]
 * and gated interface rects (same SIGSEGV-safety as the slide-puzzle overlay).
 */
object InventionDiscoveryOverlayRenderer {

    @JvmStatic
    @ImGUIRender(priority = Priority.LOW)
    fun render() {
        val state = InventionDiscoveryFeature.overlay ?: return
        try {
            if (Bootstrap.client.mainState != MainState.LOGGED_IN) return
        } catch (_: Throwable) {
            return
        }
        backgroundDrawList {
            var anchor: IntRect? = null
            for (slot in state.slotHints.indices) {
                val rect = iconRect(slot) ?: continue
                if (anchor == null || rect.y < anchor!!.y) anchor = rect
                try {
                    drawSlot(rect, slot + 1, state.slotHints[slot])
                } catch (_: Throwable) {
                }
            }
            anchor?.let { drawPanel(it, state) }
        }
    }

    private fun BackgroundDrawListScope.drawSlot(rect: IntRect, ordinal: Int, hint: SlotHint) {
        val tl = Vector2f(rect.x - 2f, rect.y - 2f)
        val br = Vector2f(rect.x + rect.w + 2f, rect.y + rect.h + 2f)
        when (hint) {
            SlotHint.NEUTRAL -> {}
            SlotHint.LIKELY -> rect(tl, br, (ImGuiColors.YELLOW and 0x00FFFFFF) or (140 shl 24), rounding = 4f, thickness = 2f)
            SlotHint.LOCKED -> rect(tl, br, ImGuiColors.GREEN or (255 shl 24), rounding = 4f, thickness = 2.5f)
            SlotHint.SWAP_FIRST, SlotHint.SWAP_SECOND -> {
                val color = pulseAlpha(ImGuiColors.CYAN, minAlpha = 220, maxAlpha = 255)
                rect(tl, br, color, rounding = 4f, thickness = 3.5f)
                val badge = if (hint == SlotHint.SWAP_FIRST) "1" else "2"
                text(Vector2f(rect.x + 3f, rect.y + 1f), color, badge)
            }
        }
    }

    private fun BackgroundDrawListScope.drawPanel(anchor: IntRect, state: InventionDiscoveryOverlayState) {
        if (state.lines.isEmpty()) return
        val lineHeight = 16f
        val width = (state.lines.maxOf { it.length } * 7 + 16).toFloat()
        val height = state.lines.size * lineHeight + 8f
        val x = anchor.x.toFloat()
        val y = anchor.y - height - 6f
        rectFilled(Vector2f(x, y), Vector2f(x + width, y + height), (ImGuiColors.BLACK and 0x00FFFFFF) or (190 shl 24), rounding = 4f)
        val accent = if (state.solved) ImGuiColors.GREEN else ImGuiColors.CYAN
        rect(Vector2f(x, y), Vector2f(x + width, y + height), accent or (255 shl 24), rounding = 4f, thickness = 1.5f)
        for ((i, line) in state.lines.withIndex()) {
            val color = if (i == 0) ImGuiColors.WHITE else if (state.solved && i == state.lines.lastIndex) ImGuiColors.GREEN else ImGuiColors.TEXT_PRIMARY
            text(Vector2f(x + 8f, y + 4f + i * lineHeight), color or (255 shl 24), line)
        }
    }

    private fun iconRect(slot: Int): IntRect? {
        val list = try { Bootstrap.client.interfaceList } catch (_: Throwable) { return null }
        if (!(try { list.isOpen(INTERFACE_ID) } catch (_: Throwable) { false })) return null
        val comp = try { list.getComponent(INTERFACE_ID, FIRST_ICON_COMPONENT + slot) } catch (_: Throwable) { return null } ?: return null
        val r = try { comp.screenRect } catch (_: Throwable) { return null } ?: return null
        if (r.width <= 0 || r.height <= 0) return null
        return IntRect(r.x, r.y, r.width, r.height)
    }

    private data class IntRect(val x: Int, val y: Int, val w: Int, val h: Int)
}
