package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

class MapSquareType(
    override var id: Int = -1
) : CacheType {
    var surface = MapSquareTiles(LEVELS)
    var underwater = MapSquareTiles(1)

    var objects: MutableList<MapSquareObject> = mutableListOf()
    var underwaterObjects: MutableList<MapSquareObject> = mutableListOf()
    var npcSpawns: MutableList<MapSquareNpcSpawn> = mutableListOf()

    companion object {
        const val LEVELS = 4
    }
}
