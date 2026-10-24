package com.projectx.pathfinder.navpath

import world.gregs.voidps.type.Tile

/** A rectangle the hosted planner reports; [plane] is null when it gave none. */
internal data class NavArea(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int, val plane: Int?) {
    companion object {
        fun of(tile: Tile) = NavArea(tile.x, tile.y, tile.x, tile.y, tile.plane)
    }
}

/**
 * One action of a hosted route. Macro actions carry the node they leave from and the node they land on ([from],
 * [to]); the database rows behind them add the areas an action is really taken from and lands in.
 */
internal sealed interface NavStep {
    val type: String

    /** The steps a link does after its first action: interface clicks, another object. */
    sealed interface Chained : NavStep {
        val from: Tile?
        val to: Tile?
        val chain: List<NavStep>
    }

    data class Move(val tile: Tile) : NavStep {
        override val type get() = "move"
    }

    data class Surge(val from: Tile?, val to: Tile, val tilesCovered: Int?) : NavStep {
        override val type get() = "surge"
    }

    data class Dive(val from: Tile?, val to: Tile, val tilesCovered: Int?) : NavStep {
        override val type get() = "dive"
    }

    data class TileExact(val tile: Tile, val minimap: Boolean?) : NavStep {
        override val type get() = "tile_exact"
    }

    data class FairyRing(val code: String, val objectId: Int, val action: String, val from: Tile?, val to: Tile, val portable: Boolean) : NavStep {
        override val type get() = "fairy_ring"
    }

    data class Lodestone(val name: String, override val from: Tile?, override val to: Tile?, override val chain: List<NavStep>) : Chained {
        override val type get() = "lodestone"
    }

    data class Npc(
        val id: Int?,
        val name: String?,
        val action: String,
        val searchRadius: Int?,
        val destination: NavArea?,
        val origin: NavArea?,
        override val from: Tile?,
        override val to: Tile?,
        override val chain: List<NavStep>,
    ) : Chained {
        override val type get() = "npc"
    }

    data class IFSlot(
        val interfaceId: Int,
        val component: Int,
        val slot: Int,
        val clickId: Int,
        val destination: NavArea?,
        override val from: Tile?,
        override val to: Tile?,
        override val chain: List<NavStep>,
    ) : Chained {
        override val type get() = "ifslot"
    }

    data class Loc(
        val id: Int?,
        val name: String?,
        val action: String,
        val searchRadius: Int?,
        val destination: NavArea?,
        val origin: NavArea?,
        override val from: Tile?,
        override val to: Tile?,
        override val chain: List<NavStep>,
    ) : Chained {
        override val type get() = "object"
    }

    data class Door(
        val direction: String,
        val realIdOpen: Int?,
        val realIdClosed: Int?,
        val openAction: String?,
        val tileInside: Tile?,
        val tileOutside: Tile?,
        override val from: Tile?,
        override val to: Tile?,
        override val chain: List<NavStep>,
    ) : Chained {
        override val type get() = "door"
    }

    data class ItemClick(
        val id: Int?,
        val name: String?,
        val option: String?,
        val optionNum: Int?,
        val destination: NavArea?,
        override val from: Tile?,
        override val to: Tile?,
        override val chain: List<NavStep>,
    ) : Chained {
        override val type get() = "item_click"
    }

    data class POAItem(
        val id: Int?,
        val name: String?,
        val action: String,
        val action2: String?,
        val action3: String?,
        val destination: NavArea?,
        override val from: Tile?,
        override val to: Tile?,
        override val chain: List<NavStep>,
    ) : Chained {
        override val type get() = "poa_item"
    }

    data class UseOn(
        val itemId: Int?,
        val itemName: String?,
        val objectId: Int?,
        val objectName: String?,
        val searchRadius: Int?,
        val destination: NavArea?,
        val origin: NavArea?,
        override val from: Tile?,
        override val to: Tile?,
        override val chain: List<NavStep>,
    ) : Chained {
        override val type get() = "use_on"
    }

    /** An action this engine has no way to perform: a generic teleport, or a kind the planner added later. */
    data class Unsupported(override val type: String, val reason: String) : NavStep
}

/** A parsed `/route` answer. [enteredByTeleport] when the start was off the planner's graph. */
internal data class NavRoute(
    val found: Boolean,
    val cost: Double,
    val lengthTiles: Int?,
    val durationMs: Int?,
    val steps: List<NavStep>,
    val enteredByTeleport: Boolean,
)
