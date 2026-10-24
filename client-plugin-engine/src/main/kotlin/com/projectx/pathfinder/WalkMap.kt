package com.projectx.pathfinder

import com.projectx.pathfinder.hops.Hops
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import kotlin.math.ceil
import kotlin.math.max

/** Walking distances from one start tile, as the collision stood when it was searched. */
internal class WalkField(private val window: SearchWindow, val start: Tile, val maxDepth: Int, private val dist: IntArray) {

    /** Steps from the start; [WalkMap.UNREACHABLE] when no walk within [maxDepth] gets there, [WalkMap.UNKNOWN] off the window. */
    fun distanceTo(x: Int, y: Int, plane: Int): Int {
        if (plane != window.plane || !window.contains(x, y)) return WalkMap.UNKNOWN
        val d = dist[window.index(x, y)]
        return if (d < 0) WalkMap.UNREACHABLE else d
    }

    fun distanceTo(tile: Tile): Int = distanceTo(tile.x, tile.y, tile.plane)

    fun reaches(tile: Tile): Boolean = distanceTo(tile) >= 0

    /** Steps to the nearest tile a [sizeX] x [sizeY] footprint is used from: one of its own tiles or an orthogonal neighbour. */
    fun distanceToFootprint(southWestX: Int, southWestY: Int, plane: Int, sizeX: Int, sizeY: Int): Int {
        if (plane != window.plane) return WalkMap.UNKNOWN
        var best = Int.MAX_VALUE
        var any = false
        for (x in southWestX - 1..southWestX + sizeX) {
            for (y in southWestY - 1..southWestY + sizeY) {
                val corner = (x == southWestX - 1 || x == southWestX + sizeX) && (y == southWestY - 1 || y == southWestY + sizeY)
                if (corner) continue
                val d = distanceTo(x, y, plane)
                if (d == WalkMap.UNKNOWN) continue
                any = true
                if (d in 0 until best) best = d
            }
        }
        return when {
            best != Int.MAX_VALUE -> best
            any -> WalkMap.UNREACHABLE
            else -> WalkMap.UNKNOWN
        }
    }
}

/**
 * Walking questions answered on a collision map: how far is that tile by walking, is this door open, which tile
 * would the server walk through. Every answer first asks [trusted]; an untrusted tile answers [UNKNOWN] and the
 * caller falls back to what it would do without a map.
 */
internal open class WalkMap(
    private val steps: () -> StepRule,
    private val trust: (x: Int, y: Int, plane: Int) -> Boolean = { _, _, _ -> true },
) {
    constructor(rule: StepRule, trust: (x: Int, y: Int, plane: Int) -> Boolean = { _, _, _ -> true }) : this({ rule }, trust)

    open fun trusted(tile: Tile): Boolean = trust(tile.x, tile.y, tile.plane)

    fun rule(): StepRule = steps()

    open fun hopRule(): StepRule = steps()

    fun canStep(x: Int, y: Int, plane: Int, direction: Direction): Boolean = steps().canStep(x, y, plane, direction)

    /** Trusted, and some step leads off it. */
    fun walkable(tile: Tile): Boolean {
        if (!trusted(tile)) return false
        val rule = steps()
        return Steps.SERVER_ORDER.any { rule.canStep(tile.x, tile.y, tile.plane, it) }
    }

    private class Cached(val field: WalkField, val at: Long)

    @Volatile
    private var newest: Cached? = null

    @Volatile
    private var older: Cached? = null

    /** Distances from [from] out to [maxDepth] steps, or null when [from] is not trusted. */
    fun field(from: Tile, maxDepth: Int = DEFAULT_MAX_DEPTH): WalkField? {
        if (!trusted(from)) return null
        val now = System.currentTimeMillis()
        val depth = maxDepth.coerceIn(1, WINDOW_RADIUS)
        for (cached in arrayOf(newest, older)) {
            if (cached != null && cached.field.start == from && cached.field.maxDepth >= depth && now - cached.at < FIELD_TTL_MS) {
                return cached.field
            }
        }
        val window = SearchWindow.around(from.x, from.y, from.plane, depth)
        val field = WalkField(window, from, depth, GridSearch.fill(steps(), window, from.x, from.y, depth))
        older = newest
        newest = Cached(field, now)
        return field
    }

    /** Steps from [from] to [to]; [UNREACHABLE] beyond [maxDepth], [UNKNOWN] when either is untrusted or they differ in plane. */
    fun distance(from: Tile, to: Tile, maxDepth: Int): Int {
        if (from.plane != to.plane || !trusted(from) || !trusted(to)) return UNKNOWN
        val radius = max(from.getLongestDelta(to), maxDepth.coerceAtMost(WINDOW_RADIUS)).coerceAtMost(WINDOW_RADIUS)
        val window = SearchWindow.around(from.x, from.y, from.plane, radius)
        if (!window.contains(to.x, to.y)) return UNKNOWN
        val d = GridSearch.distance(steps(), window, from, to, maxDepth)
        return if (d < 0) UNREACHABLE else d
    }

    /** The tiles the server walks for a walk click on [to] from [from], [from] first; null when unknown. */
    fun serverPath(from: Tile, to: Tile): List<Tile>? {
        if (from.plane != to.plane || !trusted(from) || !trusted(to)) return null
        return GridSearch.serverPath(steps(), SearchWindow.around(from.x, from.y, from.plane, SERVER_PATH_RADIUS), from, to)
    }

    /** Whether a walk joins [a] and [b] in at most [maxSteps]: a door is open when its two sides do. Null when unknown. */
    fun passage(a: Tile, b: Tile, maxSteps: Int): Boolean? {
        val d = distance(a, b, maxSteps)
        return if (d == UNKNOWN) null else d >= 0
    }

    companion object {
        const val UNREACHABLE = -1
        const val UNKNOWN = -2
        const val DEFAULT_MAX_DEPTH = 96
        const val WINDOW_RADIUS = 96
        private const val SERVER_PATH_RADIUS = 64
        private const val FIELD_TTL_MS = 150L
    }
}

