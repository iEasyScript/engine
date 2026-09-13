package com.projectx.webwalker

import com.projectx.pathfinder.StepValidator
import com.projectx.pathfinder.WorldCollision
import world.gregs.voidps.collision.CollisionFlag
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A* over cache collision on one plane, loading map squares as the search reaches them.
 *
 * Steps cost the same in every direction, as they do in game; diagonals carry a token extra cost only so that,
 * among equally short routes, the straighter one wins. A closed door is a passable edge with a surcharge, so
 * routes prefer open ground but still use a door when it is the way through.
 */
internal class WebPathfinder(
    private val maxExpansions: Int = MAX_EXPANSIONS,
    private val maxSquares: Int = MAX_SQUARES,
) {
    fun find(startX: Int, startY: Int, plane: Int, destX: Int, destY: Int, arriveDistance: Int): WebWalkResult {
        val tolerance = arriveDistance.coerceAtLeast(0)
        if (!squareExists(startX, startY, plane)) {
            return WebWalkResult(WebWalkStatus.NOT_IN_WORLD, "The start tile $startX,$startY is not on the world map")
        }
        if (!squareExists(destX, destY, plane)) {
            return WebWalkResult(WebWalkStatus.NO_PATH, "The destination $destX,$destY is not on the world map")
        }

        val validator = StepValidator(WorldCollision.allFlags)
        val best = IntIntMap()
        val open = LongHeap()
        val startKey = key(startX, startY)
        best.put(startKey, START_MARKER)
        open.push(heuristic(startX, startY, destX, destY, tolerance), startKey)

        var expansions = 0
        while (open.isNotEmpty()) {
            val entry = open.pop()
            val current = (entry and 0xFFFFFFFFL).toInt()
            val x = current shr 15
            val y = current and 0x7FFF
            val g = best.get(current) ushr G_SHIFT
            if ((entry ushr 32).toInt() != g + heuristic(x, y, destX, destY, tolerance)) continue

            if (max(abs(x - destX), abs(y - destY)) <= tolerance) return WebWalkResult(WebWalkStatus.PATH_FOUND, "Route found", rebuild(best, current, plane))
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
                val next = key(nx, ny)
                val known = best.get(next)
                if (known != IntIntMap.MISSING && (known ushr G_SHIFT) <= cost) continue
                best.put(next, (cost shl G_SHIFT) or ((if (door) 1 else 0) shl DOOR_BIT) or dir)
                open.push(cost + heuristic(nx, ny, destX, destY, tolerance), next)
            }
        }
        return WebWalkResult(WebWalkStatus.NO_PATH, "No walkable route on plane $plane after $expansions tiles")
    }

    private var squaresTouched = 0
    private val squareState = ByteArray(256 * 256)

    private fun squareExists(x: Int, y: Int, plane: Int): Boolean {
        if (x < 0 || y < 0 || x >= MAX_COORD || y >= MAX_COORD) return false
        val square = WebCollision.squareId(x, y)
        return when (squareState[square].toInt()) {
            LOADED -> true
            MISSING -> false
            else -> {
                squaresTouched++
                val exists = WebCollision.ensure(square, plane)
                squareState[square] = (if (exists) LOADED else MISSING).toByte()
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

    private fun rebuild(best: IntIntMap, goal: Int, plane: Int): WebPath {
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        val doors = ArrayList<Boolean>()
        var current = goal
        while (true) {
            val value = best.get(current)
            val x = current shr 15
            val y = current and 0x7FFF
            xs += x
            ys += y
            if (value == START_MARKER) {
                doors += false
                break
            }
            doors += (value shr DOOR_BIT) and 1 == 1
            val dir = value and DIR_MASK
            current = key(x - DX[dir], y - DY[dir])
        }
        xs.reverse()
        ys.reverse()
        doors.reverse()
        return WebPath(plane, xs.toIntArray(), ys.toIntArray(), doors.toBooleanArray())
    }

    private fun heuristic(x: Int, y: Int, destX: Int, destY: Int, tolerance: Int): Int {
        val dx = (abs(x - destX) - tolerance).coerceAtLeast(0)
        val dy = (abs(y - destY) - tolerance).coerceAtLeast(0)
        return STEP_COST * max(dx, dy) + (DIAGONAL_COST - STEP_COST) * min(dx, dy)
    }

    private fun key(x: Int, y: Int): Int = (x shl 15) or y

    private companion object {
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
        const val START_MARKER = 0xF

        const val LOADED = 1
        const val MISSING = 2

        const val BLOCKS_TILE = CollisionFlag.OBJECT or CollisionFlag.FLOOR or CollisionFlag.FLOOR_DECORATION

        // West, north, east, south first: their index doubles as the wall side a door occupies.
        val DX = intArrayOf(-1, 0, 1, 0, -1, 1, 1, -1)
        val DY = intArrayOf(0, 1, 0, -1, 1, 1, -1, -1)
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
