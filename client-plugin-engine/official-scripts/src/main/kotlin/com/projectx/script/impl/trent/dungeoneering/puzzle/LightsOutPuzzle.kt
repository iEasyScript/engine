package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.script.Script
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import com.projectx.script.impl.trent.dungeoneering.DungeonSession
import com.projectx.util.gaussian
import world.gregs.voidps.type.Tile

/**
 * Daemonheim "lights out" tile room: a grid of green/yellow tiles whose door stays frozen until every tile
 * shows the same colour. Pressing a tile flips a small neighbourhood around it.
 *
 * The board is solved as a linear system over GF(2) and then executed as a whole. Two properties of these
 * boards drive the shape of this code:
 *
 * - The flip mask is LEARNED, never assumed, and only an INTERIOR press teaches it. A press on an edge tile
 *   cannot flip the neighbour that isn't there, so its observation is a truncated subset of the real shape,
 *   and a solve run against a truncated mask is a solve of a board that does not exist.
 * - The grid is edge-clipped rather than toroidal, which leaves the matrix rank-deficient (a 5x5 plus board
 *   has a two-dimensional null space). That does not make the board unsolvable - it means the solution is
 *   not unique, so the null space is enumerated and the cheapest solution is taken.
 *
 * Presses are only ever made from a computed solution. An inconsistent system means the MODEL is wrong, not
 * that the board needs poking, so the response is to re-learn the mask rather than press something arbitrary.
 */
object LightsOutPuzzle {

    // Matched on the cache NAME, not on ids: the same room ships under five themes with different ids, and a
    // new theme would silently stop matching an id list while the names stay put.
    private const val GREEN = "Green tile"
    private const val YELLOW = "Yellow tile"
    private const val IMBUE = "Imbue"
    private const val RANGE = 20
    private const val CONTRADICTION_LIMIT = 4
    private const val NULL_SPACE_CAP = 12
    private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    private var mask: List<Pair<Int, Int>>? = null
    private var maskFromInterior = false
    private var pressByInteract = false
    private var plan: List<Tile> = emptyList()
    private var lastPressed: Tile? = null
    private var boardBeforePress: Map<Tile, Boolean>? = null
    private var predicted: Map<Tile, Boolean>? = null
    private var contradictions = 0
    private var attemptCell: Pair<Int, Int>? = null

    private fun tileObjects() = objectsInRoom(RANGE).filter { it.name() == GREEN || it.name() == YELLOW }

    private fun tiles(): Map<Tile, Boolean> = tileObjects().associate { it.tile to (it.name() == GREEN) }

    private fun solved(grid: Map<Tile, Boolean>) = grid.values.toSet().size <= 1

    /**
     * The tiles remain in the room after the board is finished, so matching on their mere presence would keep
     * this handler owning a solved room forever and the navigator would never get its turn to walk out.
     */
    fun present(session: DungeonSession): Boolean {
        if (session.currentCell != null && session.currentCell in session.bannedCells) return false
        return tiles().let { it.isNotEmpty() && !solved(it) }
    }

    suspend fun solve(session: DungeonSession, script: Script) {
        if (session.currentCell != attemptCell) {
            attemptCell = session.currentCell
            forget()
        }
        val grid = tiles()
        if (grid.isEmpty() || solved(grid)) {
            plan = emptyList()
            predicted = null
            return
        }

        val shape = mask
        if (shape == null || !maskFromInterior) {
            learnMask(script, grid)
            return
        }

        predicted?.let { expected ->
            predicted = null
            if (expected != grid) {
                println("DUNG: lights-out board diverged from the model - re-solving")
                widenMask(boardBeforePress, grid, lastPressed)
                plan = emptyList()
                if (++contradictions >= CONTRADICTION_LIMIT) {
                    session.ban(session.currentCell, "lights-out model wrong $contradictions times (mask=$mask)")
                    return
                }
            }
        }

        if (plan.isEmpty()) {
            val solution = planFor(grid, shape)
            if (solution == null) {
                println("DUNG: lights-out has no consistent solution for either colour - re-learning the flip shape")
                maskFromInterior = false
                mask = null
                return
            }
            plan = walkOrder(solution)
            println("DUNG: lights-out solved in ${plan.size} presses (mask=$shape)")
        }

        val next = plan.first()
        plan = plan.drop(1)
        lastPressed = next
        boardBeforePress = grid
        predicted = flip(grid, next, shape)
        press(script, next, grid)
    }

    private fun forget() {
        mask = null
        maskFromInterior = false
        plan = emptyList()
        lastPressed = null
        boardBeforePress = null
        predicted = null
        contradictions = 0
    }

    /**
     * Probe a tile with all four neighbours present so the observation is the whole shape rather than a
     * corner-truncated piece of it. Falls back to any tile only when the grid is too thin to have an interior.
     */
    private suspend fun learnMask(script: Script, grid: Map<Tile, Boolean>) {
        val interior = grid.keys.filter { tile ->
            NEIGHBOURS.all { (dx, dy) -> grid.containsKey(Tile.of(tile.x + dx, tile.y + dy, tile.plane)) }
        }
        val probe = nearest(interior.ifEmpty { grid.keys }) ?: return
        press(script, probe, grid)
        val after = tiles()
        val flipped = after.keys.filter { grid[it] != null && grid[it] != after[it] }
        if (flipped.isEmpty()) {
            println("DUNG: lights-out probe at (${probe.x},${probe.y}) changed nothing - trigger still unknown")
            return
        }
        mask = flipped.map { it.x - probe.x to it.y - probe.y }
        maskFromInterior = probe in interior
        println("DUNG: lights-out flip shape = $mask (interior=$maskFromInterior at ${probe.x},${probe.y})")
    }

