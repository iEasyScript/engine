package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/**
 * A world map area's details: what it is called, where it sits and which slabs of the world it draws.
 *
 * [colour] is a 24 bit value the client widens to rgba, and -1 for an area that carries none.
 */
data class WorldMapType(
    override var id: Int = -1,
    var map: String = "",
    var name: String = "",
    var position: Int = -1,
    var colour: Int = -1,
    var static: Boolean = false,
    var unknown6: Int = -1,
    var unknown7: Int = 0,
    var sections: List<WorldMapSection> = emptyList(),
) : CacheType
