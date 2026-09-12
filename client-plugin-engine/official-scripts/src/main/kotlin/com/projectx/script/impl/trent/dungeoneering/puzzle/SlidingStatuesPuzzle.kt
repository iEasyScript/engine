package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import com.projectx.util.gaussian
import world.gregs.voidps.type.Tile
import kotlin.math.abs

/**
 * Daemonheim "sliding statues": the room is split into four corner carpets, stationary statues on the north
 * pair, movable ones on the south pair, and the south arrangement must DUPLICATE the north one.
 *
 * Because the carpets sit in the same columns, that reduces to two rules with no geometry to guess at:
 *  - a movable statue must end up in the same COLUMN as its stationary counterpart;
 *  - the row offsets between statues must match, so the south group's spacing copies the north group's.
 *
 * Counterparts are paired by kind first — a statue only ever matches one of its own model, since the room
 * holds two of each — and then by column order within that kind, which keeps west with west and east with
 * east. Nothing here depends on carpet bounds, statue ids, or which theme the floor rolled.
 *
 * Movement: a statue slides one tile per interaction. Pull draws it one tile toward the player, so the player
 * stands TWO tiles out and the destination between them stays clear; push drives it one tile away and is the
 * fallback. A statue will not enter an occupied tile — the interaction animates and nothing moves — so a
 * blocked step is skipped rather than repeated.
 */
object SlidingStatuesPuzzle {

    private const val NAME = "Statue"
    private const val PUSH = "Push"
    private const val PULL = "Pull"
    private const val RANGE = 20

    // Interactions that move nothing before the room is handed back. A statue that will not budge from either
    // side is one the plan cannot actually reach — grinding it forever holds the bot in a room it can never
    // finish, which is worse than leaving the puzzle unsolved and routing on.
    private const val GIVE_UP_ATTEMPTS = 12

    // Rows either side of the group's current position to consider anchoring the copy at.
    private const val BASE_SEARCH = 8

    private var lastPlan: String? = null
    private var lastArrangement: String? = null
    private var failedAttempts = 0
    private var chosenBase: Int? = null
    private var roomSignature: String? = null

    // Tiles a statue has demonstrably refused to enter with the destination verifiably empty — the carpet's
    // real edge, learned by trying. Kept for the life of the room: it is geometry, not a transient block.
    private val statueBlocked = HashSet<Pair<Int, Int>>()

    // Stances the avatar asked for and never arrived at. A pull stance sits two tiles out and can simply be
    // off the walkable carpet, and re-picking it forever is a stall: without this the nearer-side preference
    // keeps choosing the one tile we cannot stand on instead of pushing from the other side.
    private val unreachableStances = HashSet<Pair<Int, Int>>()

    private fun statues() = npcsInRoom(RANGE) { it.name == NAME }
    private fun movable(): List<NPC> = statues().filter { it.hasOption(PUSH) || it.hasOption(PULL) }
    private fun stationary(): List<NPC> = statues().filter { !it.hasOption(PUSH) && !it.hasOption(PULL) }

    private fun kind(npc: NPC): Int = npc.getDef().modelIds?.firstOrNull() ?: -1

    // Only another statue is a wall. The player also blocks a slide, but it is the one occupant this solver
    // can move, so counting it here turns "step aside" into "this puzzle has no legal move" — which is a
    // silent, permanent stall, because the room keeps owning the bot loop while doing nothing.
    private fun blocked(tile: Tile) = npcsInRoom(RANGE) { it.tile == tile }.isNotEmpty()

    private class Requirement(val statue: NPC, val column: Int, val rowOffset: Int)

    /** Each movable statue's counterpart column and the row offset that copying the north group demands. */
    private fun requirements(): List<Requirement> {
        val south = movable()
        val north = stationary()
        if (south.isEmpty() || north.isEmpty()) return emptyList()

        val northBase = north.minOf { it.tile.y }
        val out = ArrayList<Requirement>()
        for (k in north.map { kind(it) }.distinct()) {
            val fixed = north.filter { kind(it) == k }.sortedBy { it.tile.x }
            val loose = south.filter { kind(it) == k }.sortedBy { it.tile.x }
            fixed.zip(loose).forEach { (f, l) -> out += Requirement(l, f.tile.x, f.tile.y - northBase) }
        }
        return out
    }

    private fun targetsFor(reqs: List<Requirement>, base: Int): Map<NPC, Tile> =
        reqs.associate { it.statue to Tile.of(it.column, base + it.rowOffset, it.statue.tile.plane) }

    private fun moveCost(targets: Map<NPC, Tile>) =
        targets.entries.sumOf { abs(it.key.tile.x - it.value.x) + abs(it.key.tile.y - it.value.y) }

    private fun legClear(from: Tile, to: Tile): Boolean {
        val dx = (to.x - from.x).coerceIn(-1, 1)
        val dy = (to.y - from.y).coerceIn(-1, 1)
        var x = from.x
        var y = from.y
        while (x != to.x || y != to.y) {
            x += dx
            y += dy
            if ((x to y) in statueBlocked) return false
        }
        return true
    }

    // A statue slides one cardinal tile at a time, so it has to walk an L. Either leg order will do.
    private fun pathClear(from: Tile, to: Tile): Boolean {
        val viaX = Tile.of(to.x, from.y, from.plane)
        val viaY = Tile.of(from.x, to.y, from.plane)
        return (legClear(from, viaX) && legClear(viaX, to)) || (legClear(from, viaY) && legClear(viaY, to))
    }

