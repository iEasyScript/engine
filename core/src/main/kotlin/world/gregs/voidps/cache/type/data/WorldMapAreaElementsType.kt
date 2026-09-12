package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/**
 * One map element drawn on a world map area.
 *
 * [coord] is the packed position the file carries: level in bits 28 and 29, x in bits 14 to 27 and
 * z in the low fourteen, with all ones standing for no coordinate. It is kept packed because the
 * bits outside those fields are not the client's to interpret and must survive a rebuild. The
 * client acts only on records whose [flag] is zero.
 */
data class WorldMapAreaElement(
    var coord: Int = 0,
    var mapElementId: Int = 0,
    var flag: Int = 0,
)

/**
 * A map area's map element list, stored twice by the cache: inline as the area group's second file
 * and again as the whole of the area coordinate index's group, byte for byte the same.
 */
data class WorldMapAreaElementsType(
    override var id: Int = -1,
    var elements: List<WorldMapAreaElement> = emptyList(),
) : CacheType
