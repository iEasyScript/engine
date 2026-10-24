package com.projectx.traversal

import world.gregs.voidps.type.Tile

/**
 * A node the player can sometimes get past just by walking: a walk, a door that stands open. When the player already
 * stands on a later node's tiles and every node between is passable, the traversal skips ahead to it, and a walk
 * aims its clicks on through the tiles of the passable nodes after it.
 */
interface WalkEquivalent {
    /** The tiles a player passing this node stands on, in route order. */
    fun routeTiles(): List<Tile>

    /** True when walking alone gets the player past this node right now. */
    fun passableNow(): Boolean

    /** True when the walk before this node may aim clicks through its tiles while it is passable. */
    fun walkThrough(): Boolean = true
}
