package com.projectx.script.impl.devin.solak

import com.projectx.pathfinder.WorldCollision
import com.projectx.script.api.allNpcsWithinRange
import world.gregs.voidps.type.Tile

object SolakArena {

    private const val ANCHOR_SEARCH_RANGE = 60
    private const val ANCHOR_TTL_MS = 10_000L

    private var anchorSeenAt = 0L

    fun reset() {
        anchorSeenAt = 0
    }

    fun refresh() {
        val seen = runCatching {
            allNpcsWithinRange(ANCHOR_SEARCH_RANGE) { it.exists() && it.anchorsEncounter() }.isNotEmpty()
        }.getOrDefault(false)
        if (seen) anchorSeenAt = System.currentTimeMillis()
    }

    fun anchorNearby(): Boolean = System.currentTimeMillis() - anchorSeenAt <= ANCHOR_TTL_MS

    fun encounterActive(): Boolean = WorldCollision.inDynamic && anchorNearby()

    /**
     * Earthen Seed impact tiles, the root paths and the rootling spawn ring have not been measured
     * from a capture yet, so nothing is drawn rather than drawn from assumed coordinates.
     */
    fun hazardTiles(): List<Tile> = emptyList()
}
