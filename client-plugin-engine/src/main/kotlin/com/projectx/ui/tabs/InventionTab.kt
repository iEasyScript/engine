package com.projectx.ui.tabs

import com.projectx.game.invention.InventionComponentTracker
import com.projectx.game.invention.InventionXpTracker
import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.ui.backend.dsl.utils.ImGuiTableColumnFlags
import com.projectx.ui.backend.dsl.utils.ImGuiTableFlags
import com.projectx.util.format

object InventionTab {
    fun ChildScope.render() {
        renderXpTracker()
        spacing()
        separator()
        renderComponentTracker()
    }

    private fun ChildScope.renderXpTracker() {
        section("Effective XP/hr")
        textWrapped("Estimated from augmented item XP.")
        checkbox("Track augmented item XP", UIState.inventionXpTrackerEnabled)
        if (!UIState.inventionXpTrackerEnabled.value) {
            text("Enable to periodically scan augmented items and estimate Invention XP/hr.")
            return
        }

        val tracker = InventionXpTracker
        text("Assuming level-${tracker.assumedSiphonLevel} siphons (Invention ${tracker.inventionLevel})")

        val rows = tracker.rows
        if (rows.isEmpty()) {
            text("No augmented items gaining XP detected - train with augmented gear equipped/carried.")
            return
        }

        text(
            "Total: ${format(tracker.totalEffectiveInvXpPerHour)} Inv XP/hr" +
                "  -  item ${format(tracker.totalItemXpPerHour)} XP/hr"
        )

        table(id = "InventionItemXpTable", columns = 6, flags = ImGuiTableFlags.SizingFixedFit) {
            setupColumn("Item", ImGuiTableColumnFlags.WidthFixed, 170f)
            setupColumn("Tier", ImGuiTableColumnFlags.WidthFixed, 45f)
            setupColumn("Lvl", ImGuiTableColumnFlags.WidthFixed, 40f)
            setupColumn("Item XP", ImGuiTableColumnFlags.WidthFixed, 90f)
            setupColumn("Item XP/hr", ImGuiTableColumnFlags.WidthFixed, 90f)
            setupColumn("Eff. Inv XP/hr", ImGuiTableColumnFlags.WidthFixed, 110f)
            headersRow()

            rows.forEach { row ->
                nextRow()
                nextColumn()
                text(row.name)
                nextColumn()
                text(if (row.tierKnown) "${row.tier}" else "${row.tier}?")
                nextColumn()
                text("${row.itemLevel}")
                nextColumn()
                text(format(row.itemXp))
                nextColumn()
                text(format(row.itemXpPerHour))
                nextColumn()
                text(format(row.effectiveInvXpPerHour))
            }
        }

        smallButton("Reset##InventionItemXp") { tracker.reset() }
    }

    private fun ChildScope.renderComponentTracker() {
        section("Components")
        textWrapped("Materials gained per hour.")
        checkbox("Track invention components", UIState.inventionComponentTrackerEnabled)
        if (!UIState.inventionComponentTrackerEnabled.value) {
            text("Enable to track every invention component's accumulation rate.")
            return
        }

        val tracker = InventionComponentTracker
        val rows = tracker.rows
        if (rows.isEmpty()) {
            text("No components gained yet - disassemble items to start accumulating.")
            return
        }

        text("Total: ${format(tracker.totalPerHour)} components/hr")

        table(id = "InventionComponentTable", columns = 4, flags = ImGuiTableFlags.SizingFixedFit) {
            setupColumn("Component", ImGuiTableColumnFlags.WidthFixed, 200f)
            setupColumn("Held", ImGuiTableColumnFlags.WidthFixed, 90f)
            setupColumn("Gained", ImGuiTableColumnFlags.WidthFixed, 90f)
            setupColumn("Per Hour", ImGuiTableColumnFlags.WidthFixed, 90f)
            headersRow()

            rows.forEach { row ->
                nextRow()
                nextColumn()
                text(row.name)
                nextColumn()
                text(format(row.count))
                nextColumn()
                text(format(row.gained))
                nextColumn()
                text(format(row.perHour))
            }
        }

        smallButton("Reset##InventionComponents") { tracker.reset() }
    }
}