    // Every observation is a subset of the true shape, so corrections only ever add offsets.
    private fun widenMask(before: Map<Tile, Boolean>?, after: Map<Tile, Boolean>, origin: Tile?) {
        if (before == null || origin == null) return
        val flipped = after.keys.filter { before[it] != null && before[it] != after[it] }
        val merged = mask.orEmpty().toSet() + flipped.map { it.x - origin.x to it.y - origin.y }
        if (merged != mask?.toSet()) {
            mask = merged.toList()
            println("DUNG: lights-out mask widened to $mask")
        }
    }

    internal fun flip(grid: Map<Tile, Boolean>, press: Tile, mask: List<Pair<Int, Int>>): Map<Tile, Boolean> {
        val out = grid.toMutableMap()
        mask.forEach { (dx, dy) ->
            val t = Tile.of(press.x + dx, press.y + dy, press.plane)
            out[t]?.let { out[t] = !it }
        }
        return out
    }

    /** The cheapest press set that makes the whole board one colour; null when neither colour is reachable. */
    internal fun planFor(grid: Map<Tile, Boolean>, mask: List<Pair<Int, Int>>): List<Tile>? {
        val order = grid.keys.toList()
        val index = order.withIndex().associate { (i, t) -> t to i }
        var best: List<Tile>? = null
        for (targetGreen in listOf(true, false)) {
            val rows = order.map { tile ->
                val row = BooleanArray(order.size + 1)
                mask.forEach { (dx, dy) ->
                    index[Tile.of(tile.x + dx, tile.y + dy, tile.plane)]?.let { row[it] = !row[it] }
                }
                row[order.size] = grid.getValue(tile) != targetGreen
                row
            }
            val presses = cheapest(rows, order.size)?.let { picks ->
                order.filterIndexed { i, _ -> picks[i] }
            } ?: continue
            if (best == null || presses.size < best.size) best = presses
        }
        return best
    }

    /**
     * Gaussian elimination over GF(2), then a sweep of the null space for the smallest press set. The system
     * is under-determined on these boards, and the free-variables-zero solution it falls out with is routinely
     * far from the shortest - every extra press is another walk across the room.
     */
    private fun cheapest(rows: List<BooleanArray>, width: Int): BooleanArray? {
        val m = rows.map { it.copyOf() }.toMutableList()
        val pivotOf = IntArray(width) { -1 }
        var row = 0
        for (col in 0 until width) {
            val pivot = (row until m.size).firstOrNull { m[it][col] } ?: continue
            val tmp = m[row]; m[row] = m[pivot]; m[pivot] = tmp
            for (r in m.indices) {
                if (r != row && m[r][col]) {
                    for (c in col..width) m[r][c] = m[r][c] != m[row][c]
                }
            }
            pivotOf[col] = row
            row++
        }
        if (m.any { r -> (0 until width).none { r[it] } && r[width] }) return null

        val particular = BooleanArray(width) { col -> pivotOf[col] >= 0 && m[pivotOf[col]][width] }
        val free = (0 until width).filter { pivotOf[it] < 0 }
        if (free.size > NULL_SPACE_CAP) return particular

        val basis = free.map { f ->
            BooleanArray(width).also { v ->
                v[f] = true
                for (col in 0 until width) if (pivotOf[col] >= 0) v[col] = m[pivotOf[col]][f]
            }
        }
        var best = particular
        var bestWeight = particular.count { it }
        for (combo in 1 until (1 shl basis.size)) {
            val candidate = particular.copyOf()
            basis.forEachIndexed { b, vector ->
                if (combo shr b and 1 == 1) for (i in 0 until width) candidate[i] = candidate[i] != vector[i]
            }
            val weight = candidate.count { it }
            if (weight < bestWeight) {
                best = candidate
                bestWeight = weight
            }
        }
        return best
    }

    // Press order is irrelevant over GF(2), so take them nearest-first to keep the walking down.
    private fun walkOrder(presses: List<Tile>): List<Tile> {
        val remaining = presses.toMutableList()
        val ordered = ArrayList<Tile>(remaining.size)
        var from = localPlayer.tile
        while (remaining.isNotEmpty()) {
            val next = remaining.minByOrNull { it.getDistance(from) }!!
            remaining.remove(next)
            ordered += next
            from = next
        }
        return ordered
    }

    /**
     * The interact variant is fired by NAME, not by menu position: these tiles carry Imbue/Force/Examine, so
     * an index-0 press only happens to be the right one while Imbue stays first in the list. The wait spans
     * the walk as well as the flip - an interact on the far side of the grid walks the avatar there first,
     * and a window sized for just the flip expires mid-walk and reads as "nothing happened".
     */
    private suspend fun press(script: Script, target: Tile, before: Map<Tile, Boolean>): Boolean {
        if (pressByInteract) {
            tileObjects().firstOrNull { it.tile == target }?.interact(IMBUE) ?: return false
            script.waitUntilNotMoving()
        } else {
            walkTo(target, false)
            script.delayUntil(gaussian(4200L, 900L)) { localPlayer.tile == target || tiles() != before }
            if (tiles() == before) {
                // Arriving changed nothing, so the trigger is the interact variant of this room, not the step.
                tileObjects().firstOrNull { it.tile == target }?.let {
                    if (it.interact(IMBUE)) {
                        pressByInteract = true
                        script.waitUntilNotMoving()
                    }
                }
            }
        }
        script.delayUntil(gaussian(3600L, 800L)) { tiles() != before }
        script.delay(300, 90)
        return true
    }

    private fun nearest(candidates: Collection<Tile>): Tile? =
        candidates.minByOrNull { it.getDistance(localPlayer.tile) }
}
