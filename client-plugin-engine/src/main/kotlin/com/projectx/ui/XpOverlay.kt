package com.projectx.ui

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Priority
import com.projectx.game.invention.InventionXpTracker
import com.projectx.game.nxt.MainState
import com.projectx.script.api.getXp
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.LayoutScope
import com.projectx.ui.backend.dsl.scopes.readout
import com.projectx.ui.backend.dsl.scopes.section
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.scopes.valueRow
import com.projectx.ui.backend.flags.WindowFlags
import com.projectx.ui.backend.rendering.ImGUIRender
import com.projectx.util.format
import com.projectx.util.formatElapsedTime
import com.projectx.util.getLevelForXp
import com.projectx.util.getUnitsPerHour
import com.projectx.util.getXpForLevel
import org.projectx.core.game.skill.Skill

// Reads UIState.xpData, which the stat hook fills whether or not a script is running.
object XpOverlay {

    @JvmStatic
    @ImGUIRender(priority = Priority.LOW)
    fun render() {
        try {
            if (!UIState.xpOverlayEnabled.value || Bootstrap.client.mainState != MainState.LOGGED_IN) return
            val rows = UIState.xpData
                .filterValues { it.second > 0 }
                .map { (skill, data) -> Row(skill, data.first, data.second) }
                .sortedByDescending { it.gained }
            ImGuiDsl.window(TITLE, WindowFlags.AlwaysAutoResize) { draw(rows) }
        } catch (t: Throwable) {
            println("Error in XP overlay: ${t.message}")
            t.printStackTrace()
        }
    }

    private class Row(val skill: Skill, val startMillis: Long, val gained: Int)

    private fun LayoutScope.draw(rows: List<Row>) {
        if (rows.isEmpty()) {
            text("Waiting for experience")
            return
        }
        val start = rows.minOf { it.startMillis }
        readout(TABLE) {
            valueRow("Runtime", formatElapsedTime(System.currentTimeMillis(), start))
            valueRow("Total", rate(rows.sumOf { it.gained }, start))
        }
        rows.forEach { skillSection(it) }
        inventionSection()
    }

    // Only experience gained is tracked, so levels gained are worked back from it.
    private fun LayoutScope.skillSection(row: Row) {
        val xp = getXp(row.skill)
        val level = getLevelForXp(xp)
        val levelsGained = level - getLevelForXp(xp - row.gained)
        section(if (levelsGained > 0) "${row.skill.label} $level (+$levelsGained)" else "${row.skill.label} $level")
        readout("$TABLE-${row.skill.ordinal}") {
            valueRow("XP", rate(row.gained, row.startMillis))
            val remaining = millisToLevel(xp, level, getUnitsPerHour(row.gained, row.startMillis))
            if (remaining >= 0) valueRow("Level ${level + 1} in", formatElapsedTime(remaining, 0))
        }
    }

    private fun LayoutScope.inventionSection() {
        val itemRate = InventionXpTracker.totalItemXpPerHour
        val inventionRate = InventionXpTracker.totalEffectiveInvXpPerHour
        if (itemRate <= 0 && inventionRate <= 0) return
        section("Augmented gear")
        readout("$TABLE-invention") {
            valueRow("Item XP", "${format(itemRate)}/hr")
            valueRow("Invention XP", "${format(inventionRate)}/hr")
        }
    }

    private fun rate(gained: Int, startMillis: Long): String =
        "${format(gained)} (${format(getUnitsPerHour(gained, startMillis))}/hr)"

    private fun millisToLevel(xp: Int, level: Int, ratePerHour: Int): Long {
        if (level >= MAX_LEVEL || ratePerHour <= 0) return -1
        return (getXpForLevel(level + 1) - xp).toLong() * MILLIS_PER_HOUR / ratePerHour
    }

    private val Skill.label get() = name.lowercase().replaceFirstChar { it.uppercase() }

    private const val TITLE = "Experience"
    private const val TABLE = "xp-overlay"
    private const val MAX_LEVEL = 120
    private const val MILLIS_PER_HOUR = 3_600_000L
}
