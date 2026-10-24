package com.projectx.pathfinder

import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile

/**
 * The walk the server runs for the last walk click, tile by tile. A walk click sends only the clicked tile and the
 * server picks one of the many equally short routes; [WalkMap.serverPath] reproduces its choice, so the way the
 * player faces on every tile of the walk - which is the way a Surge goes - is known before it is walked.
 *
 * The server starts a walk from its tile when the click reaches it: this tick's, or the next tick's along the walk
 * it was already on, so a walk from each is kept until the player's tile shows which.
 */
internal class ServerWalk(private val map: WalkMap, private val clock: () -> Long = System::currentTimeMillis) {

    class Walk(val tiles: List<Tile>, val entry: Direction?)

    /** Where a surge cast now may fire from, and the way it goes. */
    data class Fire(val tile: Tile, val direction: Direction)

    /** [fires] when the walk is known (never empty), else [why]; [hold] while the walk is about to start. */
    class Plan(val fires: List<Fire>?, val why: String?, val hold: Boolean)

    private var walks: List<Walk> = emptyList()
    private var clickedFrom: Tile? = null
    private var clicked: Tile? = null
    private var clickedAt = 0L
    private var hopping = false
    private var clickedInFlight = false

    val click: Tile?
        @Synchronized get() = if (walks.isEmpty()) null else clicked

    @Synchronized
    fun clicked(player: Tile, click: Tile) {
        val before = walks
        walks = emptyList()
        clickedFrom = player
        clicked = click
        clickedAt = clock()
        clickedInFlight = hopping
        val next = ArrayList<Walk>()
        add(next, player, click, entryOn(before, player))
        for (walk in before) {
            val i = walk.tiles.indexOf(player)
            if (i < 0) continue
            for (k in 1..TICK_STEPS) {
                if (i + k >= walk.tiles.size) break
                add(next, walk.tiles[i + k], click, stepInto(walk, i + k))
            }
        }
        walks = next
    }

    /** Something other than a walk click has replaced the walk queue. */
    @Synchronized
    fun forget() {
        walks = emptyList()
        clicked = null
    }

    @Synchronized
    fun hopCast() {
        hopping = true
        clickedInFlight = false
    }

    /** The hop landed on [landing], facing [facing]: a walk clicked while it was in the air goes from the landing. */
    @Synchronized
    fun hopSettled(landing: Tile?, facing: Direction?) {
        val target = clicked
        val again = hopping && clickedInFlight
        hopping = false
        clickedInFlight = false
        if (!again || landing == null || target == null) return
        val next = ArrayList<Walk>()
        add(next, landing, target, facing)
        walks = next
        clickedFrom = landing
        clickedAt = clock()
    }

    /** Every tile a surge cast now may fire from, up to [reach] steps on, with the way it would go. */
    @Synchronized
    fun fires(player: Tile, facing: Direction?, reach: Int = TICK_STEPS): Plan {
        if (walks.isEmpty()) return Plan(null, "no walk predicted", false)
        if (player == clickedFrom) {
            return Plan(null, "the walk to $clicked has not started", clock() - clickedAt < START_WAIT_MS)
        }
        val out = ArrayList<Fire>()
        for (walk in walks) {
            val i = walk.tiles.indexOf(player)
            if (i < 0) continue
            val last = walk.tiles.size - 1
            for (k in 0..minOf(reach, TICK_STEPS)) {
                val j = minOf(i + k, last)
                val direction = (if (j == 0) walk.entry ?: facing else stepInto(walk, j))
                    ?: return Plan(null, "the facing at ${walk.tiles[j]} is unknown", false)
                val fire = Fire(walk.tiles[j], direction)
                if (fire !in out) out += fire
            }
        }
        if (out.isEmpty()) return Plan(null, "$player is off the predicted walk to $clicked", false)
        return Plan(out, null, false)
    }

    /** The tiles a hop cast now may fire from: the player's and up to a tick's steps on along each walk. */
    @Synchronized
    fun ahead(player: Tile): List<Tile>? {
        val out = ArrayList<Tile>()
        for (walk in walks) {
            val i = walk.tiles.indexOf(player)
            if (i < 0) continue
            for (k in 0..TICK_STEPS) {
                val tile = walk.tiles[minOf(i + k, walk.tiles.size - 1)]
                if (tile !in out) out += tile
            }
        }
        return out.ifEmpty { null }
    }

    private fun add(into: MutableList<Walk>, from: Tile, click: Tile, entry: Direction?) {
        if (into.any { it.tiles.first() == from }) return
        map.serverPath(from, click)?.let { into += Walk(it, entry) }
    }

    private fun entryOn(before: List<Walk>, player: Tile): Direction? {
        for (walk in before) {
            val i = walk.tiles.indexOf(player)
            if (i > 0) return stepInto(walk, i)
            if (i == 0 && walk.entry != null) return walk.entry
        }
        return null
    }

    private fun stepInto(walk: Walk, j: Int): Direction? {
        val a = walk.tiles[j - 1]
        val b = walk.tiles[j]
        return Steps.of(b.x - a.x, b.y - a.y)
    }

    companion object {
        /** Steps a running tick moves the player; a surge fires at most this far on. */
        const val TICK_STEPS = 2

        /** A walk not started this long after its click (two ticks) is not waited for. */
        const val START_WAIT_MS = 1_300L
    }
}
