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
import com.projectx.script.impl.trent.dungeoneering.DungeonSession
import com.projectx.util.gaussian
import world.gregs.voidps.type.Tile
import kotlin.math.abs

/**
 * The Daemonheim "critter key" (ferret) puzzle: a Ferret carries the key that unlocks the room's door and
 * bolts between holes, teleporting across the room. Catching it barehanded is random and useless for a bot,
 * so we TRAP it: chop the dry branches -> make a trap -> LAY it on the floor tile diagonally INSIDE a corner
 * (next to that corner's wall hole - you can't set it on the corner itself or on a hole) -> then herd the
 * ferret into that corner so it bolts into the trapped portal. Only the Fletching (make) requirement is a real
 * gate; a failed Lay is just a bad tile, so we cycle corners rather than skipping. If our Fletching can't meet
 * this floor's rolled make requirement we mark the room bonus so the picker routes around the locked door.
 * Lives HERE, same isolation as the ice rooms / bosses.
 *
 * The branches, wall holes and trap are matched on cache names and options rather than ids, because every one
 * of them has a separate id per dungeon theme - keying on the furnished ones declined every other theme's room
 * as unsolvable and banned the cell with the branches standing right there.
 */
object FerretPuzzle {
    private const val FERRET = 11010            // rand_critter_key_critter
    private const val DRY_LOGS = 17377          // item, "Make-trap" (Fletching)
    private const val SIMPLE_TRAP = 17378       // item, "Lay"
    private const val TRAP = "Simple trap"      // loc; "Dismantle" while empty, "Pick-up" once the ferret is in
    // The critter door "Unlock"s with the key, then TRANSFORMS to the unlocked variant ("Enter" to cross). The
    // analyzer still reads the stale key-icon and won't target the room behind it, so we drive the cross here.
    private val CRITTER_DOORS_LOCKED = setOf(35907, 49577, 49578, 49579, 54001)     // rand_critter_key_door_<theme>
    private val CRITTER_DOORS_UNLOCKED = setOf(137117, 137118, 137119, 137120, 137121) // ..._door_unlocked_<theme>
    private const val UNLOCK = "Unlock"
    private const val ROOM_TILES = 16
    private const val RANGE = 16
    private const val MAKE_LIMIT = 3

    private var makeFails = 0
    private val triedLayTiles = HashSet<Pair<Int, Int>>()

    fun present(session: DungeonSession): Boolean {
        val room = roomOf(localPlayer.tile)
        if (session.currentCell != null && session.currentCell in session.bannedCells) return false
        return ferret(room) != null || lockedDoor(room) != null || unlockedDoor(room) != null
    }

    suspend fun solve(session: DungeonSession, script: Script) {
        val room = roomOf(localPlayer.tile)

        val ferret = ferret(room)
        if (ferret == null) {
            triedLayTiles.clear()
            // Ferret trapped -> Pick-up the full trap for the key -> Unlock the door -> ENTER it to cross into
            // the room behind (the analyzer won't route us there - the key-icon still reads locked).
            val fullTrap = fullTrap(room)
            if (fullTrap != null) {
                approach(script, fullTrap)
                if (fullTrap.interact("Pick-up")) {
                    script.waitUntilNotMoving()
                    script.delayUntil(gaussian(2000L, 500L)) { fullTrap(room) == null }
                }
                script.delay(560, 150)
                println("DUNG-FERRET: caught - picked up the trap")
                return
            }
            lockedDoor(room)?.let {
                approach(script, it)
                if (it.interact(UNLOCK)) {
                    script.waitUntilNotMoving()
                    script.delayUntil(gaussian(2000L, 500L)) { lockedDoor(room) == null }
                }
                script.delay(560, 150)
                return
            }
            unlockedDoor(room)?.let {
                approach(script, it)
                if (it.interact("Enter")) {
                    script.waitUntilNotMoving()
                    println("DUNG-FERRET: crossed the unlocked door")
                }
                script.delay(420, 110)
                return
            }
            script.delay(420, 110)
            return
        }

        // A trap already on the floor -> herd the ferret into the corner beside it.
        placedTrap(room)?.let { herd(script, ferret, nearestCorner(room, it.tile)); return }

        // Hold a trap -> lay it beside a corner portal (interior + toward-centre from the outer wall hole).
        val trap = inventory.firstOrNull { it.id == SIMPLE_TRAP }
        if (trap != null) {
            val layTile = cornerLayTile(room) ?: run { script.delay(400, 110); return }
            if (localPlayer.tile.x != layTile.x || localPlayer.tile.y != layTile.y) {
                walkTo(layTile, false)
                script.waitUntilNotMoving()
                return
            }
            if (trap.click("Lay")) {
                script.waitUntilNotMoving()
                script.delayUntil(gaussian(1200L, 300L)) { placedTrap(room) != null || inventory.firstOrNull { it.id == SIMPLE_TRAP } == null }
            }
            if (placedTrap(room) != null || inventory.firstOrNull { it.id == SIMPLE_TRAP } == null) {
                println("DUNG-FERRET: trap laid at (${layTile.x},${layTile.y})")
            } else {
                triedLayTiles += layTile.x to layTile.y
                println("DUNG-FERRET: can't set trap at (${layTile.x},${layTile.y}) - trying another portal")
            }
            return
        }

        // Make a trap (Fletching gate) or chop for logs first.
        val logs = inventory.firstOrNull { it.id == DRY_LOGS }
        if (logs != null) {
            if (logs.click("Make-trap"))
                script.delayUntil(gaussian(1600L, 400L)) { inventory.firstOrNull { it.id == SIMPLE_TRAP } != null }
            if (inventory.firstOrNull { it.id == SIMPLE_TRAP } != null) { makeFails = 0; return }
            if (++makeFails >= MAKE_LIMIT) ban(session, "Fletching too low to make the trap")
            return
        }

        val branches = getAllObjectsWithinRange(RANGE)
            .firstOrNull { it.name() == "Dry branches" && it.hasOption("Chop") && roomOf(it.tile) == room }
        if (branches == null) { ban(session, "no dry branches to make a trap"); return }
        approach(script, branches)
        if (branches.interact("Chop")) {
            script.waitUntilNotMoving()
            script.delayUntil(gaussian(2000L, 500L)) { inventory.hasItem(DRY_LOGS) }
        }
        script.delay(560, 150)
    }

