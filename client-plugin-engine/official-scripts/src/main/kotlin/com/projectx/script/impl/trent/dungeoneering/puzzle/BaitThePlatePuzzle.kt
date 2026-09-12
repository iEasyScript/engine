package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.script.api.MakeX
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.makeX
import com.projectx.script.api.makeXConfirm
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import com.projectx.script.impl.trent.dungeoneering.DungeonSession
import com.projectx.util.gaussian

/**
 * Daemonheim "bait the plate": a critter has to end up on the room's pressure plate for its door to open.
 *
 * The plate and the critter sit in a sunken tile grid the player CANNOT walk into - the server refuses every
 * step onto it - so the critter is never herded by standing near it (it ignores the player entirely). The bait
 * is a cooked Vile fish, landed at the room's fishing spot (which refuses to fish without dungeoneering
 * feathers), cooked on a fire, then used on a grid square ("You throw the fish!").
 *
 * The critter walks at the FISH, not at the plate, and covers only two or three squares before it is
 * teleported back to a spawn hole - so the plate cannot simply be thrown at from across the grid, which is a
 * loop that never ends. Each throw is aimed at the square nearest the plate that is still inside the critter's
 * reach, walking it in a stage at a time until the plate itself is that square.
 */
object BaitThePlatePuzzle {

    private val PLATE = setOf(35326, 35328, 49555, 49556, 49557, 49558, 49559, 49560, 54296, 54297)
    private val FISHING_SPOT = setOf(35347, 49561, 49562, 49563, 54298)
    private val FLOOR = setOf(35293, 49546, 49547, 49548, 54293)
    private val HOLE = setOf(35302, 49549, 49550, 49551, 54294)
    private val SPAWN = setOf(35312, 49552, 49553, 49554, 54295)
    private val GRID = FLOOR + HOLE + SPAWN + PLATE

    // Tiles the critter covers before it bolts back to a spawn hole. Measured live: it walks at the fish for
    // roughly four seconds, about a tile every 0.8s, then resets wherever it had got to.
    private const val LEAD_REACH = 3
    private const val TILES_PER_BAIT = 2

    // Baits held in reserve beyond the bare minimum, for the steps the critter loses to a diagonal.
    private const val STOCK_MARGIN = 2

    // Gap between throws in a run. It has to be comfortably under the reset so the next bait is already down
    // when the critter would otherwise bolt - that chaining is the whole point of stocking up first.
    private const val THROW_SPACING_MS = 1500

    private const val CRITTER = 11007
    private const val RAW_FISH = 17374
    private const val BAIT_FISH = 17375
    private const val FEATHER = 17796
    private const val RANGE = 24
    private const val APPROACH_RANGE = 5
    private const val STALL_LIMIT = 5
    private const val CAST_LIMIT = 10

    private var attemptCell: Pair<Int, Int>? = null
    private var stalledThrows = 0
    private var emptyCasts = 0

    private fun plate(): SceneObject? = objectsInRoom(RANGE).firstOrNull { it.id in PLATE }
    private fun critter(): NPC? = npcsInRoom(RANGE) { it.id == CRITTER }.firstOrNull()

    /**
     * The grid, plate and fishing spot all stay in the room once it is finished, so claiming on their presence
     * alone would hold a solved room and stall the navigator. The critter standing on the plate is the
     * completion signal.
     */
    fun present(session: DungeonSession): Boolean {
        if (session.currentCell != null && session.currentCell in session.bannedCells) return false
        val plate = plate() ?: return false
        val critter = critter() ?: return false
        return critter.tile != plate.tile
    }

    suspend fun solve(session: DungeonSession, script: Script) {
        if (session.currentCell != attemptCell) {
            attemptCell = session.currentCell
            stalledThrows = 0
            emptyCasts = 0
        }

        val plate = plate() ?: return

        // Stock the whole run BEFORE throwing any of it. One bait moves the critter two tiles and then it is
        // teleported back to a spawn, so baits arriving a fishing-trip apart are always spent walking the same
        // two tiles: the gap oscillates and never closes. Enough fish to cover the gap, thrown back to back,
        // chains the critter from one bait to the next without it ever resetting.
        val critter = critter() ?: return
        val needed = critter.tile.getDistance(plate.tile) / TILES_PER_BAIT + STOCK_MARGIN
        val cooked = inventory.filter { it.id == BAIT_FISH }
        if (cooked.size >= needed || (cooked.isNotEmpty() && !inventory.hasItem(FEATHER))) {
            throwRun(session, script, plate)
            return
        }

        if (inventory.hasItem(RAW_FISH)) {
            cookBait(session, script)
            return
        }
        if (!inventory.hasItem(FEATHER)) {
            ban(session, "no dungeoneering feathers to land the bait (buy them from the Smuggler)")
            return
        }
        landBait(session, script)
    }

