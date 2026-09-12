package com.projectx.ui.tabs

import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.ui.backend.dsl.utils.ImGuiTableColumnFlags
import com.projectx.ui.backend.dsl.utils.ImGuiTableFlags
import com.projectx.util.format
import com.projectx.util.getFormattedUnitsPerHour

object XpTrackerTab {
    fun ChildScope.render() {
        section("XP Tracking")

        if (UIState.xpData.isEmpty()) {
            text("No skills tracked yet.")
        } else {
            table(id = "XPTable", columns = 4, flags = ImGuiTableFlags.SizingFixedFit) {
                setupColumn("Skill", ImGuiTableColumnFlags.WidthFixed, 100f)
                setupColumn("XP Gained", ImGuiTableColumnFlags.WidthFixed, 100f)
                setupColumn("XP/Hour", ImGuiTableColumnFlags.WidthFixed, 100f)
                setupColumn("Actions", ImGuiTableColumnFlags.WidthFixed, 150f)
                headersRow()

                UIState.xpData.toList().forEach { (skill, data) ->
                    nextRow()
                    nextColumn()
                    text(skill.name)
                    nextColumn()
                    text(format(data.second))
                    nextColumn()
                    text(getFormattedUnitsPerHour(data.second, data.first))
                    nextColumn()
                    smallButton("Reset##$skill") {
                        UIState.xpData[skill] = System.currentTimeMillis() to 0
                    }
                    sameLine()
                    smallButton("Remove##$skill") {
                        UIState.xpData.remove(skill)
                    }
                }
            }
        }
    }

}
