package com.projectx.script.impl.bp.hunter.box

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.game.math.WorldToScreen
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.ScriptCategory
import com.projectx.script.api.*
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.ui.backend.dsl.ImGuiDsl.window
import com.projectx.ui.backend.dsl.scopes.separator
import com.projectx.ui.backend.dsl.scopes.spacing
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.utils.ImGuiColors

private enum class TrapState(val itemName: String, val actionName: String?, val objectIds: Set<Int>, val drawColor: Int) {
    WAITING("Box trap", null, setOf(19187), ImGuiColors.YELLOW),
    DROPPED("Box trap", "Lay", setOf(10008), ImGuiColors.RED),
    EMPTY("Box tap", "Lay", setOf(10008), ImGuiColors.BLACK),
    CATCHING("Box trap", null, setOf(19188, 19197, 19198, 19199, 19200), ImGuiColors.GREEN_SEMI),
    CAUGHT("Shaking box", "Check", setOf(19190), ImGuiColors.GREEN),
    FAILED("Box trap", "Rebuild", setOf(19192), ImGuiColors.ORANGE);

    companion object {
        private val lookup: Map<Int, TrapState> = entries.flatMap { state ->
            state.objectIds.map { id -> id to state }
        }.toMap()

        @JvmStatic
        fun fromInteractId(interactId: Int): TrapState? = lookup[interactId]
    }
}


private val crossOffsets = listOf(
    -1 to 0,
    0 to 0,
    1 to 0,
    0 to -1,
    0 to 1,
)

@ScriptDescription(
    name = "Box Trapping",
    version = "1.0.0",
    author = "BP",
    description = "Automated box trapping script for hunter training.",
    category = ScriptCategory.HUNTER
)
class BoxTrapping : Script() {
    private var startTile: Tile = Tile.EMPTY
    private val trapTiles: Set<Tile>
        get() {
            if (startTile == Tile.EMPTY) {
                return emptySet()
            }
            return crossOffsets.take(getMaxTraps(getCurrentLevel(Skill.HUNTER))).map { (x, y) ->
                startTile.transform(x, y)
            }.toSet()
        }

    private var trapsPlaced = 0
    private var animalsCaught = 0
    private val trapStateHistory = mutableMapOf<Tile, Pair<TrapState, Long>>()

    private val tileStates: Map<Tile, TrapState>
        get() {
            return trapTiles.associateWith { tile -> getTrapState(tile) }
        }


    private fun getTrapState(tile: Tile): TrapState {
        val currentState = if (groundItems.any { item -> item.tile.matches(tile) && TrapState.DROPPED.objectIds.contains(item.id) }) {
            TrapState.DROPPED
        } else {
            getAllObjectsWithinRange(tile, 1).filter { obj ->
                obj.tile.matches(tile)
            }.firstNotNullOfOrNull { obj -> TrapState.fromInteractId(obj.id) } ?: TrapState.EMPTY
        }
        
        val previousEntry = trapStateHistory[tile]
        if (previousEntry == null || previousEntry.first != currentState) {
            trapStateHistory[tile] = currentState to System.currentTimeMillis()
        }
        
        return currentState
    }

    private fun getTimeSinceStateChange(tile: Tile): Long {
        val entry = trapStateHistory[tile] ?: return 0L
        return System.currentTimeMillis() - entry.second
    }

    private fun formatTime(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        return if (seconds < 60) {
            "${seconds}s"
        } else {
            val minutes = seconds / 60
            val remainingSeconds = seconds % 60
            "${minutes}m ${remainingSeconds}s"
        }
    }

    override fun onStart() {
        startTile = localPlayer.tile
    }

    override suspend fun loop() {
//        if (!areaLoot.isEmpty) {
//            lootAllAreaLoot()
//            return delay(2500, 5000)
//        }

        if (localPlayer.isAniMoving) return delay(850, 1000)

        val actionableTiles = tileStates.filter { (_, state) ->
            state != TrapState.WAITING && state != TrapState.CATCHING
        }.toList().sortedByDescending { (tile, _) -> tile.x }

        val actionTile = actionableTiles.firstOrNull() ?: return delay(1000)
        val (tile, state) = actionTile
        val sceneObject = findClosestObjectToTile(tile) { obj -> state.objectIds.contains(obj.id) }

        when (state) {
            TrapState.EMPTY -> {
                if (inventory.hasItem("Box trap")) {
                    placeTrap(tile)
                }
            }

            TrapState.CAUGHT -> {
                if (sceneObject?.interact(state.actionName!!) ?: false) {
                    animalsCaught++
                    waitUntilNotAniMoving()
                    placeTrap(tile)
                } else {
                    println("[ERROR] could not match tile to sceneObject, skipping")
                }
            }

            TrapState.FAILED -> {

                if (sceneObject?.interact(state.actionName!!) ?: false) {
                    waitUntilNotAniMoving()
                } else {
                    println("[ERROR] failed to Rebuild trap, skipping")
                }
            }

            TrapState.DROPPED -> {
                val groundItem = groundItems.find { it.tile.matches(tile) && state.objectIds.contains(it.id) }
                if (groundItem?.interact(state.actionName!!)?: false) {
                    waitUntilNotAniMoving()
                } else {
                    println("[ERROR] failed to re-lay collapsed trap")
                }

            }

            else -> {} // WAITING/CATCHING already filtered out
        }
        delay(600, 1200)
    }

    private suspend fun placeTrap(tile: Tile) {
        if (localPlayer.tile != tile) {
            walkTo(tile, false)
            waitUntilNotAniMoving()
        }
        inventory.clickItem("Box trap", "Lay")
        waitUntilNotAniMoving()
        trapsPlaced++
    }

    private fun getMaxTraps(level: Int): Int = when {
        level < 30 -> 2
        level < 60 -> 3
        level < 80 -> 4
        else -> 5
    }

    override fun render() {
        window("Box Trapping Stats") {
            text("Script: Box Trapping")
            separator()
            text("Hunter Level: ${getCurrentLevel(Skill.HUNTER)}")
            text("Active Tiles: ${trapTiles.size}")
            spacing()
            text("Statistics:")
            text("Traps Placed: $trapsPlaced")
            text("Animals Caught: $animalsCaught")
        }
        backgroundDrawList {
            trapTiles.forEach { tile ->
                val state = getTrapState(tile)
                val screenPos = WorldToScreen.getEstimatedTileCenter(tile, localPlayer.graphNode.tileFine.z)
                if (screenPos != null) {
                    val timeSinceChange = getTimeSinceStateChange(tile)
                    val timeText = formatTime(timeSinceChange)
                    
                    // Draw time above state text (assuming 10px font height)
                    text(screenPos.transform(0f, -12f), ImGuiColors.WHITE, timeText)
                    text(screenPos, state.drawColor, state.name)
                }
            }
        }
    }

}