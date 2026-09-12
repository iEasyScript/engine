package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.type.data.WorldMapColoursType
import world.gregs.voidps.cache.type.data.WorldmapSquareType
import world.gregs.voidps.cache.type.data.WorldmapSquareType.Companion.ZONES_PER_SQUARE
import world.gregs.voidps.cache.type.decoder.WorldMapColoursDecoder.Companion.COLOURS_ARCHIVE

/** A map square's colour grid resolved to the world area each of its zones belongs to. */
class WorldmapSquareDecoder(private val colourToArea: (Int) -> Int?) {

    private val decoder = WorldMapColoursDecoder()

    fun decode(cache: Cache, mapSquareId: Int): WorldmapSquareType? {
        val data = cache.data(Index.WORLD_MAP, COLOURS_ARCHIVE, mapSquareId) ?: return null
        val definition = WorldMapColoursType(mapSquareId)
        decoder.readLoop(definition, BufferReader(data))
        val colours = definition.zoneColours()
        return WorldmapSquareType(mapSquareId, IntArray(ZONES_PER_SQUARE) { colourToArea(colours[it]) ?: DEFAULT_AREA })
    }

    private companion object {
        const val DEFAULT_AREA = 1
    }
}
