package com.projectx.ui.tabs

import org.projectx.core.game.combat.EffectRegistry
import com.projectx.ui.BuffDebuffEntry
import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.ui.backend.dsl.utils.ImGuiTableColumnFlags
import com.projectx.ui.backend.dsl.utils.ImGuiTableFlags

object BuffsDebuffsTab {
    fun ChildScope.render() {
        properties("buffs-filter") {
            row("Auto refresh") { checkbox("##buffsAutoRefresh", UIState.buffsDebuffsEnabled) }
            row("Search") { inputText("##buffsSearch", UIState.buffsDebuffsSearchText) }
        }

        section("Effects")
        button("Refresh") {
            loadBuffsDebuffs()
        }
        sameLine()
        button("Clear") {
            UIState.buffsDebuffsData.clear()
        }

        table(
            id = "BuffsDebuffsTable",
            columns = 5,
            flags = ImGuiTableFlags.SizingStretchSame or ImGuiTableFlags.BordersInner
        ) {
            setupColumn("Effect", flags = ImGuiTableColumnFlags.WidthFixed)
            setupColumn("Type", flags = ImGuiTableColumnFlags.WidthFixed)
            setupColumn("Active", flags = ImGuiTableColumnFlags.WidthFixed)
            setupColumn("Time Left", flags = ImGuiTableColumnFlags.WidthFixed)
            setupColumn("Stacks", flags = ImGuiTableColumnFlags.WidthFixed)
            headersRow()

            val filteredData = if (UIState.buffsDebuffsSearchText.value.isEmpty()) {
                UIState.buffsDebuffsData
            } else {
                UIState.buffsDebuffsData.filter { entry ->
                    entry.name.contains(UIState.buffsDebuffsSearchText.value, ignoreCase = true) ||
                            entry.effect.name.contains(UIState.buffsDebuffsSearchText.value, ignoreCase = true)
                }
            }

            val sortedData = filteredData.sortedWith(
                compareByDescending<BuffDebuffEntry> { it.isActive }
                    .thenBy { it.isDebuff }
                    .thenBy { it.name }
            )

            sortedData.forEach { entry ->
                nextRow()
                nextColumn()

                val displayName = when {
                    !entry.isActive -> "[INACTIVE] ${entry.name}"
                    entry.isDebuff -> "[DEBUFF] ${entry.name}"
                    else -> "[BUFF] ${entry.name}"
                }
                text(displayName)

                nextColumn()
                text(if (entry.isDebuff) "Debuff" else "Buff")

                nextColumn()
                text(if (entry.isActive) "Yes" else "No")

                nextColumn()
                if (entry.isActive && entry.timeRemaining > 0) {
                    val minutes = entry.timeRemaining / 60000
                    val seconds = (entry.timeRemaining % 60000) / 1000
                    text("${minutes}:${seconds.toString().padStart(2, '0')}")
                } else if (entry.isActive) {
                    text("inf")
                } else {
                    text("--")
                }

                nextColumn()
                if (entry.stacks > 0) {
                    text(entry.stacks.toString())
                } else {
                    text("--")
                }
            }
        }

        if (UIState.buffsDebuffsData.isEmpty()) {
            text("No effects data available. Enable auto-refresh and wait a moment.")
        }
    }

    fun loadBuffsDebuffs() {
        try {
            UIState.buffsDebuffsData.clear()

            EffectRegistry.all.forEach { effect ->
                try {
                    val isActive = effect.active()
                    val timeRemaining = if (isActive) effect.timeRemainingMs() else 0L
                    val stacks = if (isActive) effect.stacks() else 0

                    if (isActive) {
                        UIState.buffsDebuffsData.add(
                            BuffDebuffEntry(
                                effect = effect,
                                name = effect.name.ifBlank { "struct ${effect.structId}" }.replace("_", " ").lowercase().split(" ").joinToString(" ") {
                                    it.replaceFirstChar { char -> char.uppercaseChar() }
                                },
                                isActive = isActive,
                                timeRemaining = timeRemaining,
                                stacks = stacks,
                                isDebuff = effect.isDebuff
                            )
                        )
                    }
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            println("Failed to load buffs/debuffs: ${e.message}")
        }
    }
}