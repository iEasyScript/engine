package world.gregs.voidps.cache.type.data

class WorldmapSquareType(val mapSquareId: Int, val zoneAreas: IntArray) {
    fun areaAt(zoneX: Int, zoneY: Int): Int = zoneAreas[(zoneX and 7) * ZONES_PER_AXIS + (zoneY and 7)]

    companion object {
        const val ZONES_PER_AXIS = 8
        const val ZONES_PER_SQUARE = ZONES_PER_AXIS * ZONES_PER_AXIS
    }
}
