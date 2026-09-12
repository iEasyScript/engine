package com.projectx.script.impl.bp.combat

import com.projectx.game.items.Item
import com.projectx.game.nxt.entity.GroundItem
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigurableScript
import com.projectx.script.Script
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.StringConfigItem
import com.projectx.script.api.areaLoot
import com.projectx.script.api.areaLootOpen
import com.projectx.script.api.groundItems
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.openAreaLoot
import com.projectx.util.random

@ScriptDescription(
    name = "Looting",
    version = "1.0.0",
    author = "BP",
    description = "Loots whitelisted ground items directly or through the area loot interface.",
    category = ScriptCategory.COMBAT
)
class Looting : Script(), ConfigurableScript {
    private val whitelistInput = StringConfigItem(
        name = "Item whitelist",
        description = "Comma, semicolon, or newline separated fragments to match (case-insensitive).",
        initialValue = ""
    )

    private val autoOpenAreaLoot = BooleanConfigItem(
        name = "Use area loot",
        description = "Attempt to open the area loot interface when matching items are nearby.",
        initialValue = true
    )

    private var cachedWhitelistRaw = ""
    private var cachedWhitelist = emptyList<String>()

    override suspend fun loop() {
        val fragments = whitelistFragments()
        if (fragments.isEmpty()) {
            delay(600)
            return
        }

        if (areaLootOpen) {
            if (lootFromAreaLoot(fragments)) {
                delay(random(350, 550))
                return
            }
        } else if (autoOpenAreaLoot.value) {
            val matchingGroundItems = snapshotGroundItems().filter { groundItem ->
                isWhitelisted(groundItem.name, fragments) && canLoot(groundItem)
            }
            if (matchingGroundItems.isNotEmpty() && openAreaLoot()) {
                delay(random(350, 550))
                return
            }
        }

        if (!localPlayer.isMoving && lootGroundItem(fragments)) {
            waitThenDelayWhile(800, 3000) {localPlayer.isMoving}
            delay(random(450, 650))
            return
        }

        delay(random(300, 450))
    }

    private fun whitelistFragments(): List<String> {
        val raw = whitelistInput.value
        if (raw == cachedWhitelistRaw) {
            return cachedWhitelist
        }

        cachedWhitelistRaw = raw
        cachedWhitelist = raw
            .split(',', ';', '\n')
            .mapNotNull { fragment ->
                val cleaned = fragment.trim().lowercase()
                cleaned.takeIf { it.isNotEmpty() }
            }
        return cachedWhitelist
    }

    private fun isWhitelisted(name: String, fragments: List<String>): Boolean {
        if (fragments.isEmpty()) return false
        return fragments.any { name.lowercase().contains(it) }
    }

    private fun canLoot(item: Item): Boolean {
        if (!inventory.isFull) return true
        val definition = item.getDef()
        return definition.isStackable() && inventory.hasItem(item.id)
    }

    private fun canLoot(item: GroundItem): Boolean {
        if (!inventory.isFull) return true
        val definition = item.getDef()
        return definition.isStackable() && inventory.hasItem(item.id)
    }

    private suspend fun lootFromAreaLoot(fragments: List<String>): Boolean {
        val targets = areaLoot.filter { item ->
            isWhitelisted(item.name, fragments) && canLoot(item)
        }
        var looted = false
        for (item in targets) {
            if (item.click(1)) {
                looted = true
                delay(random(300, 450))
            }
        }
        return looted
    }

    private fun lootGroundItem(fragments: List<String>): Boolean {
        val target = snapshotGroundItems()
            .asSequence()
            .filter { groundItem -> isWhitelisted(groundItem.name, fragments) && canLoot(groundItem) }
            .minByOrNull { groundItem -> groundItem.tile.getDistance(localPlayer.tile) }
            ?: return false

        return target.interact("Take")
    }

    private fun snapshotGroundItems(): List<GroundItem> = try {
        groundItems.toList()
    } catch (_: Exception) {
        emptyList()
    }
}
