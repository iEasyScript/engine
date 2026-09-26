package com.projectx.ui.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.farming.FarmingTracker
import com.projectx.game.farming.PatchCategory
import com.projectx.game.invention.InventionComponentTracker
import com.projectx.game.invention.InventionXpTracker
import com.projectx.ui.InventoryEntry
import com.projectx.ui.InventoryType
import com.projectx.ui.UIState
import com.projectx.ui.compose.Feed
import com.projectx.ui.compose.GameThread
import com.projectx.ui.compose.components.ActionButton
import com.projectx.ui.compose.components.DataTable
import com.projectx.ui.compose.components.Dropdown
import com.projectx.ui.compose.components.EmptyState
import com.projectx.ui.compose.components.Hint
import com.projectx.ui.compose.components.NumberInput
import com.projectx.ui.compose.components.Pill
import com.projectx.ui.compose.components.ScreenScroll
import com.projectx.ui.compose.components.SearchField
import com.projectx.ui.compose.components.Section
import com.projectx.ui.compose.components.SettingRow
import com.projectx.ui.compose.components.Stat
import com.projectx.ui.compose.components.TableColumn
import com.projectx.ui.compose.components.ToggleRow
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.Palette
import com.projectx.util.format
import com.projectx.util.getFormattedUnitsPerHour
import org.projectx.core.game.combat.EffectRegistry
import org.projectx.core.game.skill.Skill
import world.gregs.voidps.gameval.Gameval

// ---------- XP ----------

data class XpRow(val skill: Skill, val gained: Int, val perHour: String)

object XpModel {
    val feed = Feed(500) {
        UIState.xpData.toList().map { (skill, data) -> XpRow(skill, data.second, getFormattedUnitsPerHour(data.second, data.first)) }
    }

    fun reset(skill: Skill) = GameThread.post { UIState.xpData[skill] = System.currentTimeMillis() to 0 }
    fun remove(skill: Skill) = GameThread.post { UIState.xpData.remove(skill) }
}

@Composable
fun XpScreen() {
    val rows = XpModel.feed.value.orEmpty()
    ScreenScroll {
        Section("Experience this session", note = "Every skill that has gained experience since the engine loaded.") {
            if (rows.isEmpty()) {
                EmptyState("No experience yet", "Skills show up here as soon as they gain experience.")
            } else {
                DataTable(
                    listOf(TableColumn("Skill", 1.2f), TableColumn("Gained", mono = true), TableColumn("Per hour", mono = true), TableColumn("", width = 200.dp)),
                    rows,
                    key = { it.skill },
                ) { row ->
                    text(row.skill.name.lowercase().replaceFirstChar { it.uppercase() })
                    text(format(row.gained))
                    text(row.perHour, Palette.running)
                    cell {
                        ActionButton("Reset", { XpModel.reset(row.skill) }, height = 28.dp)
                        ActionButton("Remove", { XpModel.remove(row.skill) }, height = 28.dp)
                    }
                }
            }
        }
    }
}

// ---------- Inventory ----------

data class InventorySnapshot(val exists: Boolean, val items: List<InventoryEntry>)

object InventoryModel {
    val feed = Feed(250) {
        val manager = Bootstrap.client.inventoryManager
        val id = UIState.inventoryId.value
        if (!manager.exists(id)) return@Feed InventorySnapshot(false, emptyList())
        val inventory = manager[id]
        InventorySnapshot(true, if (inventory.isEmpty) emptyList() else inventory.map { InventoryEntry(it.slot.slotId, it.id, it.name, it.amount) })
    }

    fun select(id: Int) {
        UIState.inventoryId.value = id
        feed.invalidate()
    }

    fun matchingContainers(query: String): List<Pair<Int, String>> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return Gameval.entries(Gameval.INV).asSequence()
            .filter { it.value.contains(q, ignoreCase = true) || it.key.toString() == q }
            .sortedBy { it.key }
            .take(40)
            .map { it.key to it.value }
            .toList()
    }
}