    // Push the ferret toward the trapped corner: stand on the side of it AWAY from the corner so it flees
    // toward the corner and bolts into the trapped portal. When it's already in the corner, just hold.
    private suspend fun herd(script: Script, ferret: NPC, corner: Tile) {
        val fx = ferret.tile.x
        val fy = ferret.tile.y
        if (abs(fx - corner.x) <= 1 && abs(fy - corner.y) <= 1) { script.delay(420, 110); return }
        val push = Tile.of(fx + Integer.signum(fx - corner.x), fy + Integer.signum(fy - corner.y), ferret.tile.plane)
        walkTo(push, false)
        script.delay(420, 110)
    }

    // The lay tile for the nearest outer-wall portal: one step off the wall (interior) and one step toward
    // room-centre (off the corner) - the exact tile a manual placement lands on. Untried portals only.
    private fun cornerLayTile(room: Pair<Int, Int>): Tile? {
        val baseX = room.first * ROOM_TILES
        val baseY = room.second * ROOM_TILES
        val cx = baseX + ROOM_TILES / 2
        val cy = baseY + ROOM_TILES / 2
        val plane = localPlayer.tile.plane
        return getAllObjectsWithinRange(RANGE)
            .filter { it.name() == "Hole" && roomOf(it.tile) == room }
            .sortedBy { it.tile.getDistance(localPlayer.tile) }
            .firstNotNullOfOrNull { hole ->
                val hx = hole.tile.x
                val hy = hole.tile.y
                val lay = when {
                    hx == baseX + ROOM_TILES - 1 -> Tile.of(hx - 1, hy + Integer.signum(cy - hy), plane) // east wall
                    hx == baseX -> Tile.of(hx + 1, hy + Integer.signum(cy - hy), plane)                  // west wall
                    hy == baseY + ROOM_TILES - 1 -> Tile.of(hx + Integer.signum(cx - hx), hy - 1, plane) // south wall
                    hy == baseY -> Tile.of(hx + Integer.signum(cx - hx), hy + 1, plane)                  // north wall
                    else -> null
                }
                lay?.takeIf { (it.x to it.y) !in triedLayTiles }
            }
    }

    private fun placedTrap(room: Pair<Int, Int>): SceneObject? =
        getAllObjectsWithinRange(RANGE)
            .firstOrNull { it.name() == TRAP && it.hasOption("Dismantle") && roomOf(it.tile) == room }

    private fun fullTrap(room: Pair<Int, Int>): SceneObject? =
        getAllObjectsWithinRange(RANGE)
            .firstOrNull { it.name() == TRAP && it.hasOption("Pick-up") && roomOf(it.tile) == room }

    private fun nearestCorner(room: Pair<Int, Int>, tile: Tile): Tile {
        val baseX = room.first * ROOM_TILES
        val baseY = room.second * ROOM_TILES
        val plane = localPlayer.tile.plane
        return listOf(
            Tile.of(baseX, baseY, plane), Tile.of(baseX + ROOM_TILES - 1, baseY, plane),
            Tile.of(baseX, baseY + ROOM_TILES - 1, plane), Tile.of(baseX + ROOM_TILES - 1, baseY + ROOM_TILES - 1, plane),
        ).minByOrNull { it.getDistance(tile) }!!
    }

    private fun ban(session: DungeonSession, reason: String) {
        val cell = session.currentCell
        session.ban(cell, "ferret: $reason")
    }

    private fun ferret(room: Pair<Int, Int>): NPC? =
        allNpcsWithinRange(RANGE) { it.id == FERRET && roomOf(it.tile) == room }
            .minByOrNull { it.tile.getDistance(localPlayer.tile) }

    private fun lockedDoor(room: Pair<Int, Int>): SceneObject? =
        getAllObjectsWithinRange(RANGE).firstOrNull { it.id in CRITTER_DOORS_LOCKED && it.hasOption(UNLOCK) && roomOf(it.tile) == room }

    private fun unlockedDoor(room: Pair<Int, Int>): SceneObject? =
        getAllObjectsWithinRange(RANGE).firstOrNull { it.id in CRITTER_DOORS_UNLOCKED && roomOf(it.tile) == room }

    private suspend fun approach(script: Script, obj: SceneObject) {
        if (obj.tile.getDistance(localPlayer.tile) <= 1) return
        walkTo(obj.tile, false)
        script.waitUntilNotMoving()
    }

    private fun roomOf(tile: Tile): Pair<Int, Int> = tile.x / ROOM_TILES to tile.y / ROOM_TILES
}
