package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/** A whole map square of the world drawn at [mapSquareX], [mapSquareY] of a map area. */
data class WorldMapSquareLink(
    var level: Int = 0,
    var unknown2: Int = 0,
    var sourceSquareX: Int = 0,
    var sourceSquareY: Int = 0,
    var unknown5: Int = 0,
    var mapSquareX: Int = 0,
    var mapSquareY: Int = 0,
)

/** One zone of the world drawn at a zone of a map area, for the edges a whole square cannot cover. */
data class WorldMapZoneLink(
    var level: Int = 0,
    var unknown2: Int = 0,
    var sourceSquareX: Int = 0,
    var sourceSquareY: Int = 0,
    var sourceZoneX: Int = 0,
    var sourceZoneY: Int = 0,
    var unknown7: Int = 0,
    var mapSquareX: Int = 0,
    var mapSquareY: Int = 0,
    var mapZoneX: Int = 0,
    var mapZoneY: Int = 0,
)

/**
 * Where a map area draws each piece of the world, at map square and then at zone granularity.
 *
 * [width] and [height] are the area's own size in map squares, which is the span the links cover.
 */
data class WorldMapCoordsType(
    override var id: Int = -1,
    var squares: List<WorldMapSquareLink> = emptyList(),
    var zones: List<WorldMapZoneLink> = emptyList(),
    var width: Int = 0,
    var height: Int = 0,
) : CacheType