@Composable
fun InventoryScreen() {
    val snapshot = InventoryModel.feed.value
    val id = UIState.inventoryId.value
    val preset = InventoryType.entries.firstOrNull { it.id == id }
    val query = UIState.inventorySearchText.value
    val items = snapshot?.items.orEmpty().filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.itemId.toString() == query.trim() }

    ScreenScroll {
        Section("Container") {
            SettingRow("Preset") {
                Dropdown(preset, listOf(null) + InventoryType.entries, { it?.let { t -> InventoryModel.select(t.id) } }, width = 200.dp) {
                    it?.displayName ?: "Custom"
                }
            }
            SettingRow("Container id", Gameval.inv(id) ?: "Not a known inventory") {
                NumberInput(id, { InventoryModel.select(it) }, 0..65535, width = 150.dp)
            }
            SettingRow("Find a container", "Search every inventory the game defines, by name or id.") {
                SearchField(UIState.inventoryNameSearch, "e.g. bank, shop", 220.dp)
            }
            val matches = InventoryModel.matchingContainers(UIState.inventoryNameSearch.value)
            if (matches.isNotEmpty()) {
                DataTable(listOf(TableColumn("Inventory", 2f), TableColumn("Id", mono = true), TableColumn("", width = 90.dp)), matches) { (matchId, name) ->
                    text(name)
                    text(matchId.toString())
                    cell { ActionButton("Show", { InventoryModel.select(matchId) }, height = 28.dp) }
                }
            }
        }
        Section(
            "Items",
            note = if (snapshot?.exists == false) "The client does not hold this container right now." else "${items.size} stack(s)",
            actions = { SearchField(UIState.inventorySearchText, "Filter items", 200.dp) },
        ) {
            DataTable(
                listOf(TableColumn("Slot", width = 60.dp, mono = true), TableColumn("Id", width = 90.dp, mono = true), TableColumn("Name", 2f), TableColumn("Amount", mono = true)),
                items,
                emptyText = if (snapshot?.exists == false) "Open or load this container in game and it will appear." else "Empty.",
            ) { item ->
                text(item.slot.toString())
                text(item.itemId.toString())
                text(item.name)
                text(format(item.amount))
            }
        }
    }
}

// ---------- Effects ----------

data class EffectRow(val structId: Int, val name: String, val debuff: Boolean, val timeRemainingMs: Long, val stacks: Int)

object EffectsModel {
    val feed = Feed(500) {
        EffectRegistry.all.mapNotNull { effect ->
            runCatching {
                if (!effect.active()) return@runCatching null
                EffectRow(
                    structId = effect.structId,
                    name = effect.name.ifBlank { "struct ${effect.structId}" }.replace('_', ' ').lowercase().split(' ')
                        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } },
                    debuff = effect.isDebuff,
                    timeRemainingMs = effect.timeRemainingMs(),
                    stacks = effect.stacks(),
                )
            }.getOrNull()
        }.sortedWith(compareBy<EffectRow> { it.debuff }.thenBy { it.name })
    }
}

@Composable
fun EffectsScreen() {
    val query = UIState.buffsDebuffsSearchText.value
    val rows = EffectsModel.feed.value.orEmpty()
        .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.structId.toString().contains(query) }
    ScreenScroll {
        Section(
            "Active effects",
            note = "Buffs and debuffs on your character right now. Scripts read these with effectActive(\"name\") or the struct id.",
            actions = { SearchField(UIState.buffsDebuffsSearchText, "Filter by name or id", 200.dp) },
        ) {
            DataTable(
                listOf(TableColumn("Effect", 2f), TableColumn("Id", width = 90.dp, mono = true), TableColumn("Kind"), TableColumn("Time left", mono = true), TableColumn("Stacks", mono = true)),
                rows,
                emptyText = "No buffs or debuffs are active.",
            ) { row ->
                text(row.name)
                text(row.structId.toString())
                cell { Pill(if (row.debuff) "Debuff" else "Buff", if (row.debuff) Palette.stop else Palette.running) }
                text(timeLeft(row.timeRemainingMs))
                text(if (row.stacks > 0) row.stacks.toString() else "-")
            }
        }
    }
}

private fun timeLeft(ms: Long): String =
    if (ms <= 0) "no limit" else "%d:%02d".format(ms / 60_000, ms % 60_000 / 1000)

// ---------- Invention ----------

data class InventionSnapshot(
    val siphonLevel: Int,
    val inventionLevel: Int,
    val items: List<InventionXpTracker.Row>,
    val totalInvXpPerHour: Int,
    val totalItemXpPerHour: Int,
    val components: List<InventionComponentTracker.Row>,
    val componentsPerHour: Int,
)

object InventionModel {
    val feed = Feed(1000) {
        InventionSnapshot(
            InventionXpTracker.assumedSiphonLevel,
            InventionXpTracker.inventionLevel,
            InventionXpTracker.rows.toList(),
            InventionXpTracker.totalEffectiveInvXpPerHour,
            InventionXpTracker.totalItemXpPerHour,
            InventionComponentTracker.rows.toList(),
            InventionComponentTracker.totalPerHour,
        )
    }
}

