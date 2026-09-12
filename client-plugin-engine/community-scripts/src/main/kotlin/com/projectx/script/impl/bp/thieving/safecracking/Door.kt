package com.projectx.script.impl.bp.thieving.safecracking
import com.projectx.game.tileOfLocal
import world.gregs.voidps.type.Tile
import com.projectx.traversal.nodes.DoorInfo

enum class Door(
    val realIdOpen: Int,
    val realIdClosed: Int,
    val locationOpen: Tile,
    val locationClosed: Tile,
    val tileInside: Tile,
    val tileOutside: Tile,
    val openAction: String? = "Open"
) {
    CAMELOT_CASTLE_MAIN_DOOR(
        25639,
        25638,
        Tile.of(2757, 3504, 0),
        Tile.of(2757, 3503, 0),
        Tile.of(2757, 3504, 0),
        Tile.of(2757, 3503, 0),
        "Open"
    ),
    CAMELOT_CASTLE_WEST_DOOR(
        25643,
        25642,
        Tile.of(2750, 3504, 0),
        Tile.of(2750, 3503, 0),
        Tile.of(2750, 3504, 0),
        Tile.of(2750, 3503, 0),
        "Open"
    ),
    ARDOUGNE_SQUARE_NORTH_LOWER(
        34808,
        34807,
        Tile.of(2659, 3320, 0),
        Tile.of(2659, 3319, 0),
        Tile.of(2659, 3320, 0),
        Tile.of(2659, 3319, 0),
        "Open"
    ),
    ARDOUGNE_SQUARE_NORTH_UPPER(
        34813,
        34811,
        Tile.of(2660, 3320, 1),
        Tile.of(2661, 3320, 1),
        Tile.of(2660, 3320, 1),
        Tile.of(2661, 3320, 1),
        "Open"
    ),
    ARDOUGNE_SQUARE_SOUTH_WEST_LOWER(
        34808,
        34807,
        Tile.of(2651, 3302, 0),
        Tile.of(2652, 3302, 0),
        Tile.of(2651, 3302, 0),
        Tile.of(2652, 3302, 0),
        "Open"
    ),
    ARDOUGNE_SQUARE_SOUTH_WEST_UPPER(
        34813,
        34811,
        Tile.of(2649, 3300, 1),
        Tile.of(2648, 3300, 1),
        Tile.of(2649, 3300, 1),
        Tile.of(2648, 3300, 1),
        "Open"
    ),
    ARDOUGNE_CASTLE_LOWER_MAIN(
        2549,
        2548,
        Tile.of(2579, 3297, 0),
        Tile.of(2580, 3297, 0),
        Tile.of(2579, 3297, 0),
        Tile.of(2580, 3297, 0),
        "Open"
    ),
    ARDOUGNE_CASTLE_LOWER_NORTH(
        34808,
        34807,
        Tile.of(2572, 3302, 0),
        Tile.of(2572, 3303, 0),
        Tile.of(2572, 3303, 0),
        Tile.of(2572, 3302, 0),
        "Open"
    ),
    ARDOUGNE_CASTLE_UPPER_NORTH(
        34808,
        34807,
        Tile.of(2577, 3306, 1),
        Tile.of(2577, 3305, 1),
        Tile.of(2577, 3305, 1),
        Tile.of(2577, 3306, 1),
        "Open"
    ),
    ARDOUGNE_CASTLE_UPPER_SOUTH(
        34808,
        34807,
        Tile.of(2573, 3290, 1),
        Tile.of(2574, 3290, 1),
        Tile.of(2574, 3290, 1),
        Tile.of(2573, 3290, 1),
        "Open"
    ),
    YANILLE_WALL_DOOR(
        17090,
        17089,
        Tile.of(2537, 3090, 0),
        Tile.of(2537, 3089, 0),
        Tile.of(2537, 3089, 0),
        Tile.of(2537, 3090, 0),
        "Open"
    ),
    YANILLE_BAR_DOOR(
        1534,
        1533,
        Tile.of(2551, 3083, 0),
        Tile.of(2551, 3082, 0),
        Tile.of(2551, 3082, 0),
        Tile.of(2551, 3083, 0),
        "Open"
    ),
}

fun Door.toDoorInfo(): DoorInfo = DoorInfo(
    realIdOpen = this.realIdOpen,
    realIdClosed = this.realIdClosed,
    locationOpen = this.locationOpen,
    locationClosed = this.locationClosed,
    tileInside = this.tileInside,
    tileOutside = this.tileOutside,
    openAction = this.openAction
)
