package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.localPlayer
import world.gregs.voidps.type.Tile

private const val ROOM_TILES = 16

/**
 * A Daemonheim room is one 16×16 cell, and a puzzle only ever owns its OWN room. Detection must be bounded to
 * the room the player is standing in — a flat tile radius spills across walls into adjacent rooms (e.g. a
 * monolith one room over, or a bridge seen from the hub) and makes a solver think it's in a room it isn't,
 * flailing at objects it can't reach. Every puzzle's present()/scan uses these instead of a raw range.
 */
fun inPlayerRoom(tile: Tile): Boolean =
    tile.x / ROOM_TILES == localPlayer.tile.x / ROOM_TILES &&
        tile.y / ROOM_TILES == localPlayer.tile.y / ROOM_TILES

fun objectsInRoom(range: Int = 20): List<SceneObject> =
    getAllObjectsWithinRange(range).filter { inPlayerRoom(it.tile) }

fun npcsInRoom(range: Int = 16, predicate: (NPC) -> Boolean): List<NPC> =
    allNpcsWithinRange(range) { inPlayerRoom(it.tile) && predicate(it) }