    /**
     * The critter walks at the FISH, not at the plate, and only gets a few tiles before it is teleported back
     * to a spawn hole - so a throw aimed at the far-off plate is never reached and the room loops forever.
     * Lead it instead: aim at the grid square nearest the plate that is still inside the critter's reach, and
     * repeat until the plate itself is the nearest such square.
     */
    private suspend fun throwRun(session: DungeonSession, script: Script, plate: SceneObject) {
        val opening = critter()?.tile?.getDistance(plate.tile) ?: return
        var best = opening
        approach(script, plate)

        while (true) {
            val critter = critter() ?: break
            val gap = critter.tile.getDistance(plate.tile)
            if (gap == 0) break
            val fish = inventory.firstOrNull { it.id == BAIT_FISH } ?: break
            val target = objectsInRoom(RANGE)
                .filter { it.id in GRID && it.tile.getDistance(critter.tile) <= LEAD_REACH }
                .minByOrNull { it.tile.getDistance(plate.tile) }
                ?: plate

            val from = critter.tile
            if (!fish.useOn(target)) break
            script.delayUntil(gaussian(THROW_SPACING_MS.toLong(), 400L)) { critter()?.tile != from }
            val landed = critter()?.tile
            val after = landed?.getDistance(plate.tile) ?: gap
            if (after < best) best = after
            println(
                "DUNG-BAIT: threw at (${target.tile.x},${target.tile.y}) - critter (${from.x},${from.y})" +
                    " -> (${landed?.x},${landed?.y}), plate gap $gap -> $after (best $best)"
            )
            script.delay(THROW_SPACING_MS, 350)
        }

        // Progress is the BEST gap the run reached, not the gap inside one throw. Every single throw closes
        // the distance before the reset undoes it, so a per-throw test scores each cycle a success and the
        // give-up can never fire - which is exactly how this room looped for four minutes.
        if (best < opening) stalledThrows = 0
        else if (++stalledThrows >= STALL_LIMIT) ban(session, "the critter never got closer than $best to the plate")
        script.delay(560, 150)
    }

    private suspend fun cookBait(session: DungeonSession, script: Script) {
        val fire = objectsInRoom(RANGE).firstOrNull { it.hasOption("Cook at") }
        if (fire == null) {
            ban(session, "no fire in the room to cook the bait on")
            return
        }
        approach(script, fire)
        if (fire.interact("Cook at")) script.delayUntil(gaussian(4000L, 900L)) { MakeX.isOpen }
        if (!MakeX.isOpen) {
            script.delay(520, 150)
            return
        }
        val recipe = MakeX.craftables().firstOrNull { it.itemId == BAIT_FISH }
        if (recipe == null || !script.makeX({ it == recipe.name })) script.makeXConfirm()
        script.delayUntil(gaussian(12000L, 3000L)) { inventory.hasItem(BAIT_FISH) }
        script.delay(560, 150)
    }

    private suspend fun landBait(session: DungeonSession, script: Script) {
        val spot = objectsInRoom(RANGE).firstOrNull { it.id in FISHING_SPOT }
        if (spot == null) {
            ban(session, "no fishing spot in the room to land the bait at")
            return
        }
        approach(script, spot)
        if (spot.interact("Fish"))
            script.delayUntil(gaussian(11000L, 3000L)) { inventory.hasItem(RAW_FISH) }
        if (inventory.hasItem(RAW_FISH)) emptyCasts = 0
        else if (++emptyCasts >= CAST_LIMIT) ban(session, "the fishing spot never landed any bait")
        script.delay(600, 170)
    }

    private suspend fun approach(script: Script, obj: SceneObject) {
        if (obj.tile.getDistance(localPlayer.tile) <= APPROACH_RANGE) return
        walkTo(obj.tile, false)
        script.waitUntilNotMoving()
    }

    private fun ban(session: DungeonSession, reason: String) {
        val cell = session.currentCell
        session.ban(cell, "bait-the-plate: $reason")
    }
}
