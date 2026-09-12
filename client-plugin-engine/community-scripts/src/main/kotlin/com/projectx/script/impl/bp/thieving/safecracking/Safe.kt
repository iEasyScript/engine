package com.projectx.script.impl.bp.thieving.safecracking

import world.gregs.voidps.type.Tile
import com.projectx.script.api.varps

enum class Safe(val realId: Int, val tile: Tile, val numLocks: Int, val crackingStateVarbit: Int) {
    CAMELOT_CASTLE_WEST(111219, Tile.of(2749, 3500, 0), 5, 40318),
    CAMELOT_CASTLE_EAST(111220, Tile.of(2751, 3500, 0), 5, 40320),
    ARDOUGNE_SQUARE_NORTH(111216, Tile.of(2655, 3319, 1), 5, 40312),
    ARDOUGNE_SQUARE_SOUTH_WEST(111215, Tile.of(2649, 3301, 1), 5, 40310),
    ARDOUGNE_CASTLE_NORTH(111212, Tile.of(2574, 3305, 1), 5, 40304),
    ARDOUGNE_CASTLE_SOUTH(111213, Tile.of(2574, 3288, 1), 5, 40306),
    YANILLE_BAR_UPPER(111217, Tile.of(2556, 3077, 1), 5, 40314),
    YANILLE_WEST_WALL(111218, Tile.of(2533, 3084, 0), 5, 40316),
    ;

    fun isCracked(): Boolean {
        return varps.getVarBit(this.crackingStateVarbit) == this.numLocks
    }
}