@Composable
fun InventionScreen() {
    val data = InventionModel.feed.value
    ScreenScroll {
        Section(
            "Augmented item XP",
            note = "Estimates Invention XP per hour from the experience your augmented items gain.",
            actions = { if (UIState.inventionXpTrackerEnabled.value) ActionButton("Reset", { GameThread.post { InventionXpTracker.reset() } }, height = 30.dp) },
        ) {
            ToggleRow("Track augmented items", UIState.inventionXpTrackerEnabled)
            if (UIState.inventionXpTrackerEnabled.value && data != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("Invention XP / hr", format(data.totalInvXpPerHour))
                    Stat("Item XP / hr", format(data.totalItemXpPerHour))
                    Stat("Assumed siphon", "Level ${data.siphonLevel}")
                }
                DataTable(
                    listOf(TableColumn("Item", 2f), TableColumn("Tier", width = 56.dp, mono = true), TableColumn("Level", width = 60.dp, mono = true), TableColumn("Item XP", mono = true), TableColumn("Item XP/hr", mono = true), TableColumn("Inv XP/hr", mono = true)),
                    data.items,
                    emptyText = "No augmented items are gaining experience. Train with augmented gear equipped or carried.",
                ) { row ->
                    text(row.name)
                    text(if (row.tierKnown) "${row.tier}" else "${row.tier}?")
                    text("${row.itemLevel}")
                    text(format(row.itemXp))
                    text(format(row.itemXpPerHour))
                    text(format(row.effectiveInvXpPerHour), Palette.running)
                }
            }
        }
        Section(
            "Components",
            note = "Materials gained per hour from disassembly.",
            actions = { if (UIState.inventionComponentTrackerEnabled.value) ActionButton("Reset", { GameThread.post { InventionComponentTracker.reset() } }, height = 30.dp) },
        ) {
            ToggleRow("Track components", UIState.inventionComponentTrackerEnabled)
            if (UIState.inventionComponentTrackerEnabled.value && data != null) {
                Stat("Components / hr", format(data.componentsPerHour))
                DataTable(
                    listOf(TableColumn("Component", 2f), TableColumn("Held", mono = true), TableColumn("Gained", mono = true), TableColumn("Per hour", mono = true)),
                    data.components,
                    emptyText = "Nothing gained yet. Disassemble items to start.",
                ) { row ->
                    text(row.name)
                    text(format(row.count))
                    text(format(row.gained))
                    text(format(row.perHour), Palette.running)
                }
            }
        }
    }
}

// ---------- Farming ----------

object FarmingModel {
    val feed = Feed(1000) { FarmingTracker.rows.toList() }
}

@Composable
fun FarmingScreen() {
    val rows = FarmingModel.feed.value.orEmpty()
    val byCategory = rows.groupBy { it.category }
    ScreenScroll {
        Section("Tracking", note = "Watches herb, flower, cactus, bush and fruit tree patches.") {
            ToggleRow("Track patches", UIState.farmingTrackerEnabled)
            if (UIState.farmingTrackerEnabled.value) {
                ToggleRow("Desktop notifications", UIState.farmingNotificationsEnabled, "Tell me when a patch is ready or needs attention.")
                ToggleRow("Show other patches", UIState.farmingShowSecondary)
            }
        }
        if (UIState.farmingTrackerEnabled.value) {
            PatchCategory.entries
                .filter { it.primary || UIState.farmingShowSecondary.value }
                .forEach { category ->
                    val patches = byCategory[category].orEmpty()
                    Section(category.plural) {
                        if (patches.isEmpty()) {
                            Hint("Waiting for data. Log in near these patches or visit them.")
                        } else {
                            DataTable(listOf(TableColumn("Patch", 1.4f), TableColumn("Produce"), TableColumn("Status", 1.4f)), patches) { row ->
                                text(row.name)
                                text(row.produce.ifBlank { "-" })
                                text(row.statusLabel, statusColor(row.status))
                            }
                        }
                    }
                }
        }
    }
}

private fun statusColor(status: FarmingTracker.Status) = when (status) {
    FarmingTracker.Status.GROWN, FarmingTracker.Status.PRODUCE -> Palette.running
    FarmingTracker.Status.DISEASED, FarmingTracker.Status.DEAD, FarmingTracker.Status.WEEDS -> Palette.stop
    FarmingTracker.Status.EMPTY, FarmingTracker.Status.HARVESTED, FarmingTracker.Status.UNKNOWN -> Palette.muted
    FarmingTracker.Status.GROWING -> Palette.text
}
