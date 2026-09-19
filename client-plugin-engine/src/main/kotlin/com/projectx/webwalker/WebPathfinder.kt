package com.projectx.webwalker

import com.projectx.pathfinder.StepValidator
import com.projectx.pathfinder.WorldCollision
import world.gregs.voidps.collision.CollisionFlag
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A* over cache collision, across planes, loading map squares as the search reaches them.
 *
 * Steps cost the same in every direction, as they do in game; diagonals carry a token extra cost only so that,
 * among equally short routes, the straighter one wins. A closed door found in the cache is a passable edge with a
 * surcharge, so routes prefer open ground but still use a door when it is the way through.
 *
 * On top of walking the search may take a [WebLink] - a staircase, ladder, shortcut or curated door - and those
 * are the only edges that change plane, so they are what lets a route leave the floor it started on.
 *
 * The heuristic is Chebyshev distance over x and y. That bounds walking from below but not a link, which can
 * cover a lot of ground for its price, so a route through links is not guaranteed to be the cheapest that exists.
 * It is a real route either way, and links are priced in walked tiles so the search does not take absurd ones.
 */
internal class WebPathfinder(
    private val maxExpansions: Int = MAX_EXPANSIONS,
    private val maxSquares: Int = MAX_SQUARES,
) {
    fun find(
        startX: Int,
        startY: Int,
        startPlane: Int,
        destX: Int,
        destY: Int,
        destPlane: Int,
        arriveDistance: Int,
        permissions: WebLinkPermissions = WebLinkPermissions.UNRESTRICTED,
    ): WebWalkResult {
        val tolerance = arriveDistance.coerceAtLeast(0)
        if (!squareExists(startX, startY, startPlane)) {
            return WebWalkResult(WebWalkStatus.NOT_IN_WORLD, "The start tile $startX,$startY is not on the world map")
        }
        if (!squareExists(destX, destY, destPlane)) {
            return WebWalkResult(WebWalkStatus.NO_PATH, "The destination $destX,$destY is not on the world map")
        }

        val validator = StepValidator(WorldCollision.allFlags)
        val best = IntIntMap()
        val open = LongHeap()
        val linkFrom = HashMap<Int, LinkStep>()
        val startKey = key(startX, startY, startPlane)
        best.put(startKey, START_MARKER)
        open.push(heuristic(startX, startY, destX, destY, tolerance), startKey)

        var expansions = 0
        while (open.isNotEmpty()) {
            val entry = open.pop()
            val current = (entry and 0xFFFFFFFFL).toInt()
            val x = tileX(current)
            val y = tileY(current)
            val plane = tilePlane(current)
            val g = best.get(current) ushr G_SHIFT
            if ((entry ushr 32).toInt() != g + heuristic(x, y, destX, destY, tolerance)) continue

            if (plane == destPlane && max(abs(x - destX), abs(y - destY)) <= tolerance) {
                return WebWalkResult(WebWalkStatus.PATH_FOUND, "Route found", rebuild(best, linkFrom, current))
            }
            if (++expansions > maxExpansions || squaresTouched > maxSquares) {
                return WebWalkResult(WebWalkStatus.TOO_FAR, "Gave up after $expansions tiles and $squaresTouched map squares")
            }

            for (dir in 0 until 8) {
                val nx = x + DX[dir]
                val ny = y + DY[dir]
                if (!squareExists(nx, ny, plane) || !squareExists(nx, y, plane) || !squareExists(x, ny, plane)) continue

                var door = false
                if (!validator.canTravel(plane, x, y, DX[dir], DY[dir], extraFlag = 0)) {
                    if (dir >= CARDINALS || !doorBetween(x, y, nx, ny, plane, dir)) continue
                    door = true
                }

                val cost = g + (if (dir < CARDINALS) STEP_COST else DIAGONAL_COST) + (if (door) DOOR_COST else 0)
                val marker = ((if (door) 1 else 0) shl DOOR_BIT) or dir
                relax(best, open, key(nx, ny, plane), cost, marker, destX, destY, tolerance)
            }

            for (link in WebLinks.from(x, y, plane)) {
                if (!permissions.allows(link)) continue
                val destination = destinationOf(link) ?: continue
                val next = key(destination.x, destination.y, destination.plane)
                if (relax(best, open, next, g + link.cost, LINK_MARKER, destX, destY, tolerance)) {
                    linkFrom[next] = LinkStep(current, link)
                }
            }
        }
        return WebWalkResult(WebWalkStatus.NO_PATH, "No route after $expansions tiles across $squaresTouched map squares")
    }

    private class LinkStep(val from: Int, val link: WebLink)

    /** Records a cheaper way to reach [next]; true when this improved on what was already known. */
    private fun relax(
        best: IntIntMap,
        open: LongHeap,
        next: Int,
        cost: Int,
        marker: Int,
        destX: Int,
        destY: Int,
        tolerance: Int,
    ): Boolean {
        val known = best.get(next)
        if (known != IntIntMap.MISSING && (known ushr G_SHIFT) <= cost) return false
        best.put(next, (cost shl G_SHIFT) or marker)
        open.push(cost + heuristic(tileX(next), tileY(next), destX, destY, tolerance), next)
        return true
    }

    /** Links land in a rectangle, so a route aims at the tile nearest its middle that is actually walkable. */
    private val destinations = HashMap<WebLink, Tile>()

    private fun destinationOf(link: WebLink): Tile? {
        destinations[link]?.let { return it }
        val tile = link.to.tilesFromCentre().firstOrNull { walkable(it.x, it.y, it.plane) } ?: return null
        destinations[link] = tile
        return tile
    }

    private fun walkable(x: Int, y: Int, plane: Int): Boolean {
        if (!squareExists(x, y, plane)) return false
        val flags = WorldCollision.getFlags(x, y, plane)
        return flags != -1 && flags and BLOCKS_TILE == 0
    }

    private var squaresTouched = 0

    // Keyed by square and plane together: a square exists on every plane or none, but its zones are allocated per
    // plane, and a cross-plane search reaches the same square on more than one of them.
    private val squareState = ByteArray(256 * 256 * 4)

    private fun squareExists(x: Int, y: Int, plane: Int): Boolean {
        if (x < 0 || y < 0 || x >= MAX_COORD || y >= MAX_COORD || plane < 0 || plane > 3) return false
        val slot = (WebCollision.squareId(x, y) shl 2) or plane
        return when (squareState[slot].toInt()) {
            LOADED -> true
            MISSING -> false
            else -> {
                squaresTouched++
                val exists = WebCollision.ensure(WebCollision.squareId(x, y), plane)
                squareState[slot] = (if (exists) LOADED else MISSING).toByte()
                exists
            }
        }
    }

    // A door stands on one side of one tile; crossing that side from either tile passes it.
    private fun doorBetween(x: Int, y: Int, nx: Int, ny: Int, plane: Int, side: Int): Boolean {
        val fromSide = WebCollision.doorSide(x, y, plane) == side
        val toSide = WebCollision.doorSide(nx, ny, plane) == (side + 2) % 4
        if (!fromSide && !toSide) return false
        val flags = WorldCollision.getFlags(nx, ny, plane)
        return flags != -1 && flags and BLOCKS_TILE == 0
    }

    private fun rebuild(best: IntIntMap, linkFrom: Map<Int, LinkStep>, goal: Int): WebPath {
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        val planes = ArrayList<Int>()
        val doors = ArrayList<Boolean>()
        val links = ArrayList<WebLink?>()
        var current = goal
        while (true) {
            val value = best.get(current)
            xs += tileX(current)
            ys += tileY(current)
            planes += tilePlane(current)
            if (value == START_MARKER) {
                doors += false
                links += null
                break
            }
            val dir = value and DIR_MASK
            if (dir == LINK_MARKER) {
                val step = linkFrom[current]
                doors += false
                links += step?.link
                if (step == null) break
                current = step.from
            } else {
                doors += (value shr DOOR_BIT) and 1 == 1
                links += null
                current = key(tileX(current) - DX[dir], tileY(current) - DY[dir], tilePlane(current))
            }
        }
        xs.reverse()
        ys.reverse()
        planes.reverse()
        doors.reverse()
        links.reverse()
        return WebPath(xs.toIntArray(), ys.toIntArray(), planes.toIntArray(), doors.toBooleanArray(), links.toTypedArray())
    }

    private fun heuristic(x: Int, y: Int, destX: Int, destY: Int, tolerance: Int): Int {
        val dx = (abs(x - destX) - tolerance).coerceAtLeast(0)
        val dy = (abs(y - destY) - tolerance).coerceAtLeast(0)
        return STEP_COST * max(dx, dy) + (DIAGONAL_COST - STEP_COST) * min(dx, dy)
    }

    companion object {
        const val MAX_EXPANSIONS = 1_500_000
        const val MAX_SQUARES = 900
        const val MAX_COORD = 256 * 64

        const val STEP_COST = 1000
        const val DIAGONAL_COST = 1001
        const val DOOR_COST = 4000

        const val CARDINALS = 4
        const val G_SHIFT = 5
        const val DOOR_BIT = 4
        const val DIR_MASK = 0xF

        /** Reached by a [WebLink] rather than a step; the link itself is kept beside the path. */
        const val LINK_MARKER = 0xE
        const val START_MARKER = 0xF

        const val LOADED = 1
        const val MISSING = 2

        const val BLOCKS_TILE = CollisionFlag.OBJECT or CollisionFlag.FLOOR or CollisionFlag.FLOOR_DECORATION

        // West, north, east, south first: their index doubles as the wall side a door occupies.
        val DX = intArrayOf(-1, 0, 1, 0, -1, 1, 1, -1)
        val DY = intArrayOf(0, 1, 0, -1, 1, 1, -1, -1)

        // x and y each fit in 14 bits at MAX_COORD, leaving room for the plane; the key stays a positive Int.
        fun key(x: Int, y: Int, plane: Int): Int = (plane shl 28) or (x shl 14) or y

        fun tileX(key: Int): Int = (key shr 14) and 0x3FFF

        fun tileY(key: Int): Int = key and 0x3FFF

        fun tilePlane(key: Int): Int = (key ushr 28) and 0x3
    }
}

