package com.projectx.webwalker

import com.projectx.pathfinder.navpath.NavGoalSet
import com.projectx.script.api.Area
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max

/** Where a web walk ends: near one tile, or on any tile of a set. */
internal sealed interface WalkGoal {
    /** The tile the hosted planner breaks cost ties towards. */
    val centre: Tile

    /** The furthest east the goal reaches, to tell a goal inside an instance. */
    val maxX: Int

    fun contains(tile: Tile): Boolean

    /** How many tiles short of [tile] the player may stop and still be in the goal. */
    fun marginAt(tile: Tile): Int

    /** The goal set to plan to on `/route_area`, or null when `/route` to [centre] is the request. */
    fun goalSet(from: Tile): NavGoalSet?

    /** Within [distance] tiles of [destination], diagonals counting as one. */
    data class Near(val destination: Tile, val distance: Int) : WalkGoal {
        override val centre: Tile get() = destination
        override val maxX: Int get() = destination.x

        override fun contains(tile: Tile): Boolean = tile.plane == destination.plane && chebyshev(tile, destination) <= distance

        override fun marginAt(tile: Tile): Int =
            if (tile.plane == destination.plane) (distance - chebyshev(tile, destination)).coerceAtLeast(0) else 0

        override fun goalSet(from: Tile): NavGoalSet? {
            if (distance <= 0) return null
            val r = distance.coerceAtMost(MAX_RADIUS)
            return NavGoalSet(WebArea(destination.x - r, destination.x + r, destination.y - r, destination.y + r, destination.plane), emptyList())
        }

        override fun toString(): String = "${destination.x},${destination.y},${destination.plane}"
    }

    /** Standing on any tile of [area] or [tiles]. */
    class Tiles(val area: WebArea?, val tiles: List<Tile>) : WalkGoal {
        private val ids = tiles.mapTo(HashSet()) { it.id }

        val isEmpty: Boolean get() = area == null && tiles.isEmpty()

        override val centre: Tile = when {
            area != null -> Tile.of(area.centreX, area.centreY, area.plane)
            tiles.isNotEmpty() -> tiles[tiles.size / 2]
            else -> Tile.EMPTY
        }

        override val maxX: Int = max(area?.maxX ?: Int.MIN_VALUE, tiles.maxOfOrNull { it.x } ?: Int.MIN_VALUE)

        override fun contains(tile: Tile): Boolean = area?.contains(tile.x, tile.y, tile.plane) == true || tile.id in ids

        override fun marginAt(tile: Tile): Int = 0

        /**
         * The set as `/route_area` takes it. Over the planner's limits it is thinned to its edge, the tiles a walk can
         * enter by, and then to the edge tiles nearest [from]; only a teleport landing in the middle is lost.
         */
        override fun goalSet(from: Tile): NavGoalSet {
            if (area != null && area.size() + tiles.size <= NavGoalSet.MAX_TILES) return NavGoalSet(area, tiles)
            val all = allTiles()
            if (all.size <= NavGoalSet.MAX_TILES) return NavGoalSet(null, all)
            val members = all.mapTo(HashSet(all.size * 2)) { it.id }
            val edge = all.filter { tile -> NEIGHBOURS.any { (dx, dy) -> Tile.of(tile.x + dx, tile.y + dy, tile.plane).id !in members } }
            val entry = if (contains(from) && from !in edge) edge + from else edge
            return NavGoalSet(null, if (entry.size <= NavGoalSet.MAX_TILES) entry else nearestFirst(entry, from).take(NavGoalSet.MAX_TILES))
        }

        /**
         * Tiles for the local planner to try in turn: the nearest tile [standable] allows, then the next nearest a few
         * tiles away from those already picked, so one walled-off corner does not use every try.
         */
        fun candidates(from: Tile, standable: (Tile) -> Boolean): List<Tile> {
            val picked = ArrayList<Tile>(MAX_CANDIDATES)
            for (tile in nearestFirst(allTiles(), from)) {
                if (picked.any { it.plane == tile.plane && chebyshev(it, tile) < CANDIDATE_SPREAD } || !standable(tile)) continue
                picked += tile
                if (picked.size == MAX_CANDIDATES) break
            }
            return picked
        }

        private fun allTiles(): List<Tile> {
            val out = LinkedHashSet<Tile>()
            if (area != null) for (x in area.minX..area.maxX) for (y in area.minY..area.maxY) out += Tile.of(x, y, area.plane)
            out += tiles
            return out.toList()
        }

        override fun toString(): String = listOfNotNull(area?.toString(), tiles.takeIf { it.isNotEmpty() }?.let { "${it.size} tiles" }).joinToString(" + ")

        companion object {
            private const val MAX_CANDIDATES = 4
            private const val CANDIDATE_SPREAD = 4
            private val NEIGHBOURS = listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)

            private fun nearestFirst(tiles: List<Tile>, from: Tile): List<Tile> =
                tiles.sortedWith(compareBy({ it.plane != from.plane }, { chebyshev(it, from) }, { abs(it.x - from.x) + abs(it.y - from.y) }))
        }
    }

    companion object {
        /** The largest square centred on a tile that fits the planner's 64 by 64 goal area. */
        const val MAX_RADIUS = 31

        fun of(area: Area): Tiles {
            if (area is Area.Rectangular) {
                val low = area.getBottomLeft()
                val high = area.getTopRight()
                if (low.plane == high.plane) return Tiles(WebArea(low.x, high.x, low.y, high.y, low.plane), emptyList())
            }
            return Tiles(null, area.getCoordinates())
        }

        fun rectangle(minX: Int, minY: Int, maxX: Int, maxY: Int, plane: Int): Tiles =
            Tiles(WebArea(minOf(minX, maxX), maxOf(minX, maxX), minOf(minY, maxY), maxOf(minY, maxY), plane), emptyList())

        fun tiles(tiles: Collection<Tile>): Tiles = Tiles(null, tiles.distinct())

        fun tiles(xs: IntArray, ys: IntArray, plane: Int): Tiles {
            require(xs.size == ys.size) { "xs and ys differ in length" }
            return tiles(xs.indices.map { Tile.of(xs[it], ys[it], plane) })
        }
    }
}

private fun chebyshev(a: Tile, b: Tile): Int = max(abs(a.x - b.x), abs(a.y - b.y))

private fun WebArea.size(): Int = (maxX - minX + 1) * (maxY - minY + 1)
