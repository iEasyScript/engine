package com.projectx.script.api

import com.projectx.game.nxt.entity.SpotAnim
import world.gregs.voidps.type.Tile

/**
 * Time sprites are Archaeology's version of a rockertunity: one settles on an excavation hotspot for a while, and
 * digging the patch it is sitting on is worth considerably more than carrying on where you are.
 *
 * An Archaeology script uses [timeSpriteTile] to aim its next click at the patch the sprite chose, and ends a long
 * dig early on a sprite appearing somewhere else, the way a skilling script ends one on `findSerenSpirit()`.
 *
 * Unlike a Seren spirit a sprite is scenery decoration rather than an NPC, so there is nothing to click and
 * nothing to capture: it only says where to dig.
 */
private const val DEFAULT_RANGE = 24

/**
 * The spot animations a time sprite is drawn with.
 *
 * An array rather than a single id because mining's rockertunity needed two, and a second here would otherwise
 * mean every Archaeology script learning about it separately.
 */
private val TIME_SPRITE_IDS = intArrayOf(7307)

/** The nearest time sprite within [range] tiles, or null when none is up. */
@JvmOverloads
fun findTimeSprite(range: Int = DEFAULT_RANGE): SpotAnim? =
    findClosestSpotAnim(range) { TIME_SPRITE_IDS.contains(it.id) }

/** The tile a time sprite has settled on within [range], or null when none is up. Java: `getTimeSpriteTile`. */
@JvmOverloads
fun timeSpriteTile(range: Int = DEFAULT_RANGE): Tile? = findTimeSprite(range)?.tile

/**
 * True when a sprite is up somewhere other than [digging], so the dig in progress is worth breaking off.
 *
 * [samePatch] is how close counts as the patch already being dug; a sprite sits on the hotspot itself, so one
 * tile of slack covers a hotspot whose footprint is larger than the tile it is anchored to.
 */
@JvmOverloads
fun timeSpriteElsewhere(digging: Tile, range: Int = DEFAULT_RANGE, samePatch: Int = 2): Boolean {
    val sprite = timeSpriteTile(range) ?: return false
    return !sprite.withinDistance(digging, samePatch)
}

/**
 * [timeSpriteElsewhere] for callers holding coordinates rather than a [Tile].
 *
 * Tile is an inline value class, so taking one mangles the JVM name and Java cannot call it at all; this is the
 * form a Java script uses, the same way [walkToTile] stands in for `walkTo`.
 */
@JvmOverloads
fun timeSpriteElsewhereThan(x: Int, y: Int, plane: Int, range: Int = DEFAULT_RANGE, samePatch: Int = 2): Boolean =
    timeSpriteElsewhere(Tile.of(x, y, plane), range, samePatch)
