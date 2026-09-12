package com.projectx.script.impl.trent

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.game.math.WorldToScreen
import com.projectx.game.nxt.entity.GroundItem
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.ui.backend.dsl.ImGuiDsl.window
import com.projectx.ui.backend.dsl.scopes.separator
import com.projectx.ui.backend.dsl.scopes.spacing
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.util.format
import com.projectx.util.formatElapsedTime
import com.projectx.util.getFormattedUnitsPerHour
import com.projectx.util.getFormattedXpPerHour
import com.projectx.util.random
import java.util.concurrent.ConcurrentHashMap

private const val TRAP_RANGE = 6
private const val LOOT_RANGE = 8

/** Laying a trap drops it on the floor as an item before its scene object appears; looting that
 *  would cancel the trap, so an item only counts as loot once it has outlived the window. */
private const val GROUND_ITEM_SETTLE_MILLIS = 5750L

private const val TOO_MANY_TRAPS_MESSAGE = "too many traps on the floor"

private enum class TrapKind(val trapItem: String, val objectNames: Set<String>) {
    MARASAMAW("Marasamaw plant", setOf("Marasamaw plant", "Wilted marasamaw plant", "Shaking marasamaw plant")),
    BOX("Box trap", setOf("Box trap", "Shaking box"))
}

private enum class TrapState(val label: String, val color: Int) {
    EMPTY("Empty", ImGuiColors.GRAY_LIGHTEST),
    SET("Set", ImGuiColors.GREEN_SEMI),
    CAUGHT("Caught", ImGuiColors.GREEN),
    FAILED("Failed", ImGuiColors.ORANGE),
    FALLEN("Fallen", ImGuiColors.RED)
}

private data class GroundItemKey(val id: Int, val tile: Tile)

private val trapItemNames = TrapKind.entries.mapTo(mutableSetOf()) { it.trapItem }

private val crossOffsets = listOf(
    0 to 0,
    0 to 1,
    0 to -1,
    1 to 0,
    -1 to 0
)

@ScriptDescription(
    name = "Chinchompa Hunter",
    version = "1.2.0",
    author = "Trent",
    description = "Replaces hunter traps and catches what is in them."
)
class ChinchompaHunting : Script() {
    var startTile: Tile = Tile.EMPTY

    private val groundItemFirstSeen = ConcurrentHashMap<GroundItemKey, Long>()
    private val trapStates = ConcurrentHashMap<Tile, TrapState>()
    private val trapStateSince = ConcurrentHashMap<Tile, Long>()
    private val resourcesGained = ConcurrentHashMap<String, Int>()

    private var lastInventoryCounts: Map<String, Int>? = null
    private var startXp = 0
    private var startTime = 0L

    @Volatile
    private var currentKind: TrapKind? = null

    @Volatile
    private var clearingFloor = false

    override fun onStart() {
        startTile = localPlayer.tile
        startXp = getXp(Skill.HUNTER)
        startTime = System.currentTimeMillis()
        groundItemFirstSeen.clear()
        trapStates.clear()
        trapStateSince.clear()
        resourcesGained.clear()
        lastInventoryCounts = null
        clearingFloor = false
    }

    override fun onEvent(event: Event) {
        super.onEvent(event)
        val chat = event as? Chat ?: return
        if (chat.message.contains(TOO_MANY_TRAPS_MESSAGE, ignoreCase = true)) clearingFloor = true
    }

