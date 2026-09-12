package com.projectx.script.impl.trent.dungeoneering.puzzle

import world.gregs.voidps.type.Tile

/**
 * Routing through a grid whose only openings are gates that clip and can only be passed by interacting with
 * them. Walking is free and a gate costs one click, so this is a 0-1 BFS that minimises gates rather than
 * tiles. Pure and grid-agnostic — the caller supplies the step test and the gates — so it is unit tested
 * offline and stays independent of scene/collision plumbing.
 */
object MazeRoute {

    class Gate<G>(val door: G, val near: Tile, val far: Tile)

    private val STEPS = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)

    /**
     * The gates the cheapest route from [from] to the first tile satisfying [reached] has to cross, in
     * traversal order. Empty when that tile is walkable already, null when not even the gates connect them.
     * [step] answers "can the avatar walk from this tile by (dx, dy)"; each gate is usable in both directions.
     */
    fun <G> crossings(
        from: Tile,
        gates: List<Gate<G>>,
        step: (Tile, Int, Int) -> Boolean,
        reached: (Tile) -> Boolean,
    ): List<Gate<G>>? {
        val byTile = HashMap<Tile, MutableList<Gate<G>>>()
        for (gate in gates) {
            byTile.getOrPut(gate.near) { ArrayList() } += gate
            byTile.getOrPut(gate.far) { ArrayList() } += Gate(gate.door, gate.far, gate.near)
        }

        val cost = hashMapOf(from to 0)
        val previous = HashMap<Tile, Pair<Tile, Gate<G>?>>()
        val queue = ArrayDeque<Tile>()
        queue += from
        while (queue.isNotEmpty()) {
            val tile = queue.removeFirst()
            val here = cost.getValue(tile)
            if (reached(tile)) return backtrack(previous, from, tile)
            for ((dx, dy) in STEPS) {
                val next = Tile.of(tile.x + dx, tile.y + dy, tile.plane)
                if (!step(tile, dx, dy) || here >= (cost[next] ?: Int.MAX_VALUE)) continue
                cost[next] = here
                previous[next] = tile to null
                queue.addFirst(next)
            }
            for (gate in byTile[tile].orEmpty()) {
                if (here + 1 >= (cost[gate.far] ?: Int.MAX_VALUE)) continue
                cost[gate.far] = here + 1
                previous[gate.far] = tile to gate
                queue.addLast(gate.far)
            }
        }
        return null
    }

    private fun <G> backtrack(
        previous: Map<Tile, Pair<Tile, Gate<G>?>>,
        from: Tile,
        goal: Tile,
    ): List<Gate<G>> {
        val crossed = ArrayList<Gate<G>>()
        var at = goal
        while (at != from) {
            val (before, gate) = previous.getValue(at)
            if (gate != null) crossed += gate
            at = before
        }
        return crossed.asReversed()
    }
}
