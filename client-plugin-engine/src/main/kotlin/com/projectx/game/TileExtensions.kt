package com.projectx.game
import com.projectx.game.tileOfSceneLocal
import com.projectx.game.tileOfLocal

import com.projectx.game.nxt.DoActionOpcode
import com.projectx.pathfinder.WorldCollision
import com.projectx.profiling.PlayerProfiles
import com.projectx.script.api.localPlayer
import world.gregs.voidps.type.Tile

data class LocalTile(val x: Short, val y: Short)

fun tileOfLocal(localX: Int, localY: Int, plane: Int): Tile {
    val base = localPlayer.tile
    return Tile((base.mapSquareX shl 6) + localX, (base.mapSquareY shl 6) + localY, plane)
}

fun tileOfSceneLocal(sceneLocalX: Int, sceneLocalY: Int, plane: Int): Tile? {
    val base = WorldCollision.sceneBase ?: return null
    return Tile(base.x + sceneLocalX, base.y + sceneLocalY, plane)
}

fun Tile.localizeScene(): LocalTile? {
    val base = WorldCollision.sceneBase ?: return null
    return LocalTile((x - base.x).toShort(), (y - base.y).toShort())
}

fun Tile.target(): Boolean {
    if (!localPlayer.tile.withinDistance(this, 20)) return false
    DoActionOpcode.SELECT_TILE.fire(0, x, y)
    return true
}

fun Tile.withinInteractionRange(other: Tile): Boolean =
    withinDistance(other, PlayerProfiles.get().interactDistanceRange)
