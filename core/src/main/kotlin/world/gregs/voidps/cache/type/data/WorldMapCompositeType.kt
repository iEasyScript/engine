package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/**
 * One entry of a map area's composite overlay table.
 *
 * [colour] and [secondColour] are argb, and every shipped entry is either fully opaque or fully
 * transparent; [secondColour] is zero throughout for an entry that carries only one.
 */
data class WorldMapCompositeOverlay(
    var unknown1: Int = 0,
    var colour: Int = 0,
    var secondColour: Int = 0,
    var unknown4: Int = 0,
)

/**
 * The overlay table that follows a map area's composite image.
 *
 * The image itself is not held here: it is a whole PNG that the source tree keeps as its own file,
 * and the length that prefixes it in the cache is the image's own size.
 */
data class WorldMapCompositeType(
    override var id: Int = -1,
    var overlays: List<WorldMapCompositeOverlay> = emptyList(),
) : CacheType