    private fun attainable(targets: Map<NPC, Tile>): Boolean {
        val fixedTiles = stationary().map { it.tile.x to it.tile.y }.toSet()
        return targets.all { (statue, target) ->
            (target.x to target.y) !in statueBlocked && (target.x to target.y) !in fixedTiles &&
                pathClear(statue.tile, target)
        }
    }

    /**
     * The win condition is that the movable group DUPLICATES the stationary group's relative arrangement, so
     * every translation reproducing those offsets is a valid solve and the group's own lowest row is only one
     * of them. Anchoring there unconditionally is what made this puzzle read as unsolvable: it is the cheapest
     * translation, and on a floor where it lands a statue past the carpet edge it is also the one that cannot
     * be finished. Search the translations instead, cheapest first, and take the first every statue can reach.
     */
    private fun plan(): Map<NPC, Tile> {
        val reqs = requirements()
        if (reqs.isEmpty()) return emptyMap()

        val signature = stationary().sortedBy { it.tile.x }.joinToString(",") { "${it.tile.x},${it.tile.y}" }
        if (signature != roomSignature) {
            roomSignature = signature
            statueBlocked.clear()
            chosenBase = null
        }
        chosenBase?.let { base ->
            val held = targetsFor(reqs, base)
            if (attainable(held)) return held
        }
        val here = reqs.minOf { it.statue.tile.y }
        val best = (here - BASE_SEARCH..here + BASE_SEARCH)
            .map { it to targetsFor(reqs, it) }
            .filter { attainable(it.second) }
            .minByOrNull { moveCost(it.second) }
            ?: return emptyMap()
        chosenBase = best.first
        return best.second
    }

    private fun offTarget(plan: Map<NPC, Tile>) =
        plan.entries.filter { it.key.tile.x != it.value.x || it.key.tile.y != it.value.y }

    fun present(): Boolean = failedAttempts < GIVE_UP_ATTEMPTS && offTarget(plan()).isNotEmpty()

    private fun arrangement() = movable().sortedBy { it.id }.joinToString(",") { "${it.id}@${it.tile.x},${it.tile.y}" }

    suspend fun solve(script: Script) {
        val arrangement = arrangement()
        if (arrangement != lastArrangement) {
            lastArrangement = arrangement
            failedAttempts = 0
            unreachableStances.clear()
        }
        val plan = plan()
        val summary = plan.entries.sortedBy { it.key.id }
            .joinToString(" ") { "${it.key.id}@${it.key.tile.x},${it.key.tile.y}->${it.value.x},${it.value.y}" }
        if (summary != lastPlan) {
            println("DUNG: sliding statues plan $summary")
            lastPlan = summary
        }

        val remaining = offTarget(plan)
        val choice = remaining.firstNotNullOfOrNull { entry ->
            nextStep(entry.key.tile, entry.value)?.let { entry.key to it }
        }
        if (choice == null) {
            script.delay(700, 180)
            return
        }
        val (statue, step) = choice
        val dest = Tile.of(statue.tile.x + step.first, statue.tile.y + step.second, statue.tile.plane)

        // Either option produces the same one-tile slide, so take whichever side the player is already nearer
        // to. Push stands directly behind; pull stands beyond the destination so the tile the statue is moving
        // into stays clear.
        val pushFrom = Tile.of(statue.tile.x - step.first, statue.tile.y - step.second, statue.tile.plane)
        val pullFrom = Tile.of(statue.tile.x + step.first * 2, statue.tile.y + step.second * 2, statue.tile.plane)
        val canPush = !blocked(pushFrom) && (pushFrom.x to pushFrom.y) !in unreachableStances
        val canPull = !blocked(pullFrom) && (pullFrom.x to pullFrom.y) !in unreachableStances
        if (!canPush && !canPull) {
            failedAttempts++
            script.delay(700, 180)
            return
        }
        val usePush = canPush && (!canPull ||
            localPlayer.tile.getDistance(pushFrom) <= localPlayer.tile.getDistance(pullFrom))
        val stand = if (usePush) pushFrom else pullFrom
        val op = if (usePush) PUSH else PULL

        val before = statue.tile
        if (localPlayer.tile != stand) {
            walkTo(stand, false)
            script.waitUntilNotMoving()
            // Arrival, not "stopped moving" — the latter reads true between waypoints and let the interaction
            // fire from the destination tile mid-walk, which is how the player came to be blocking the slide.
            script.delayUntil(gaussian(2400L, 600L)) { localPlayer.tile == stand }
            if (localPlayer.tile != stand) {
                unreachableStances += stand.x to stand.y
                script.delay(420, 120)
                return
            }
        }
        if (localPlayer.tile == dest) {
            script.delay(420, 120)
            return
        }
        statue.interact(op)
        script.waitUntilNotMoving()
        script.delayUntil(gaussian(2600L, 600L)) {
            movable().firstOrNull { it.id == statue.id }?.tile?.let { it != before } == true
        }
        if (movable().firstOrNull { it.id == statue.id }?.tile == before) {
            // Destination was empty and we were clear of it, so the tile itself is the carpet's edge. Learn it
            // and let plan() re-anchor onto a translation that does not need it.
            failedAttempts++
            statueBlocked += dest.x to dest.y
            chosenBase = null
        }
        script.delay(320, 100)
    }

    /** One cardinal step toward the target, or null if every remaining direction holds another statue. */
    private fun nextStep(from: Tile, to: Tile): Pair<Int, Int>? {
        val options = buildList {
            if (to.x != from.x) add((if (to.x > from.x) 1 else -1) to 0)
            if (to.y != from.y) add(0 to (if (to.y > from.y) 1 else -1))
        }
        return options.firstOrNull { !blocked(Tile.of(from.x + it.first, from.y + it.second, from.plane)) }
    }
}
