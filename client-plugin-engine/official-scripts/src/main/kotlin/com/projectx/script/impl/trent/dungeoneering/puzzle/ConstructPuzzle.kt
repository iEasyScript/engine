package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import com.projectx.util.gaussian
import world.gregs.voidps.type.Tile

/**
 * The Daemonheim "Damaged construct" puzzle: a broken golem blocks the room's doors until it is rebuilt and
 * charged. The MISSING part is encoded in the NPC id (broken arm/head/leg), so we don't need to examine it -
 * we Take a lump of stone from the Crate of magic rocks, Carve the matching part, Imbue it, Repair the
 * construct with the finished part, then Charge the now-dormant construct, which opens the doors. Fully
 * self-driven from inventory + scene state, same room-isolation as the other puzzles/bosses.
 */
object ConstructPuzzle {
    private const val DAMAGED_ARM = 11002
    private const val DAMAGED_HEAD = 11003
    private const val DAMAGED_LEG = 11004
    private const val DORMANT = 11005

    private const val LUMP = 17364

    private val CRATE_IDS = setOf(49543, 49544, 49545) // rand_bc_crate_<frozen|abandoned|furnished>

    private const val RANGE = 16
    private const val ROOM_TILES = 16

    private data class Part(val carveOption: String, val unfinished: Int, val finished: Int)

    private fun partFor(constructId: Int): Part? = when (constructId) {
        DAMAGED_ARM -> Part("Carve arm", 17365, 17368)
        DAMAGED_LEG -> Part("Carve leg", 17366, 17370)
        DAMAGED_HEAD -> Part("Carve head", 17367, 17372)
        else -> null
    }

    fun present(): Boolean {
        val room = roomOf(localPlayer.tile)
        return damaged(room) != null || dormant(room) != null
    }

    suspend fun solve(script: Script) {
        val room = roomOf(localPlayer.tile)

        dormant(room)?.let { construct ->
            approach(script, construct.tile)
            if (construct.interact("Charge")) {
                script.waitUntilNotMoving()
                script.delayUntil(gaussian(2200L, 500L)) { dormant(room) == null }
            }
            script.delay(640, 170)
            println("DUNG-CONSTRUCT: charged the construct - doors opening")
            return
        }

        val construct = damaged(room) ?: run { script.delay(300, 80); return }
        val part = partFor(construct.id) ?: run { script.delay(300, 80); return }

        if (inventory.hasItem(part.finished)) {
            approach(script, construct.tile)
            if (construct.interact("Repair")) {
                script.waitUntilNotMoving()
                script.delayUntil(gaussian(2200L, 500L)) { !inventory.hasItem(part.finished) }
            }
            script.delay(640, 170)
            println("DUNG-CONSTRUCT: repaired with finished part ${part.finished}")
            return
        }

        inventory.firstOrNull { it.id == part.unfinished }?.let { unfinished ->
            if (unfinished.click("Imbue"))
                script.delayUntil(gaussian(1600L, 400L)) { inventory.hasItem(part.finished) }
            println("DUNG-CONSTRUCT: imbued the ${part.carveOption.removePrefix("Carve ")}")
            return
        }

        inventory.firstOrNull { it.id == LUMP }?.let { lump ->
            if (lump.click(part.carveOption))
                script.delayUntil(gaussian(1600L, 400L)) { inventory.hasItem(part.unfinished) }
            println("DUNG-CONSTRUCT: ${part.carveOption}")
            return
        }

        val crate = crate(room) ?: run {
            println("DUNG-CONSTRUCT: no crate of magic rocks in room")
            script.delay(400, 110)
            return
        }
        approach(script, crate.tile)
        if (crate.interact("Take")) {
            script.waitUntilNotMoving()
            script.delayUntil(gaussian(2000L, 500L)) { inventory.hasItem(LUMP) }
        }
        script.delay(640, 170)
    }

    private fun damaged(room: Pair<Int, Int>): NPC? =
        allNpcsWithinRange(RANGE) { it.id == DAMAGED_ARM || it.id == DAMAGED_HEAD || it.id == DAMAGED_LEG }
            .filter { roomOf(it.tile) == room }
            .minByOrNull { it.tile.getDistance(localPlayer.tile) }

    private fun dormant(room: Pair<Int, Int>): NPC? =
        allNpcsWithinRange(RANGE) { it.id == DORMANT }
            .filter { roomOf(it.tile) == room }
            .minByOrNull { it.tile.getDistance(localPlayer.tile) }

    private fun crate(room: Pair<Int, Int>): SceneObject? =
        getAllObjectsWithinRange(RANGE).firstOrNull { it.id in CRATE_IDS && roomOf(it.tile) == room }

    private suspend fun approach(script: Script, tile: Tile) {
        if (tile.getDistance(localPlayer.tile) <= 2) return
        walkTo(tile, false)
        script.waitUntilNotMoving()
    }

    private fun roomOf(tile: Tile): Pair<Int, Int> = tile.x / ROOM_TILES to tile.y / ROOM_TILES
}
