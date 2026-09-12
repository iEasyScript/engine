package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.Script
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import com.projectx.util.gaussian
import world.gregs.voidps.type.Tile

/**
 * The Daemonheim "Convergence" puzzle (crystal cross): a light on each of the 4 arms advances one tile per
 * tick toward the central Large crystal; the arms are desynchronised. Inactive lodestones are powered up to
 * activate missing colours; pressure pads at the arm ends freeze an arm's light while stood on. Solve = land
 * all 4 colours on the Large crystal the same tick. All of it lives HERE, never in the generic navigator
 * (same isolation as [IceRoom] / the bosses).
 *
 * Everything keys off the theme-stable DISPLAY NAMES ("Large crystal", "Crystal"/"<Colour> crystal",
 * "Inactive lodestone"/"<Colour> lodestone", "Pressure pad"), so it works on every floor theme without an
 * id table. This pass powers up the lodestones (banning the room when Magic is too low) and INSTRUMENTS the
 * arms tick-by-tick - including one controlled pad-freeze probe - so the exact advance/freeze timing can be
 * read from the logs before the pristine synchronise step is committed.
 */
object ConvergencePuzzle {
    private const val ROOM_TILES = 16
    private const val SCAN = 14
    private const val ARM_MAX = 7
    private const val POWERUP_ATTEMPTS = 2
    private const val CENTRE = "Large crystal"
    private const val OFF_CRYSTAL = "Crystal"
    private const val PAD = "Pressure pad"
    private const val INACTIVE_LODE = "Inactive lodestone"

    // Ticks earlier than "majority at outer" to begin the step-off, absorbing walk latency. Tune from traces.
    private const val RELEASE_LEAD = 0

    private val bannedRooms = HashSet<Pair<Int, Int>>()

    // Loops in which NOTHING advanced - no lodestone sparked and no extra arm brought into phase. Aligning
    // one arm takes several full rotations, so a working solve looks idle for minutes; only a run that is
    // neither sparking nor aligning is actually stuck.
    private const val IDLE_LIMIT = 120
    private var progressRoom: Pair<Int, Int>? = null
    private var idleLoops = 0
    private var bestAligned = 0
    private val powerUpFails = HashMap<Pair<Int, Int>, Int>()

    fun isBanned(room: Pair<Int, Int>): Boolean = room in bannedRooms

    fun present(): Boolean {
        val room = roomOf(localPlayer.tile)
        if (room in bannedRooms) return false
        val centre = centre() ?: return false
        return roomOf(centre.tile) == room && !solved(centre)
    }

    // Bright centre = every colour has converged and the puzzle is finished; the doors are now normal.
    private fun solved(centre: SceneObject): Boolean = centreState(centre) == CentreState.BRIGHT

    suspend fun solve(script: Script) {
        val room = roomOf(localPlayer.tile)

        // Two things count as progress: a lodestone sparked, and an arm brought into phase. Pinning genuinely
        // works - it holds one arm while the others come round - but it takes several full rotations per arm,
        // so a room can be doing everything right and look idle for minutes. Counting only sparks would ban
        // exactly that solve for being slow.
        if (room != progressRoom) {
            progressRoom = room
            idleLoops = 0
            bestAligned = 0
        }

        // 1) Activate the missing colours. A lodestone that stays "Inactive" after our Power-up interacts is
        //    one our Magic can't reach - ban the whole room (it's unsolvable for us, treat it as bonus).
        val inactive = lodestones().firstOrNull { it.name() == INACTIVE_LODE }
        if (inactive != null) {
            val key = inactive.tile.x to inactive.tile.y
            approach(script, inactive)
            if (inactive.interact("Power-up")) {
                script.waitUntilNotMoving()
                script.delayUntil(gaussian(1600L, 400L)) { !objectAt(inactive.tile, INACTIVE_LODE) }
            }
            val stillInactive = objectAt(inactive.tile, INACTIVE_LODE)
            if (stillInactive) {
                val fails = (powerUpFails[key] ?: 0) + 1
                powerUpFails[key] = fails
                if (fails >= POWERUP_ATTEMPTS) {
                    bannedRooms += room
                    println("DUNG-CONV: cannot power up lodestone at $key (Magic too low) - banning room $room as bonus")
                }
            } else {
                powerUpFails.remove(key)
                idleLoops = 0
            }
            return
        }


        // 2) All colours active - synchronise the arms so all four hit the centre on the same tick.
        val arms = readArms() ?: run { script.delay(150, 40); return }
        logArms("state", arms)

        val lit = arms.filter { it.distance != null }
        if (lit.size < arms.size) { script.delay(150, 40); return } // read again on a tick where every arm is lit

        // The synced majority defines the target phase; any arm off it is an outlier we must re-phase.
        val mode = lit.groupingBy { it.distance!! }.eachCount().maxByOrNull { it.value }!!.key
        val aligned = lit.count { it.distance == mode }
        if (aligned > bestAligned) {
            bestAligned = aligned
            idleLoops = 0
        } else if (++idleLoops >= IDLE_LIMIT) {
            bannedRooms += room
            println("DUNG-CONV: $idleLoops loops with no arm gained past $bestAligned/${arms.size} - banning room $room as bonus")
            return
        }

        val outliers = arms.filter { it.distance != null && it.distance != mode && it.padTile != null }
        if (outliers.isEmpty()) {
            // All arms share a phase -> stand clear of every pad and they converge on the centre together.
            standClear(script, arms)
            script.delay(300, 80)
            return
        }
        // Work the arm the majority comes round to SOONEST, not whichever sits first in compass order. Every
        // arm has to be pinned eventually and an aligned one stays aligned, so the order is pure scheduling -
        // and picking it blind is what stretched a solve to nine minutes.
        rephaseOutlier(script, outliers.minByOrNull { ticksUntilRelease(it, mode) }!!)
    }

