package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.quest.runtime.RecentChat
import com.projectx.script.Script
import com.projectx.script.api.groundItems
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import com.projectx.script.impl.trent.dungeoneering.DungeonSession
import com.projectx.script.impl.trent.dungeoneering.DungeonTables
import com.projectx.script.impl.trent.dungeoneering.auto.DungeonNavigator
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import com.projectx.script.impl.trent.dungeoneering.DungeonRefusals
import com.projectx.pathfinder.WorldCollision
import world.gregs.voidps.collision.ClipFlag

/**
 * Daemonheim "maze exit" room: the floor key sits inside a walled maze whose only openings are Barriers, and a
 * Barrier refuses to pass ("You find yourself unable to move through the barrier.") until the Switch beside the
 * entrance is pulled. Pulling it also unlocks the chest at the centre and starts a timer - when that runs out
 * every Fountain set into the walls turns on and a toxin floods the WHOLE room, damaging harder every few
 * seconds until it kills. Opening the centre chest stops it and clears the obstacles, so a flooded room is
 * still winnable - it becomes a race against damage instead of against the countdown, and is never written off.
 *
 * A Barrier is a wall loc that always clips, so it is never walked through - each is its own Go-through that
 * steps the avatar across. Routing therefore searches the real instance collision plus one edge per Barrier,
 * costing a crossing but not a step, and only the FIRST crossing of the winning route is driven per tick, so a
 * mis-plan costs one step and re-plans. Pedestals are mineable rubble sealing corridors; they are cleared only
 * when the target is otherwise unreachable, since mining burns far more of the timer than walking around.
 *
 * Matched on cache names and options, so every dungeon theme is recognised.
 */
object MazePuzzle {

    private const val RANGE = 24
    private const val ROOM_TILES = 16
    private const val PULL_LIMIT = 3

    // rand_maze_exit_door_frozen - the room's own exit, rather than whichever door is nearest the switch.
    private const val MAZE_EXIT_DOOR = 49336

    private const val WALK_BASE_MS = 1200L
    private const val WALK_MS_PER_STEP = 400L
    private const val GATE_MS = 3000L
    private const val TOXIN_WINDOW_MS = 10_000L
    private const val STEP_MS = 2500L
    private const val APPROACH_RANGE = 4

