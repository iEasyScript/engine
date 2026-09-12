package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

data class WorldMapIconType(
    override var id: Int = -1,
    var icons: Array<WorldMapIcon> = emptyArray()
) : CacheType {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WorldMapIconType

        if (id != other.id) return false
        if (!icons.contentEquals(other.icons)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + icons.contentHashCode()
        return result
    }
}