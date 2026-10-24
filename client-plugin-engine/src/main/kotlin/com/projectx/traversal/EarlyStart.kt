package com.projectx.traversal

/**
 * A node whose interaction can begin before the player has walked all the way up to it: clicking a door, object or
 * NPC from a few steps away makes the server walk the player there. The walk before it hands over as soon as
 * [readyFrom] says so.
 */
interface EarlyStart {
    /** True when this node's interaction can be started from ([x], [y]) on [plane] now. */
    fun readyFrom(x: Int, y: Int, plane: Int): Boolean

    companion object {
        /** Kept short: once a node has taken over, the server walks the player there and no hop is cast. */
        const val MAX_STEPS = 5
    }
}