    private val CARDINALS = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)

    private var activeRoom: Pair<Int, Int>? = null
    private var armed = false
    private var pulls = 0
    private var abandoned = false

    private var switchTile: Tile? = null
    private val refusedFrom = HashSet<Pair<Int, Int>>()
    // Tiles the server has refused to walk us to, per room, kept for the life of the floor. This is a cached
    // server answer, not a give-up: it writes off no room, barrier or route, only a tile already answered with
    // "You can't reach that". Re-learning one cost eleven tiles of round trip - sixteen of a forty-five second
    // budget - on every single entry, because the knowledge died with the plan.
    private val undeliverableByRoom = HashMap<Pair<Int, Int>, MutableSet<Pair<Int, Int>>>()

    private fun undeliverable(): MutableSet<Pair<Int, Int>> =
        undeliverableByRoom.getOrPut(roomOf(localPlayer.tile)) { HashSet() }

    private class Goal(val tile: Tile, val stepOn: Boolean, val act: suspend () -> Unit)

    private fun lever() = objectsInRoom(RANGE).firstOrNull { it.name() == "Switch" && it.hasOption("Pull") }

    private fun chest() = objectsInRoom(RANGE).firstOrNull { it.name() == "Locked chest" && it.hasOption("Open") }

    private fun barriers() = objectsInRoom(RANGE).filter { it.name() == "Barrier" && it.hasOption("Go-through") }

    private fun pedestals() = objectsInRoom(RANGE).filter { it.name() == "Pedestal" && it.hasOption("Mine") }

    // The Fountains that animate when the toxin starts are not in this room's scene at all, so reading them can
    // never report a flooded room - the server does, every few seconds, for as long as it burns. Getting this
    // wrong sent the solver after the floor key while the toxin ran, and it died mid-detour.
    private fun fountains() = objectsInRoom(RANGE).filter { it.name() == "Fountain" }

    // Fountains are real walls the collision rebuild never records - rand_frzn_maze_gas_fountain(_active)
    // blocks walking while carrying no clipType/blockwalk, so every flag reads the tile as open and the server
    // still refuses it. That one omission accounts for every otherwise-unexplained refusal in this room.
    //
    // They are WALL_STRAIGHT (shape 0) exactly like the barriers, so each closes the EDGE its rotation names -
    // not its tile. Blocking the tile instead seals the maze outright. Unlike a barrier there is nothing to
    // Go-through: this edge is simply shut, in both directions.
    private fun fountainEdges(): Set<Pair<Pair<Int, Int>, Pair<Int, Int>>> {
        val out = HashSet<Pair<Pair<Int, Int>, Pair<Int, Int>>>()
        for (fountain in fountains()) {
            val at = fountain.tile.x to fountain.tile.y
            val (dx, dy) = wallDelta(fountain.rotation.toInt())
            val across = at.first + dx to at.second + dy
            out += at to across
            out += across to at
        }
        return out
    }

    private fun gassed() = RecentChat.containsRecent("toxin in the room", TOXIN_WINDOW_MS) ||
        objectsInRoom(RANGE).any { it.name() == "Fountain" && it.defs.animations != null }

    private fun floorKey() = groundItems.firstOrNull {
        inPlayerRoom(it.tile) && DungeonTables.KEY_ITEM_NAME.matches(it.name)
    }

    fun present(session: DungeonSession): Boolean {
        val room = roomOf(localPlayer.tile)
        // Once we have carried a solved room's exit through, it is an ordinary room forever after: the outer
        // ring reaches every door with no gate crossings, so the navigator passes through unaided. Re-taking
        // it on each entry just walked back out of the door the navigator had come in to route past.
        solvedRoom?.let { if (room != it) { releasedRooms += it; solvedRoom = null } }
        if (room in releasedRooms) return false
        if (abandoned && room == activeRoom) return false
        if (session.currentCell != null && session.currentCell in session.bannedCells) return false
        if (barriers().size < 2) return false
        // Unsolved: ours. Solved but still in the centre: ours for that one trip out. Otherwise not ours.
        return chest() != null || room == solvedRoom
    }

    suspend fun solve(session: DungeonSession, script: Script) {
        val room = roomOf(localPlayer.tile)
        if (room != activeRoom) {
            activeRoom = room
            armed = false
            abandoned = false
            switchTile = null
            clearedForExit = false
            lastBulkTarget = null
            refusedFrom.clear()
            pulls = 0
        }

        if (contentsChanged()) {
            undeliverableByRoom.remove(roomOf(localPlayer.tile))
            refusedFrom.clear()
            lastBulkTarget = null
            DungeonNavigator.forgetRefusedDoors()
        }
        val goal = goal(session, script) ?: run { abandon(session, "nothing left to reach in the maze"); return }
        if (atGoal(goal)) return goal.act()
        val path = planPath(goal.tile, goal.stepOn)
            ?: run { abandon(session, "no tile path to (${goal.tile.x},${goal.tile.y})"); return }
        advance(script, path)
    }

    private class Step(val tile: Tile, val barrier: SceneObject?)

    private var lastBulkTarget: Pair<Int, Int>? = null

    // A solved maze still has to be walked out of, and the exit is a door-type loc the navigator would try to
    // WALK ONTO - which no door permits. Keep the room until we have crossed out, so the exit is delivered by
    // the same Fountain-aware search that solved the maze: route to an adjacent tile, then Enter.
    private var solvedRoom: Pair<Int, Int>? = null
    private val releasedRooms = HashSet<Pair<Int, Int>>()

    // A maze MUTATES: opening the chest swaps every Fountain to its inactive id and removes obstacles, so every
    // conclusion drawn before that moment is about a room that no longer exists. Cached refusals are the
    // dangerous ones - an exit door retired while the room was sealed would stay blacklisted after it opened.
    private var contentsSignature: String? = null
    private var clearedForExit = false

    private fun contentsChanged(): Boolean {
        val signature = objectsInRoom(RANGE)
            .sortedWith(compareBy({ it.tile.x }, { it.tile.y }, { it.id }))
            .joinToString(",") { "${it.id}@${it.tile.x},${it.tile.y}" }
        if (signature == contentsSignature) return false
        contentsSignature = signature
        return true
    }


    // Walls live in the rebuilt collision grid, not in the scene - the maze room exposes exactly one Wall loc
    // for a whole 16x16 maze. So collision is the wall oracle and the scene is the barrier oracle; neither is
    // sufficient alone. An unloaded zone reads -1 (every bit set) and is correctly treated as blocked.
    private fun canStep(from: Pair<Int, Int>, to: Pair<Int, Int>, dx: Int, dy: Int, plane: Int): Boolean {
        val fromFlags = WorldCollision.getFlags(from.first, from.second, plane)
        val toFlags = WorldCollision.getFlags(to.first, to.second, plane)
        if (ClipFlag.flagged(toFlags, ClipFlag.PFBW_FLOOR, ClipFlag.BW_FULL, ClipFlag.PF_FULL)) return false
        return when {
            dy > 0 -> !ClipFlag.flagged(fromFlags, ClipFlag.BW_N, ClipFlag.PF_N) &&
                !ClipFlag.flagged(toFlags, ClipFlag.BW_S, ClipFlag.PF_S)
            dy < 0 -> !ClipFlag.flagged(fromFlags, ClipFlag.BW_S, ClipFlag.PF_S) &&
                !ClipFlag.flagged(toFlags, ClipFlag.BW_N, ClipFlag.PF_N)
            dx > 0 -> !ClipFlag.flagged(fromFlags, ClipFlag.BW_E, ClipFlag.PF_E) &&
                !ClipFlag.flagged(toFlags, ClipFlag.BW_W, ClipFlag.PF_W)
            else -> !ClipFlag.flagged(fromFlags, ClipFlag.BW_W, ClipFlag.PF_W) &&
                !ClipFlag.flagged(toFlags, ClipFlag.BW_E, ClipFlag.PF_E)
        }
    }

    /**
     * The maze is a tile grid, not a collision problem. Barriers CLIP, so a collision router can only ever
     * path UP TO one and never THROUGH it - which is why routing stalled the moment the next useful gate sat
     * behind another barrier, three tiles short of the chest. Walk the grid ourselves and treat a barrier as
     * an enterable tile that costs a Go-through instead of a step. Cardinal moves only; the avatar cannot cut
     * a corner past a barrier.
     */
    private fun planPath(goalTile: Tile, stepOn: Boolean): List<Step>? {
        val here = localPlayer.tile
        val baseX = (here.x / ROOM_TILES) * ROOM_TILES
        val baseY = (here.y / ROOM_TILES) * ROOM_TILES
        val start = here.x to here.y

        val refused = undeliverable()
        val blocked = fountainEdges()
        val barrierAt = HashMap<Pair<Int, Int>, SceneObject>()
        for (barrier in barriers()) barrierAt[barrier.tile.x to barrier.tile.y] = barrier

        val previous = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        val seen = hashSetOf(start)
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue += start
        var end: Pair<Int, Int>? = null
        val crossedBy = HashMap<Pair<Int, Int>, SceneObject>()
        outer@ while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            val standingOn = barrierAt[at]
            for ((dx, dy) in CARDINALS) {
                val next = at.first + dx to at.second + dy
                if (next.first < baseX || next.first >= baseX + ROOM_TILES) continue
                if (next.second < baseY || next.second >= baseY + ROOM_TILES) continue
                // A barrier occupies an EDGE, and an edge is crossable from BOTH sides. Consulting only the
                // tile we stand on made every crossing one-directional: the avatar reached the chest through
                // five barriers and could then cross none of them back, because from the far side the
                // barrier's own edge bit is all canStep sees. The pocket was sealed by its own entrances.
                val ahead = barrierAt[next]
                val gate = when {
                    standingOn != null && (dx to dy) == wallDelta(standingOn.rotation.toInt()) -> standingOn
                    ahead != null && wallDelta(ahead.rotation.toInt()) == (-dx to -dy) -> ahead
                    else -> null
                }
                if (next in refused || (at to next) in blocked) continue
                if (gate == null && !canStep(at, next, dx, dy, here.plane)) continue
                if (!seen.add(next)) continue
                previous[next] = at
                if (gate != null) crossedBy[next] = gate
                val gap = abs(next.first - goalTile.x) + abs(next.second - goalTile.y)
                if (if (stepOn) gap == 0 else gap <= 1) { end = next; break@outer }
                queue += next
            }
        }

        var cursor = end ?: return null
        val path = ArrayList<Step>()
        while (cursor != start) {
            path += Step(Tile.of(cursor.first, cursor.second, here.plane), crossedBy[cursor])
            cursor = previous.getValue(cursor)
        }
        return path.asReversed()
    }

    // One action per call, gated on the state it changes and nothing else - this is a 45 second countdown, so
    // there is no idle pacing here. Free tiles are walked in a single run up to the next barrier.
    private suspend fun advance(script: Script, path: List<Step>) {
        val first = path.first()
        val here = localPlayer.tile
        val crossing = first.barrier
        if (crossing != null) {
            // A barrier one tile away is not reachable if a Fountain sits between - clicking it is clicking
            // through a wall, which is answered "You can't reach that".
            if (((here.x to here.y) to (crossing.tile.x to crossing.tile.y)) in fountainEdges()) return
            if (crossing.interact("Go-through")) script.delayUntil(GATE_MS) { localPlayer.tile != here }
            return
        }
        // One walk for the whole contiguous run of free tiles. Endpoint walking failed earlier because the PLAN
        // was wrong - sealed columns and phantom routes - not because delivery was; over verified barriers and
        // verified open edges the server should honour it. If it does not, single-step this run so the exact
        // offending tile is found and cached, then resume bulk walking from wherever we got to.
        val run = path.takeWhile { it.barrier == null }
        val endpoint = run.last().tile
        val key = endpoint.x to endpoint.y
        if (run.size > 1 && key != lastBulkTarget) {
            lastBulkTarget = key
            walkTo(endpoint, false)
            script.delayUntil(WALK_BASE_MS + run.size * WALK_MS_PER_STEP) { localPlayer.tile == endpoint }
            if (localPlayer.tile == endpoint) lastBulkTarget = null
            return
        }
        val dest = first.tile
        walkTo(dest, false)
        script.delayUntil(STEP_MS) { localPlayer.tile == dest }
        // ONLY a single cardinal step failing is evidence about the tile. A bulk run failing means the server
        // would not deliver that run in one action, which says nothing about any tile in it.
        if (localPlayer.tile == dest) undeliverable() -= dest.x to dest.y
        else if (localPlayer.tile == here) undeliverable() += dest.x to dest.y
    }

    private fun barrierAt(tile: Tile): SceneObject? =
        barriers().firstOrNull { it.tile.x == tile.x && it.tile.y == tile.y }



    // Adjacency is the only position from which an interact is trustworthy here. A tile the server has already
    // refused from is not adjacency in any useful sense - a wall edge sits between - so it forces a gate step.
    private fun atGoal(goal: Goal): Boolean {
        val here = localPlayer.tile
        if ((here.x to here.y) in refusedFrom) return false
        val gap = abs(here.x - goal.tile.x) + abs(here.y - goal.tile.y)
        return if (goal.stepOn) gap == 0 else gap <= 1
    }

    private fun goal(session: DungeonSession, script: Script): Goal? {
        // The lever only matters while something is still locked behind it. Gating the whole solver on it meant
        // a room whose switch was already spent had no goal at all - so it stood down and handed the exit back
        // to the navigator, which cannot cross barriers. Leaving the room is still this solver's job.
        if (!armed && chest() != null) {
            lever()?.takeIf { pulls < PULL_LIMIT }?.let { return Goal(it.tile, false) { pull(script, it) } }
        }
        // The chest solves the room in BOTH states - it stops the toxin and clears the obstacles - so it is
        // the objective whether or not the gas is running. The router already minimises gate crossings to it.
        // Only once there is no chest left to open does leaving become the objective.
        val key = if (gassed()) null else floorKey()
        if (key != null) return Goal(key.tile, true) { take(script, key.tile) }
        chest()?.let { chest -> return Goal(chest.tile, false) { open(script, chest) } }
        exitDoor()?.let { door ->
            // Both exits were retired by refusals earned while the room was gassed and the navigator was
            // walking at them wrongly. Those say nothing about the route we are about to take through the
            // barriers, so drop them once before targeting the way out.
            if (!clearedForExit) {
                clearedForExit = true
                DungeonNavigator.forgetRefusedDoors()
            }
            return Goal(door.tile, false) { cross(script, door) }
        }
        return null
    }

    // The server says it outright when the switch is pulled: "both the chest in the centre of the room and the
    // door NEXT TO YOU unlock". The switch's own tile is therefore the anchor for the exit - not the door the
    // router would prefer, which is on whichever wall we happen to be heading for and may still be locked.
    private fun exitDoor(): SceneObject? {
        objectsInRoom(RANGE).firstOrNull { it.id == MAZE_EXIT_DOOR && it.hasOption("Enter") }?.let { return it }
        val anchor = switchTile ?: return null
        return objectsInRoom(RANGE)
            .filter { it.hasOption("Enter") }
            .minByOrNull { it.tile.getDistance(anchor) }
    }

    private suspend fun cross(script: Script, door: SceneObject) {
        val room = roomOf(localPlayer.tile)
        if (door.interact("Enter")) {
            script.waitUntilNotMoving()
            script.delayUntil(3400L) { roomOf(localPlayer.tile) != room }
        }
    }

    private suspend fun pull(script: Script, lever: SceneObject) {
        if (lever.interact("Pull")) {
            pulls++
            switchTile = lever.tile
            script.waitUntilNotMoving()
            // Re-entering a room whose switch was already pulled answers "The switch has already been
            // triggered." - the barriers are open and the door beside it is unlocked, so that is armed too.
            // Reading only the first-pull wording left a re-entered room pulling a dead lever forever.
            val confirmed = { RecentChat.containsRecent("press the switch") || RecentChat.containsRecent("already been triggered") }
            script.delayUntil(4200L) { confirmed() }
            armed = confirmed()
            if (armed) println("DUNG-MAZE: switch pulled - running the maze before the toxin lands")
        }
    }

    private suspend fun take(script: Script, tile: Tile) {
        if (DungeonNavigator.pickUpGroundItemAt(script, tile.x, tile.y))
            script.delayUntil(3500L) { floorKey() == null }
    }

    private suspend fun open(script: Script, chest: SceneObject) {
        val at = System.currentTimeMillis()
        if (chest.interact("Open")) {
            script.waitUntilNotMoving()
            script.delayUntil(4000L) { chest() == null }
        }
        // The chest sits behind gates the client's collision map thinks are open, so the server answers the
        // interact with "you can't reach that" and nothing changes. Claiming success on the timeout hid that.
        if (chest() == null) {
            solvedRoom = roomOf(localPlayer.tile)
            println("DUNG-MAZE: centre chest opened - toxin stopped")
        } else {
            // "You can't reach that" means a gate lies between us that the collision map never knew about.
            // Bar this standing tile so the router inserts a gate step instead of re-firing from here.
            val refused = DungeonRefusals.unreachableSince(at)
            if (refused != null) refusedFrom += localPlayer.tile.x to localPlayer.tile.y
        }
    }

    private suspend fun mine(script: Script, rubble: SceneObject) {
        val tile = rubble.tile
        if (rubble.interact("Mine")) {
            script.waitUntilNotMoving()
            script.delayUntil(9000L) { pedestals().none { it.tile == tile } }
        }
    }

    // Losing a timed race proves the maze is not worth re-running, not that the room cannot be crossed, so an
    // ordinary give-up only stops this solver and leaves the room to normal routing.
    //
    // A gassed room is the exception and must still be written off: the toxin never turns back off, damages
    // through the whole room, and keeps damaging after its visual clears - so "let the death counter decide"
    // means paying three deaths and the floor's XP to learn what the Fountains already said. Observed live.
    // Stands this solver down for the visit and nothing more. A maze is never written off: flooded or fresh,
    // the chest still solves it, so the room stays a legitimate target and a later visit gets a fresh attempt.
    private fun abandon(session: DungeonSession, reason: String) {
        abandoned = true
        println("DUNG-MAZE: standing down in ${session.currentCell} - $reason; the room stays open to retry")
    }

    private fun inRoom(tile: Tile, baseX: Int, baseY: Int) =
        tile.x >= baseX && tile.x < baseX + ROOM_TILES && tile.y >= baseY && tile.y < baseY + ROOM_TILES

    private fun wallDelta(rotation: Int): Pair<Int, Int> = when (rotation and 0x3) {
        0 -> -1 to 0
        1 -> 0 to 1
        2 -> 1 to 0
        else -> 0 to -1
    }

    private fun roomOf(tile: Tile): Pair<Int, Int> = tile.x / ROOM_TILES to tile.y / ROOM_TILES
}
