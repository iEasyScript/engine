package com.projectx.script.impl.devin.aiobozocombat

import com.projectx.ui.backend.dsl.scopes.LayoutScope
import com.projectx.ui.backend.dsl.scopes.child
import com.projectx.ui.backend.dsl.scopes.image
import com.projectx.ui.backend.dsl.scopes.popStyleColor
import com.projectx.ui.backend.dsl.scopes.popStyleVar
import com.projectx.ui.backend.dsl.scopes.pushStyleColor
import com.projectx.ui.backend.dsl.scopes.pushStyleVar
import com.projectx.ui.backend.dsl.scopes.sameLine
import com.projectx.ui.backend.dsl.scopes.setCursorPos
import com.projectx.ui.backend.dsl.scopes.setCursorPosX
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiColors.hex
import com.projectx.ui.backend.dsl.utils.ImGuiColors.withAlpha
import com.projectx.ui.backend.dsl.utils.ImGuiStyleVar
import com.projectx.ui.backend.flags.WindowFlags
import com.projectx.ui.backend.native.ImGuiTexture
import kotlin.math.abs

/**
 * Draws the queue as a strip of action-bar tiles: a brown rail, square slots the size of their icon,
 * the keybind stamped on the tile the way the game draws it, and the next press ringed in gold.
 *
 * Tiles are square and icon-sized on purpose. An earlier layout put the ability name inside the card,
 * which made card width a function of the longest name and left a small icon marooned in a wide box.
 * The name lives in a single caption under the rail instead.
 */
object QueueRenderer {

    fun LayoutScope.drawQueue(model: QueueModel, tickPhase: Float, texture: (Int) -> ImGuiTexture?) {
        pulse = tickPhase
        val width = railWidth(model.used.size, model.upcoming.size)

        pushStyleColor(ImGuiCol.ChildBg, RAIL)
        pushStyleVar(ImGuiStyleVar.ChildRounding, RAIL_ROUNDING)
        pushStyleVar(ImGuiStyleVar.WindowPadding, RAIL_PAD, 0f)
        child("queue_rail##aiobozo", width, RAIL_HEIGHT) {
            pushStyleVar(ImGuiStyleVar.ItemSpacing, GAP, 0f)
            var index = 0
            for (entry in model.used) {
                if (index++ > 0) sameLine()
                slot(entry, QueueKind.USED, index, texture)
            }
            if (model.used.isNotEmpty()) sectionBreak(0)
            slot(model.next, QueueKind.NEXT, 0, texture)
            if (model.upcoming.isNotEmpty()) sectionBreak(1)
            for ((offset, entry) in model.upcoming.withIndex()) {
                if (offset > 0) sameLine()
                slot(entry, QueueKind.UPCOMING, index++, texture)
            }
            popStyleVar(1)
        }
        popStyleVar(2)
        popStyleColor(1)

        caption(model.next, width)
    }

    private fun LayoutScope.slot(
        entry: QueueEntry?,
        kind: QueueKind,
        ordinal: Int,
        texture: (Int) -> ImGuiTexture?
    ) {
        val highlighted = kind == QueueKind.NEXT
        val interrupt = highlighted && entry?.urgency == Urgency.INTERRUPT

        pushStyleColor(
            ImGuiCol.ChildBg,
            when {
                entry == null -> CARD_EMPTY
                entry.actionKind == ActionKind.ITEM -> CARD_ITEM
                entry.actionKind == ActionKind.PRAYER -> CARD_PRAYER
                entry.actionKind == ActionKind.CUE -> CARD_CUE
                else -> cardFor(kind)
            }
        )
        pushStyleColor(
            ImGuiCol.Border,
            when {
                interrupt -> ALERT
                highlighted -> GOLD
                else -> CARD_EDGE
            }
        )
        pushStyleVar(ImGuiStyleVar.ChildRounding, TILE_ROUNDING)
        pushStyleVar(ImGuiStyleVar.ChildBorderSize, if (highlighted) GOLD_EDGE else THIN_EDGE)
        pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f)