/**
 * The live map: [WorldCollision] with the scene's door changes applied. Untrusted inside an instance, on a tile
 * whose zone was never loaded, and while the map catches up after a teleport.
 */
internal object LiveWalkMap : WalkMap(Steps::live) {
    private const val INSTANCE_MIN_X = 6400

    // A jump this far, plus a running tick's two tiles for every tick between observations, is a teleport: the
    // observations can be seconds apart while a node waits out a leg, and a surge or dive moves ten.
    private const val TELEPORT_JUMP_TILES = 12
    private const val RUN_TILES_PER_TICK = 2.0
    private const val TICK_MS = 600.0
    private const val ARRIVAL_MAX_MS = 5_000L
    private const val ARRIVAL_SAMPLE = 32

    @Volatile
    private var lastObserved: Tile? = null

    @Volatile
    private var lastObservedAt = 0L

    @Volatile
    private var arrivingSince = 0L

    @Volatile
    var lastTeleportAt = 0L
        private set

    fun observe(player: Tile) {
        val now = System.currentTimeMillis()
        val previous = lastObserved
        val previousAt = lastObservedAt
        lastObserved = player
        lastObservedAt = now
        if (previous == null) return
        val allowed = TELEPORT_JUMP_TILES + RUN_TILES_PER_TICK * ceil((now - previousAt) / TICK_MS)
        val jump = previous.getLongestDelta(player)
        if (previous.plane != player.plane && jump > TELEPORT_JUMP_TILES || jump > allowed) {
            arrivingSince = now
            lastTeleportAt = now
        }
    }

    fun arriving(): Boolean {
        val since = arrivingSince
        if (since == 0L) return false
        val player = lastObserved ?: return true
        if (System.currentTimeMillis() - since < ARRIVAL_MAX_MS && !loadedAround(player)) return true
        arrivingSince = 0L
        return false
    }

    private fun loadedAround(player: Tile): Boolean =
        listOf(0 to 0, ARRIVAL_SAMPLE to 0, -ARRIVAL_SAMPLE to 0, 0 to ARRIVAL_SAMPLE, 0 to -ARRIVAL_SAMPLE).all { (dx, dy) ->
            WorldCollision.getFlags(player.x + dx, player.y + dy, player.plane) != -1
        }

    override fun hopRule(): StepRule = Hops.knowledge.rule(rule(), ::trusted)

    override fun trusted(tile: Tile): Boolean =
        tile.x in 1 until INSTANCE_MIN_X && !WorldCollision.inDynamic && WorldCollision.getFlags(tile.x, tile.y, tile.plane) != -1 && !arriving()
}