    override suspend fun loop() {
        trackResources()

        val kind = activeTrapKind()
        currentKind = kind
        if (kind == null) return delay(1063, 850)

        refreshTrapStates(kind)

        if (clearingFloor) {
            if (lootGroundItems(kind, includeTraps = true)) return delay(2437, 4871)
            clearingFloor = false
        }

        if (lootGroundItems(kind, includeTraps = false)) return delay(2437, 4871)
        if (localPlayer.isAniMoving) return delay(883, 941)

        if (interactTrap(kind, "Check")) {
            delay(1187, 743)
            waitUntilNotAniMoving()
            placeTrap(kind, localPlayer.tile)
            return
        }

        val traps = nearbyTraps(kind)
        if (traps.size < getMaxTraps(getCurrentLevel(Skill.HUNTER))) {
            val occupiedTiles = traps.mapTo(mutableSetOf()) { it.tile }
            val availableTile = trapTiles().firstOrNull { it !in occupiedTiles } ?: return
            return placeTrap(kind, availableTile)
        }

        if (interactTrap(kind, "Rebuild")) {
            delay(1187, 743)
            waitUntilNotAniMoving()
        }
    }

    private fun trapTiles(): List<Tile> {
        if (startTile == Tile.EMPTY) return emptyList()
        return crossOffsets.take(getMaxTraps(getCurrentLevel(Skill.HUNTER)))
            .map { (x, y) -> startTile.transform(x, y) }
    }

    private fun activeTrapKind(): TrapKind? =
        TrapKind.entries.firstOrNull { inventory.hasItem(it.trapItem) }
            ?: TrapKind.entries.firstOrNull { nearbyTraps(it).isNotEmpty() }

    private fun nearbyTraps(kind: TrapKind): List<SceneObject> =
        getAllObjectsWithinRange(TRAP_RANGE).filter { it.name() in kind.objectNames }

    private fun interactTrap(kind: TrapKind, option: String): Boolean {
        val trap = findClosestReachableObject(TRAP_RANGE) {
            it.name() in kind.objectNames && it.hasOption(option)
        } ?: return false
        return trap.interact(option)
    }

    private fun fallenTrapAt(kind: TrapKind, tile: Tile): GroundItem? =
        groundItems.firstOrNull { it.name == kind.trapItem && it.tile.matches(tile) }

    private suspend fun placeTrap(kind: TrapKind, tile: Tile) {
        if (localPlayer.tile != tile) {
            walkTo(tile, false)
            waitUntilNotAniMoving()
        }
        val fallenTrap = fallenTrapAt(kind, tile)
        if (fallenTrap != null) fallenTrap.interact("Lay") else inventory.clickItem(kind.trapItem, "Lay")
        waitUntilNotAniMoving()
    }

    private fun refreshTrapStates(kind: TrapKind) {
        val now = System.currentTimeMillis()
        val tiles = trapTiles().toSet()
        trapStates.keys.retainAll(tiles)
        trapStateSince.keys.retainAll(tiles)
        tiles.forEach { tile ->
            val state = trapStateAt(kind, tile)
            if (trapStates.put(tile, state) != state) trapStateSince[tile] = now
        }
    }

    private fun trapStateAt(kind: TrapKind, tile: Tile): TrapState {
        if (fallenTrapAt(kind, tile) != null) return TrapState.FALLEN
        val trap = getAllObjectsWithinRange(tile, 1)
            .firstOrNull { it.tile.matches(tile) && it.name() in kind.objectNames } ?: return TrapState.EMPTY
        return when {
            trap.hasOption("Check") -> TrapState.CAUGHT
            trap.hasOption("Rebuild") -> TrapState.FAILED
            else -> TrapState.SET
        }
    }

    private suspend fun lootGroundItems(kind: TrapKind, includeTraps: Boolean): Boolean {
        val lootableIds = lootableGroundItemIds(kind, includeTraps)
        if (lootableIds.isEmpty()) return false
        if (!areaLootOpen) return openAreaLoot()

        var looted = false
        for (item in areaLoot.filter { it.id in lootableIds }) {
            if (item.click(1)) {
                looted = true
                delay(random(287, 461))
            }
        }
        return looted
    }

