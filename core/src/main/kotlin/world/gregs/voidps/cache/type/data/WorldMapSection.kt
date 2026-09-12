package world.gregs.voidps.cache.type.data

/**
 * One rectangle of the world a map area draws: [minX]..[maxY] in world tiles, drawn at
 * [startX]..[endY] in the area's own tile space.
 */
data class WorldMapSection(
    var level: Int = 0,
    var minX: Int = 0,
    var minY: Int = 0,
    var maxX: Int = 0,
    var maxY: Int = 0,
    var startX: Int = 0,
    var startY: Int = 0,
    var endX: Int = 0,
    var endY: Int = 0,
)
