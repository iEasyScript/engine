package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.data.WorldmapSquareType.Companion.ZONES_PER_SQUARE

/** [length] zones of [colour], in the order [WorldMapColoursType.zoneColours] lays them out. */
data class WorldMapColourRun(var colour: Int = 0, var length: Int = 0)

/**
 * One map square's world area colours, run length encoded over its 64 zones.
 *
 * The file leaves the last run's length out, so it is always whatever is needed to reach
 * [ZONES_PER_SQUARE]; it is filled in here so a reader never has to work it out.
 */
data class WorldMapColoursType(
    override var id: Int = -1,
    var runs: List<WorldMapColourRun> = emptyList(),
) : CacheType {

    fun zoneColours(): IntArray {
        val colours = IntArray(ZONES_PER_SQUARE)
        var index = 0
        for (run in runs) {
            val end = minOf(index + run.length, ZONES_PER_SQUARE)
            while (index < end) {
                colours[index++] = run.colour
            }
        }
        return colours
    }
}
