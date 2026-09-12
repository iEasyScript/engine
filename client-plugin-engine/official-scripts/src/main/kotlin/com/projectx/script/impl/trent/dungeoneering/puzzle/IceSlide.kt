package com.projectx.script.impl.trent.dungeoneering.puzzle

import world.gregs.voidps.type.Tile

/**
 * The shared ice-slide primitive for Daemonheim frozen floors: stepping onto ice makes you keep moving in the
 * direction you walked until the next tile is blocked (a wall, a pillar, or the room edge), stopping on the
 * last free tile. Pure and grid-agnostic — the caller supplies `blocked` (from collision/scene) — so it's unit
 * tested offline and reused by both plain ice-room TRAVERSAL and the sliding-ice PUZZLE solvers.
 */
enum class SlideDir(val dx: Int, val dy: Int) {
    NORTH(0, 1), EAST(1, 0), SOUTH(0, -1), WEST(-1, 0)
}

object IceSlide {

    /**
     * Where a slide from [from] heading [dir] comes to rest: advance one tile at a time while the NEXT tile is
     * free, stop on the last free tile. Returns [from] unchanged when the very next tile is blocked (no move).
     */
    fun landing(from: Tile, dir: SlideDir, blocked: (Int, Int) -> Boolean): Tile {
        var x = from.x
        var y = from.y
        while (!blocked(x + dir.dx, y + dir.dy)) {
            x += dir.dx
            y += dir.dy
        }
        return Tile.of(x, y, from.plane)
    }

    /**
     * A BFS over slide-moves — each state is a rest tile, its neighbours are the four slide landings — giving
     * the shortest sequence of walk directions from [from] to [to], or null if [to] can't be reached as a
     * resting tile. [to] must be a tile a slide actually stops on (against a wall/pillar).
     */
    fun path(from: Tile, to: Tile, blocked: (Int, Int) -> Boolean): List<SlideDir>? {
        val start = from.x to from.y
        val goal = to.x to to.y
        if (start == goal) return emptyList()

        val prev = HashMap<Pair<Int, Int>, Pair<Pair<Int, Int>, SlideDir>>()
        val seen = hashSetOf(start)
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue += start
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (dir in SlideDir.entries) {
                val land = landing(Tile.of(cur.first, cur.second, from.plane), dir, blocked)
                val key = land.x to land.y
                if (key == cur || !seen.add(key)) continue
                prev[key] = cur to dir
                if (key == goal) return backtrack(prev, start, key)
                queue += key
            }
        }
        return null
    }

    private fun backtrack(
        prev: Map<Pair<Int, Int>, Pair<Pair<Int, Int>, SlideDir>>,
        start: Pair<Int, Int>,
        goal: Pair<Int, Int>,
    ): List<SlideDir> {
        val dirs = ArrayList<SlideDir>()
        var t = goal
        while (t != start) {
            val (p, d) = prev.getValue(t)
            dirs += d
            t = p
        }
        return dirs.asReversed()
    }
}