    /**
     * A fallen trap is re-laid in place rather than looted, so it is only lootable while clearing
     * the floor. An id stays blocked until every one of its ground copies has settled, because
     * area loot cannot be aimed at a single tile.
     */
    private fun lootableGroundItemIds(kind: TrapKind, includeTraps: Boolean): Set<Int> {
        val (settled, pending) = trackGroundItems()
            .filter { includeTraps || it.name != kind.trapItem }
            .partition { millisOnGround(it) >= GROUND_ITEM_SETTLE_MILLIS }

        val pendingIds = pending.mapTo(mutableSetOf()) { it.id }
        return settled.filter { canLoot(it) }.mapTo(mutableSetOf()) { it.id } - pendingIds
    }

    private fun trackGroundItems(): List<GroundItem> {
        val now = System.currentTimeMillis()
        val nearby = groundItems.filter { it.tile.withinDistance(startTile, LOOT_RANGE) }
        groundItemFirstSeen.keys.retainAll(nearby.mapTo(mutableSetOf()) { GroundItemKey(it.id, it.tile) })
        nearby.forEach { groundItemFirstSeen.putIfAbsent(GroundItemKey(it.id, it.tile), now) }
        return nearby
    }

    private fun millisOnGround(item: GroundItem): Long {
        val firstSeen = groundItemFirstSeen[GroundItemKey(item.id, item.tile)] ?: return 0L
        return System.currentTimeMillis() - firstSeen
    }

    private fun canLoot(item: GroundItem): Boolean {
        if (!inventory.isFull) return true
        return item.getDef().isStackable() && inventory.hasItem(item.id)
    }

    private fun trackResources() {
        val counts = inventoryCounts()
        lastInventoryCounts?.let { previous ->
            counts.forEach { (name, amount) ->
                val gained = amount - (previous[name] ?: 0)
                if (gained > 0) resourcesGained.merge(name, gained, Int::plus)
            }
        }
        lastInventoryCounts = counts
    }

    private fun inventoryCounts(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        inventory.forEach { item ->
            if (item.name !in trapItemNames) counts.merge(item.name, item.amount, Int::plus)
        }
        return counts
    }

    private fun getMaxTraps(level: Int): Int = when {
        level < 30 -> 2
        level < 60 -> 3
        level < 80 -> 4
        else -> 5
    }

    override fun render() {
        if (startTime == 0L) return
        val kind = currentKind
        val now = System.currentTimeMillis()

        window("Chinchompa Hunter") {
            text("Trap: ${kind?.trapItem ?: "none detected"}")
            text("Hunter level: ${getCurrentLevel(Skill.HUNTER)}")
            text("Runtime: ${formatElapsedTime(now, startTime)}")
            separator()
            text("XP gained: ${format(getXp(Skill.HUNTER) - startXp)}")
            text("XP/hr: ${getFormattedXpPerHour(startXp, getXp(Skill.HUNTER), startTime)}")
            spacing()
            text("Resources:")
            if (resourcesGained.isEmpty()) text("  none yet")
            resourcesGained.entries.sortedByDescending { it.value }.forEach { (name, count) ->
                text("  $name: ${format(count)} (${getFormattedUnitsPerHour(count, startTime)}/hr)")
            }
            if (clearingFloor) {
                spacing()
                text("Clearing floor - too many traps")
            }
        }

        if (kind == null) return
        backgroundDrawList {
            val plane = localPlayer.graphNode.tileFine.z
            val tiles = trapTiles()
            tiles.forEach { tile ->
                val state = trapStates[tile] ?: return@forEach
                val screenPos = WorldToScreen.getEstimatedTileCenter(tile, plane) ?: return@forEach
                text(screenPos.transform(0f, -12f), ImGuiColors.WHITE, formatElapsedTime(now, trapStateSince[tile] ?: now))
                text(screenPos, state.color, state.label)
            }
            val trapTileSet = tiles.toSet()
            groundItems.filter {
                it.name == kind.trapItem && it.tile !in trapTileSet && it.tile.withinDistance(startTile, LOOT_RANGE)
            }.forEach { fallen ->
                val screenPos = WorldToScreen.getEstimatedTileCenter(fallen.tile, plane) ?: return@forEach
                text(screenPos, TrapState.FALLEN.color, TrapState.FALLEN.label)
            }
        }
    }
}
