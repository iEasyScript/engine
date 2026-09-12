package com.projectx.ui.tabs

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.ui.InventoryEntry
import com.projectx.ui.InventoryType
import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.ui.backend.dsl.utils.ImGuiTableColumnFlags
import com.projectx.ui.backend.dsl.utils.ImGuiTableFlags
import world.gregs.voidps.gameval.Gameval

object InventoryTab {
    fun ChildScope.render() {

        val types = InventoryType.entries
        val current = types.firstOrNull { it.id == UIState.inventoryId.value } ?: InventoryType.BACKPACK

        section("Container")
        properties("inventory-container") {
            row("Preset") {
                combo("##invPreset", current.displayName) {
                    types.forEach { t ->
                        selectable(t.displayName, t.id == UIState.inventoryId.value) {
                            UIState.inventoryId.value = t.id
                        }
                    }
                }
            }
            row("Container ID") {
                inputInt("##invid", UIState.inventoryId.value) { newVal ->
                    UIState.inventoryId.value = newVal
                }
            }
            row("Resolves to") {
                text(Gameval.inv(UIState.inventoryId.value)?.let { "($it)" } ?: "(unknown inv)")
            }
            row("Find container") { inputText("##invFind", UIState.inventoryNameSearch) }
        }

        val invQuery = UIState.inventoryNameSearch.value.trim()
        if (invQuery.isNotEmpty()) {
            val matches = Gameval.entries(Gameval.INV).asSequence()
                .filter { it.value.contains(invQuery, ignoreCase = true) || it.key.toString() == invQuery }
                .sortedBy { it.key }
                .take(40)
                .toList()
            child("invMatches", height = 130f) {
                if (matches.isEmpty()) {
                    text("No inventories match")
                } else {
                    matches.forEach { (id, name) ->
                        selectable("$name ($id)", id == UIState.inventoryId.value) {
                            UIState.inventoryId.value = id
                            loadInventory(id)
                        }
                    }
                }
            }
        }

        section("Items")
        properties("inventory-items") {
            row("Auto refresh") { checkbox("##invAutoRefresh", UIState.inventoryEnabled) }
            row("Item search") { inputText("##invItemSearch", UIState.inventorySearchText) }
        }
        button("Load") {
            loadInventory(UIState.inventoryId.value)
        }
        sameLine()
        button("Clear") {
            UIState.inventoryData.clear()
        }

        table(id = "InventoryTable", columns = 4, flags = ImGuiTableFlags.SizingStretchSame or ImGuiTableFlags.BordersInnerH) {
            setupColumn("Slot", ImGuiTableColumnFlags.WidthFixed)
            setupColumn("ID", ImGuiTableColumnFlags.WidthFixed)
            setupColumn("Name" )
            setupColumn("Amount", ImGuiTableColumnFlags.WidthFixed)
            headersRow()

            val filteredData = if (UIState.inventorySearchText.value.isEmpty()) {
                UIState.inventoryData
            } else {
                UIState.inventoryData.filter { item ->
                    item.name.contains(UIState.inventorySearchText.value, ignoreCase = true)
                }
            }

            filteredData.forEach { item ->
                nextRow()
                nextColumn()
                text(item.slot.toString())
                nextColumn()
                text(item.itemId.toString())
                nextColumn()
                text(item.name)
                nextColumn()
                text(item.amount.toString())
            }
        }
    }

    fun loadInventory(inventoryId: Int) {
        try {
            UIState.inventoryData.clear()

            if (!Bootstrap.client.inventoryManager.exists(inventoryId)) {
                return
            }

            val inventory = Bootstrap.client.inventoryManager[inventoryId]

            if (inventory.isEmpty) {
                return
            }

            inventory.forEachIndexed { index, item ->
                UIState.inventoryData.add(
                    InventoryEntry(
                        slot = item.slot.slotId,
                        itemId = item.id,
                        name = item.name,
                        amount = item.amount
                    )
                )
            }
        } catch (e: Exception) {
            println("Failed to load inventory: ${e.message}")
        }
    }
}