        child("slot_${kind}_$ordinal", TILE, TILE, CHILD_BORDER, NO_SCROLL) {
            val icon = entry?.iconGraphic?.takeIf { it > 0 }?.let(texture)
            if (icon == null && entry != null) {
                val words = abbreviate(entry.name)
                var y = (TILE - LINE_HEIGHT * words.size) / 2f
                pushStyleColor(ImGuiCol.Text, KEY)
                for (line in words) {
                    setCursorPos((TILE - textWidth(line)) / 2f, y)
                    text(line)
                    y += LINE_HEIGHT
                }
                popStyleColor(1)
            }
            if (icon != null) {
                val edge = if (highlighted) GOLD_EDGE else THIN_EDGE
                val inset = ((TILE - edge * 2f - ICON) / 2f).coerceAtLeast(0f)
                setCursorPos(inset, inset)
                image(icon, ICON, ICON, 0f, 0f, 1f, 1f, iconTint(kind, entry), 0)

                entry.keybind?.let { key ->
                    setCursorPos(KEY_INSET, TILE - edge * 2f - KEY_BASELINE)
                    pushStyleColor(ImGuiCol.Text, if (highlighted) GOLD_TEXT else KEY)
                    text(key)
                    popStyleColor(1)
                }
            }
        }

        popStyleVar(3)
        popStyleColor(2)
    }

    /**
     * Generic shortening for items that have no icon - no per-item table.
     *
     * Drops the dose suffix and connecting words so the two lines carry the parts that identify the
     * item: "Powerburst of vitality" reads "Powerb / vitali" rather than wasting a line on "of".
     */
    private fun abbreviate(name: String): List<String> {
        val words = name.replace(DOSE_SUFFIX, "").split(' ').filter { it.isNotBlank() }
        val meaningful = words.filter { it.lowercase() !in CONNECTING_WORDS }
        return (if (meaningful.isEmpty()) words else meaningful)
            .take(TEXT_TILE_LINES)
            .map { it.take(TEXT_TILE_CHARS) }
    }

    /**
     * The one line of prose on the panel: what to do and when.
     *
     * Counts down in ticks rather than seconds because presses land on tick boundaries - that is the
     * unit that prevents both firing early into a channel and dawdling long enough for revolution to
     * choose instead. Only an ability counts down; a prayer, an item or a positional cue is wanted
     * now, and an "in 0" suffix on those reads as a broken timer.
     */
    private fun LayoutScope.caption(next: QueueEntry?, railWidth: Float) {
        val line = when {
            next == null -> " "
            next.urgency == Urgency.INTERRUPT -> "${next.name} - ${next.reason ?: "now"}"
            next.actionKind == ActionKind.PRAYER -> "pray ${next.name}"
            next.actionKind == ActionKind.ITEM -> "use ${next.name}"
            next.actionKind == ActionKind.CUE -> next.name
            else -> "${next.name} in ${next.readyInTicks.coerceAtLeast(0)}"
        }
        val colour = when {
            next == null -> CARD_EMPTY
            next.urgency == Urgency.INTERRUPT -> ALERT_TEXT
            next.actionKind == ActionKind.CUE -> pulsed(CUE_TEXT)
            next.timing == Timing.NOW -> pulsed(READY)
            else -> WAIT
        }
        centreIn(railWidth, textWidth(line))
        pushStyleColor(ImGuiCol.Text, colour)
        text(line)
        popStyleColor(1)
    }

    /**
     * Groove between sections. `sameLine()` has no offset overload, so the wider gap either side is
     * bought by temporarily widening [ImGuiStyleVar.ItemSpacing], which is what sameLine consumes.
     */
    private fun LayoutScope.sectionBreak(ordinal: Int) {
        pushStyleVar(ImGuiStyleVar.ItemSpacing, SECTION_GAP, SECTION_GAP_Y)
        sameLine()
        pushStyleColor(ImGuiCol.ChildBg, DIVIDER)
        pushStyleVar(ImGuiStyleVar.ChildBorderSize, 0f)
        pushStyleVar(ImGuiStyleVar.ChildRounding, 0f)
        child("div_$ordinal##aiobozo", DIVIDER_WIDTH, TILE) { }
        popStyleVar(2)
        popStyleColor(1)
        sameLine()
        popStyleVar(1)
    }

    /**
     * Centres [content] inside a known [container] width.
     *
     * Deliberately arithmetic rather than `getContentRegionAvail()`: that helper allocates a fresh
     * reference per call and reads it before its deferred command executes, so it always reports
     * zero and every centred element silently renders flush left.
     */
    private fun LayoutScope.centreIn(container: Float, content: Float) {
        setCursorPosX(((container - content) / 2f).coerceAtLeast(0f))
    }

    fun textWidth(value: String): Float = value.length * GLYPH_WIDTH

    /** Width the rail will occupy, so callers can align other rows to it. */
    fun railWidthFor(used: Int, upcoming: Int): Float = railWidth(used, upcoming)

    private fun railWidth(used: Int, upcoming: Int): Float {
        val slots = used + 1 + upcoming
        val dividers = (if (used > 0) 1 else 0) + (if (upcoming > 0) 1 else 0)
        val innerGaps = (slots - 1 - dividers).coerceAtLeast(0)
        return slots * TILE + dividers * (DIVIDER_WIDTH + SECTION_GAP * 2) +
            innerGaps * GAP + RAIL_PAD * 2
    }

    private fun cardFor(kind: QueueKind): Int = when (kind) {
        QueueKind.USED -> CARD_USED
        QueueKind.NEXT -> CARD_NEXT
        QueueKind.UPCOMING -> CARD_UPCOMING
    }

    private fun iconTint(kind: QueueKind, entry: QueueEntry): Int = when {
        kind == QueueKind.USED -> USED_TINT
        kind == QueueKind.NEXT -> FULL
        else -> fade(FULL, entry.confidence)
    }

    /** Breathes the ready cue in time with the tick so "press now" is visible peripherally. */
    private fun pulsed(argb: Int): Int {
        val eased = PULSE_FLOOR + (1f - PULSE_FLOOR) * abs(1f - 2f * pulse)
        val alpha = (((argb ushr 24) and 0xFF) * eased).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (argb and 0x00FFFFFF)
    }

    /** Scales alpha only, so a faded entry keeps its hue instead of washing toward grey. */
    private fun fade(argb: Int, confidence: Float): Int {
        val alpha = ((argb ushr 24) and 0xFF) * confidence.coerceIn(MIN_FADE, 1f)
        return (alpha.toInt() shl 24) or (argb and 0x00FFFFFF)
    }

    private var pulse = 0f

    private const val TILE = 44f
    private const val ICON = 36f
    private const val KEY_INSET = 4f
    private const val KEY_BASELINE = 16f
    private const val GAP = 2f
    private const val DIVIDER_WIDTH = 3f
    private const val SECTION_GAP = 8f
    private const val SECTION_GAP_Y = 2f
    private const val RAIL_PAD = 4f
    private const val RAIL_HEIGHT = TILE
    private const val TILE_ROUNDING = 3f
    private const val RAIL_ROUNDING = 4f
    private const val GOLD_EDGE = 2f
    private const val THIN_EDGE = 1f
    private const val MIN_FADE = 0.4f
    private const val GLYPH_WIDTH = 7.1f
    private const val LINE_HEIGHT = 15f
    private const val TEXT_TILE_CHARS = 6
    private const val TEXT_TILE_LINES = 2
    private val DOSE_SUFFIX = Regex("\\s*\\(\\d+\\)\\s*$")
    private val CONNECTING_WORDS = setOf("of", "the", "a", "an", "and")
    private const val PULSE_FLOOR = 0.45f

    // ImGui packs ABGR (a<<24 | b<<16 | g<<8 | r), so raw ARGB literals come out with red and blue
    // swapped - the brown rail rendered blue. Always go through hex(), never a hand-packed int.
    private val RAIL = hex("#5C4A36")
    private val CARD_USED = hex("#221B14")
    private val CARD_NEXT = hex("#453218")
    private val CARD_UPCOMING = hex("#2B2219")
    private val CARD_EMPTY = hex("#241D15")
    private val CARD_ITEM = hex("#1F2A22")
    private val CARD_PRAYER = hex("#1B2436")
    private val CARD_CUE = hex("#33241B")
    private val CUE_TEXT = hex("#FFC24D")
    private val CARD_EDGE = hex("#52422F")
    private val DIVIDER = hex("#241D15")
    private val GOLD = hex("#D4A02A")
    private val GOLD_TEXT = hex("#FFE9A8")
    private val FULL = hex("#FFFFFF")
    private val USED_TINT = withAlpha(hex("#FFFFFF"), 95)
    private val KEY = hex("#F2ECE2")
    private val READY = hex("#5BFF8F")
    private val WAIT = hex("#C7B9A4")
    private val ALERT = hex("#D9543B")
    private val ALERT_TEXT = hex("#FF9A7A")
    private val NO_SCROLL = (WindowFlags.NoScrollbar + WindowFlags.NoScrollWithMouse).value

    /** `ImGuiChildFlags_Border`; without it `ChildBorderSize` has nothing to draw and rings vanish. */
    private const val CHILD_BORDER = 1
}
