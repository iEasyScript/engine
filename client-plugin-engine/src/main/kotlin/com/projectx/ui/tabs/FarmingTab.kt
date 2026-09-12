package com.projectx.ui.tabs

import com.projectx.game.farming.FarmingTracker
import com.projectx.game.farming.PatchCategory
import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.ui.backend.dsl.utils.ImGuiTableColumnFlags
import com.projectx.ui.backend.dsl.utils.ImGuiTableFlags
import com.projectx.ui.backend.dsl.utils.ImGuiTreeNodeFlags

object FarmingTab {
    fun ChildScope.render() {
        section("Tracking")
        properties("farming-tracking") {
            row("Track patches") { checkbox("##farmTrack", UIState.farmingTrackerEnabled) }
            if (UIState.farmingTrackerEnabled.value) {
                row("Desktop notifications") {
                    checkbox("##farmNotify", UIState.farmingNotificationsEnabled)
                }
                row("Show other patches") {
                    checkbox("##farmSecondary", UIState.farmingShowSecondary)
                }
            }
        }
        if (!UIState.farmingTrackerEnabled.value) {
            textWrapped("Enable to monitor herb, flower, cactus, bush and fruit tree patches.")
            return
        }

        section("Patches")
        val byCategory = FarmingTracker.rows.groupBy { it.category }
        PatchCategory.entries.filter { it.primary }.forEach { renderCategory(it, byCategory[it]) }

        if (UIState.farmingShowSecondary.value) {
            PatchCategory.entries.filter { !it.primary }.forEach { renderCategory(it, byCategory[it]) }
        }
    }

    private fun ChildScope.renderCategory(category: PatchCategory, rows: List<FarmingTracker.Row>?) {
        val flags = if (category.primary) ImGuiTreeNodeFlags.DefaultOpen else ImGuiTreeNodeFlags.None
        collapsingHeader(category.plural, flags) {
            if (rows.isNullOrEmpty()) {
                text("Waiting for data - log in near or visit these patches.")
                return@collapsingHeader
            }
            table(id = "FarmTable_${category.name}", columns = 3, flags = ImGuiTableFlags.SizingFixedFit) {
                setupColumn("Patch", ImGuiTableColumnFlags.WidthFixed, 140f)
                setupColumn("Produce", ImGuiTableColumnFlags.WidthFixed, 120f)
                setupColumn("Status", ImGuiTableColumnFlags.WidthFixed, 160f)
                headersRow()
                rows.forEach { row ->
                    nextRow()
                    nextColumn()
                    text(row.name)
                    nextColumn()
                    text(row.produce.ifBlank { "-" })
                    nextColumn()
                    text(row.statusLabel)
                }
            }
        }
    }
}
