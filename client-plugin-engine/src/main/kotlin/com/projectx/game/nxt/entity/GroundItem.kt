package com.projectx.game.nxt.entity

import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.DoActionOpcode
import com.projectx.script.api.interfaces
import com.projectx.script.api.localPlayer
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.ObjType

private val MENU_OPS = arrayOf(
    DoActionOpcode.GROUND_ITEM_1,
    DoActionOpcode.GROUND_ITEM_2,
    DoActionOpcode.GROUND_ITEM_3,
    DoActionOpcode.GROUND_ITEM_4,
    DoActionOpcode.GROUND_ITEM_5,
    DoActionOpcode.GROUND_ITEM_6
)

data class GroundItem(val id: Int, var amount: Int = 1, val tile: Tile) {
    // Tile is an inline value class, so its accessors are name-mangled and unreachable from Java.
    val tileX: Int get() = tile.x
    val tileY: Int get() = tile.y
    val plane: Int get() = tile.plane

    val name: String
        get() = Cache.obj(id)?.name ?: "null"

    val groundOps: Array<String?>
        get() = Cache.obj(id)?.groundActions ?: arrayOfNulls(6)

    fun interact(option: Int): Boolean {
        if (!localPlayer.tile.withinDistance(tile, 25)) return false
        if (option < 0 || option >= MENU_OPS.size) return false
        val doAction = MENU_OPS.getOrNull(option) ?: return false
        doAction.fire(id, tile.x, tile.y)
        return true
    }

    fun target(): Boolean {
        if (!localPlayer.tile.withinDistance(tile, 25)) return false
        DoActionOpcode.SELECT_GROUND_ITEM.fire(id, tile.x, tile.y)
        return true
    }

    fun telegrab(): Boolean {
        if (!interfaces.isOpen(1886)) return false
        val alch = IFSlot(1886, 1, 32)
        if (alch.select())
            return target()
        return false
    }

    fun getDef() = Cache.obj(id) ?: ObjType.EMPTY

    fun interact(option: String): Boolean {
        val op = getDef().getGroundOpIdForName(option)
        if (op != -1) {
            interact(op)
            return true
        }
        return false
    }
}