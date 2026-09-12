package com.projectx.script.impl.gibson.farming

import world.gregs.voidps.type.Tile
import com.projectx.script.api.varps

enum class CactusPatch(
    val patchName: String,
    val locationName: String,
    val location: Tile,
    val varbitId: Int,
    val questId: Int
) {
    AL_KHARID("Cactus patch", "Al Kharid", Tile(3313, 3204, 0), 18416, 0);

    fun detectPatchState(): String {
        val varValue = varps.getVarBit(this.varbitId)
        return when {
            varValue in 0..2 -> "Needs to be raked"
            varValue == 3 -> "Already raked"
            varValue in 102..108 -> "Growing"
            varValue == 125 -> "Ready to be picked"
            varValue == 112 -> "Pick"
            varValue == 109 -> "Clear"
            else -> "Unknown state: $varValue"
        }
    }
}
