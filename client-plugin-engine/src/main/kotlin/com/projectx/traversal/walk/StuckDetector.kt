package com.projectx.traversal.walk

import world.gregs.voidps.type.Tile

/**
 * Says, about a second after the player stops, that a target cannot be walked to: the player has stood still for
 * [STILL_MS] and the map calls the target unreachable on two checks [CONFIRM_MS] apart, since the map can trail a door
 * by a tick and does not know which tiles NPCs block. Long before a no-movement timeout would give up on the walk.
 */
internal class StuckDetector(private val clock: () -> Long = System::currentTimeMillis) {
    private var lastTile: Tile? = null
    private var stillSince = 0L
    private var unreachableSince = 0L

    /** [unreachableFrom] must answer false when the map cannot tell. */
    fun stuck(here: Tile?, moving: Boolean, unreachableFrom: (Tile) -> Boolean): Boolean {
        val now = clock()
        if (here == null || moving || here != lastTile) {
            lastTile = here
            stillSince = now
            unreachableSince = 0
            return false
        }
        if (now - stillSince < STILL_MS) return false
        if (!unreachableFrom(here)) {
            unreachableSince = 0
            return false
        }
        if (unreachableSince == 0L) {
            unreachableSince = now
            return false
        }
        return now - unreachableSince >= CONFIRM_MS
    }

    fun reset() {
        lastTile = null
        unreachableSince = 0
    }

    companion object {
        const val STILL_MS = 1_200L
        const val CONFIRM_MS = 600L
    }
}