    /**
     * Ticks the majority still has to advance before this outlier can be released onto their phase. Both wrap
     * at the arm's length, so it is a cyclic gap - the same "soonest, not nearest" ranking the garden gates
     * use, and like that one it claims only an ordering, never a predicted time.
     *
     * Note [outlier].length is read PER ARM on purpose, not hoisted to a shared constant. Arms of different
     * lengths rank correctly against each other because each is measured in its own cycle, exactly as the
     * garden's colour gap survives not knowing the big flower's rate. Replacing it with one length would look
     * like a simplification and would quietly break every room whose arms are not all the same size.
     */
    private fun ticksUntilRelease(outlier: Arm, mode: Int): Int {
        val len = outlier.length
        if (len <= 0) return Int.MAX_VALUE
        return ((len - RELEASE_LEAD - mode) % len + len) % len
    }

    // Pin the outlier at its outer tile (stand on its pad), then release it (step off) on the tick the synced
    // majority is back at the outer tile, so the outlier's fresh length-tick run lands on the centre with them.
    // RELEASE_LEAD absorbs the ~1-tick walk latency of stepping off the pad; tune from the DUNG-CONV trace.
    private suspend fun rephaseOutlier(script: Script, outlier: Arm) {
        val pad = outlier.padTile ?: return
        println("DUNG-CONV: rephase ${outlier.dir.tag} (d=${outlier.distance}) - pinning on pad (${pad.x},${pad.y})")
        walkTo(pad, false)
        script.waitUntilNotMoving()
        if (localPlayer.tile.x != pad.x || localPlayer.tile.y != pad.y) return

        val releaseAt = outlier.length - RELEASE_LEAD
        script.delayUntil(gaussian(8000L, 1500L), pollingDelayMillis = 40) {
            val a = readArms() ?: return@delayUntil false
            val majority = a.filter { it.dir != outlier.dir && it.distance != null }
            majority.isNotEmpty() && majority.all { it.distance == releaseAt }
        }
        val off = stepOffTile(pad) ?: return
        walkTo(off, false)
        script.waitUntilNotMoving()
        readArms()?.let { logArms("released", it) }
    }

    // Move off any pad/crystal tile to a clear tile in the room so all arms run to the centre untouched.
    private suspend fun standClear(script: Script, arms: List<Arm>) {
        val on = pads().firstOrNull { it.tile.x == localPlayer.tile.x && it.tile.y == localPlayer.tile.y } ?: return
        val off = stepOffTile(on.tile) ?: return
        println("DUNG-CONV: all synced (d=${arms.firstOrNull { it.distance != null }?.distance}) - standing clear")
        walkTo(off, false)
        script.waitUntilNotMoving()
    }

