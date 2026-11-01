package com.projectx.script.api

import com.projectx.pathfinder.HopModel
import com.projectx.script.Script
import com.projectx.util.gaussian
import org.projectx.core.game.combat.Ability
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max

private const val FACING_POLL_MILLIS = 20
private const val SURGE_LEFT_TILES = 2

/**
 * Turns the player toward ([x], [y]) by stepping one tile that way, along the nearest of the 8 directions, and
 * waits for the step to land. Surge and Escape fly the way the player faces, so this aims them. Returns whether
 * the player stands on that tile; false when ([x], [y]) is where the player already is.
 */
suspend fun Script.faceTile(x: Int, y: Int): Boolean {
    val fromX = localPlayer.tileX
    val fromY = localPlayer.tileY
    val plane = localPlayer.plane
    val step = HopModel.dir8(x - fromX, y - fromY)?.delta ?: return false
    val stepX = fromX + step.x
    val stepY = fromY + step.y
    if (!walkToTile(stepX, stepY, plane)) return false
    delayUntil(gaussian(1500L, 300L), FACING_POLL_MILLIS) { localPlayer.tileX == stepX && localPlayer.tileY == stepY }
    return localPlayer.tileX == stepX && localPlayer.tileY == stepY
}

/** [faceTile] for a [Tile]. */
suspend fun Script.faceTile(tile: Tile): Boolean = faceTile(tile.x, tile.y)

/**
 * Surges toward ([x], [y]): faces that way with [faceTile], then casts Surge the moment the step lands, before
 * anything can turn the player back. Surge goes along the nearest of the 8 directions, up to 10 tiles. Returns
 * whether the player surged; false, without moving, when Surge is not on an action bar or is cooling down.
 */
suspend fun Script.surgeToward(x: Int, y: Int): Boolean {
    if (actionbarAbilities[Ability.SURGE.type] == null || !Ability.SURGE.offCdIgnoreGCD) return false
    if (!faceTile(x, y)) return false
    val fromX = localPlayer.tileX
    val fromY = localPlayer.tileY
    if (!surge()) return false
    val left = { max(abs(localPlayer.tileX - fromX), abs(localPlayer.tileY - fromY)) >= SURGE_LEFT_TILES }
    delayUntil(gaussian(1200L, 250L), FACING_POLL_MILLIS, left)
    return left()
}

/** [surgeToward] for a [Tile]. */
suspend fun Script.surgeToward(tile: Tile): Boolean = surgeToward(tile.x, tile.y)
