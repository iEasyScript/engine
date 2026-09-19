package com.projectx.webwalker

import world.gregs.voidps.type.Tile

/** What a route has to do to cross a [WebLink], which is what tells the walker how to perform it. */
enum class WebLinkKind {
    /** Click a scene object: stairs, a ladder, a shortcut, a cave entrance. */
    OBJECT,

    /** Open a door and step through it. */
    DOOR,
}

/** A rectangle of tiles on one plane. Link endpoints are areas because a staircase lands you anywhere in a room. */
class WebArea(
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
    val plane: Int,
) {
    val centreX: Int get() = (minX + maxX) / 2
    val centreY: Int get() = (minY + maxY) / 2

    fun contains(x: Int, y: Int, plane: Int): Boolean =
        plane == this.plane && x in minX..maxX && y in minY..maxY

    /** Every tile in the rectangle, nearest the centre first, so a resolver tries the middle of a room before a corner. */
    fun tilesFromCentre(): List<Tile> {
        val tiles = ArrayList<Tile>((maxX - minX + 1) * (maxY - minY + 1))
        for (x in minX..maxX) for (y in minY..maxY) tiles += Tile.of(x, y, plane)
        return tiles.sortedBy { maxOf(Math.abs(it.x - centreX), Math.abs(it.y - centreY)) }
    }

    override fun toString(): String =
        if (minX == maxX && minY == maxY) "$minX,$minY,$plane" else "$minX..$maxX,$minY..$maxY,$plane"
}

/**
 * A condition on using a link: a varbit or varp value, a skill level, or coins in the pouch.
 *
 * [key] is `varbit_<id>`, `varp_<id>`, `<skill>Level`, `coins`, or an opaque flag the converter carried over;
 * anything this engine cannot evaluate is treated as met, so an unrecognised gate costs a re-plan rather than
 * making a destination unreachable. See [WebLinkPermissions].
 */
class WebRequirement(
    val about: String?,
    val key: String,
    val value: String,
    val comparison: String,
) {
    override fun toString(): String = "$key $comparison $value" + (about?.let { " ($it)" } ?: "")
}

/**
 * A step a route can take that walking cannot: up a staircase, down a ladder, through a shortcut or a door.
 *
 * Walk edges come from collision, so the pathfinder discovers them itself. A link cannot be derived that way -
 * nothing in the cache says where a staircase comes out - so links come from [WebLinks], which loads the curated
 * set converted from navpathService.
 *
 * [from] is where the player must stand and [to] is where they end up; the two may be on different planes, which
 * is the point. [cost] is in the pathfinder's own units, so it compares directly with walked tiles.
 */
class WebLink(
    val kind: WebLinkKind,
    val from: WebArea,
    val to: WebArea,
    val cost: Int,
    /** The option to click, e.g. "Climb-up" or "Open". */
    val action: String,
    /** The scene object to click it on; the walker looks for it within [searchRadius] of the player. */
    val objectId: Int,
    val searchRadius: Int,
    val requirements: List<WebRequirement>,
) {
    override fun toString(): String = "$kind($action $objectId: $from -> $to)"
}