    private fun logArms(tag: String, arms: List<Arm>) {
        val s = arms.joinToString(" ") { "${it.dir.tag}:${it.colour ?: "-"}@${it.distance ?: "-"}/${it.length}" }
        println("DUNG-CONV[${Bootstrap.client.clientCycle}] $tag me=(${localPlayer.tile.x},${localPlayer.tile.y}) $s")
    }

    private data class Arm(val dir: Dir, val colour: String?, val distance: Int?, val length: Int, val padTile: Tile?)

    private enum class Dir(val dx: Int, val dy: Int, val tag: String) {
        NORTH(0, 1, "N"), SOUTH(0, -1, "S"), EAST(1, 0, "E"), WEST(-1, 0, "W")
    }

    // For each arm: walk out from the centre; the arm's length is how far the crystal locs run, and the lit
    // one (a "<Colour> crystal", not the plain "Crystal") gives the colour + its distance from the centre.
    private fun readArms(): List<Arm>? {
        val centre = centre() ?: return null
        val locs = getAllObjectsWithinRange(SCAN)
        val padTiles = locs.filter { it.name() == PAD }.map { it.tile }
        return Dir.entries.map { dir ->
            var length = 0
            var colour: String? = null
            var distance: Int? = null
            for (k in 1..ARM_MAX) {
                val t = Tile.of(centre.tile.x + dir.dx * k, centre.tile.y + dir.dy * k, centre.tile.plane)
                val crystal = locs.firstOrNull { it.tile.x == t.x && it.tile.y == t.y && isCrystal(it.name()) } ?: break
                length = k
                if (crystal.name() != OFF_CRYSTAL) {
                    colour = crystal.name().removeSuffix(" crystal")
                    distance = k
                }
            }
            val pad = padTiles.firstOrNull { onArm(centre.tile, dir, it) }
            Arm(dir, colour, distance, length, pad)
        }
    }

    private fun isCrystal(name: String): Boolean = name == OFF_CRYSTAL || name.endsWith(" crystal")

    private fun onArm(centre: Tile, dir: Dir, tile: Tile): Boolean = when (dir) {
        Dir.NORTH -> tile.x == centre.x && tile.y > centre.y
        Dir.SOUTH -> tile.x == centre.x && tile.y < centre.y
        Dir.EAST -> tile.y == centre.y && tile.x > centre.x
        Dir.WEST -> tile.y == centre.y && tile.x < centre.x
    }

    private fun centre(): SceneObject? {
        val room = roomOf(localPlayer.tile)
        return getAllObjectsWithinRange(SCAN).firstOrNull { it.name() == CENTRE && roomOf(it.tile) == room }
    }

    private fun lodestones(): List<SceneObject> =
        getAllObjectsWithinRange(SCAN).filter { it.name().endsWith("lodestone") }

    private fun pads(): List<SceneObject> = getAllObjectsWithinRange(SCAN).filter { it.name() == PAD }

    private fun objectAt(tile: Tile, name: String): Boolean =
        getAllObjectsWithinRange(SCAN).any { it.tile.x == tile.x && it.tile.y == tile.y && it.name() == name }

    private enum class CentreState { OFF, DIM, BRIGHT }

    private val DIM_IDS = setOf(49512, 49511, 49510, 35070, 54276)
    private val BRIGHT_IDS = setOf(49515, 49514, 49513, 35231, 54277)

    private fun centreState(centre: SceneObject): CentreState = when (centre.id) {
        in BRIGHT_IDS -> CentreState.BRIGHT
        in DIM_IDS -> CentreState.DIM
        else -> CentreState.OFF
    }

    private fun stepOffTile(pad: Tile): Tile? {
        val plane = localPlayer.tile.plane
        return listOf(
            Tile.of(pad.x + 1, pad.y, plane), Tile.of(pad.x - 1, pad.y, plane),
            Tile.of(pad.x, pad.y + 1, plane), Tile.of(pad.x, pad.y - 1, plane),
        ).firstOrNull { getAllObjectsWithinRange(SCAN).none { o -> o.tile.x == it.x && o.tile.y == it.y && isCrystal(o.name()) } }
    }

    private suspend fun approach(script: Script, obj: SceneObject) {
        if (obj.tile.getDistance(localPlayer.tile) <= 3) return
        walkTo(obj.tile, false)
        script.waitUntilNotMoving()
    }

    private fun roomOf(tile: Tile): Pair<Int, Int> = tile.x / ROOM_TILES to tile.y / ROOM_TILES
}
