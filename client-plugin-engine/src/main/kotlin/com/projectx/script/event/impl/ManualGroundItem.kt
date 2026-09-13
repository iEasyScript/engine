package com.projectx.script.event.impl

import world.gregs.voidps.type.Tile

class ManualGroundItem(val itemId: Int, val name: String, val tile: Tile) {
    // Tile is an inline value class, so its accessors are name-mangled and unreachable from Java.
    val tileX: Int get() = tile.x
    val tileY: Int get() = tile.y
    val plane: Int get() = tile.plane
}