/** Open-addressing int map for the search's best-cost table; boxing a million entries would dominate the search. */
private class IntIntMap {
    private var keys = IntArray(1 shl 16)
    private var values = IntArray(1 shl 16)
    private var count = 0

    fun get(key: Int): Int {
        val mask = keys.size - 1
        var slot = mix(key) and mask
        while (true) {
            val stored = keys[slot]
            if (stored == 0) return MISSING
            if (stored == key + 1) return values[slot]
            slot = (slot + 1) and mask
        }
    }

    fun put(key: Int, value: Int) {
        if (count * 10 >= keys.size * 6) grow()
        if (insert(keys, values, key, value)) count++
    }

    private fun insert(keys: IntArray, values: IntArray, key: Int, value: Int): Boolean {
        val mask = keys.size - 1
        var slot = mix(key) and mask
        while (true) {
            val stored = keys[slot]
            if (stored == 0) {
                keys[slot] = key + 1
                values[slot] = value
                return true
            }
            if (stored == key + 1) {
                values[slot] = value
                return false
            }
            slot = (slot + 1) and mask
        }
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = IntArray(oldKeys.size * 2)
        values = IntArray(oldValues.size * 2)
        for (i in oldKeys.indices) if (oldKeys[i] != 0) insert(keys, values, oldKeys[i] - 1, oldValues[i])
    }

    private fun mix(key: Int): Int {
        var h = key * -0x61c88647
        h = h xor (h ushr 16)
        return h
    }

    companion object {
        const val MISSING = -1
    }
}

/** Binary min-heap of `priority shl 32 or key`, so ordering by the long orders by priority. */
private class LongHeap {
    private var items = LongArray(1 shl 12)
    private var size = 0

    fun isNotEmpty() = size > 0

    fun push(priority: Int, key: Int) {
        if (size == items.size) items = items.copyOf(size * 2)
        var index = size++
        val item = (priority.toLong() shl 32) or (key.toLong() and 0xFFFFFFFFL)
        while (index > 0) {
            val parent = (index - 1) ushr 1
            if (items[parent] <= item) break
            items[index] = items[parent]
            index = parent
        }
        items[index] = item
    }

    fun pop(): Long {
        val top = items[0]
        val last = items[--size]
        var index = 0
        while (true) {
            val left = index * 2 + 1
            if (left >= size) break
            val right = left + 1
            val child = if (right < size && items[right] < items[left]) right else left
            if (items[child] >= last) break
            items[index] = items[child]
            index = child
        }
        items[index] = last
        return top
    }
}
