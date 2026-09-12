package world.gregs.voidps.cache.type.decoder

import org.projectx.core.Logger.logWarn
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.DecodeReport
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.type.data.MapSquareType

/** One map square's files, read through the lossless per-file decoders. */
class MapDecoder {

    /** Set to collect decode telemetry; leave null to log failures instead. */
    var report: DecodeReport? = null

    fun decode(cache: Cache, mapSquareId: Int): MapSquareType? {
        val archive = archive(mapSquareId)
        if (!cache.exists(Index.MAPS, archive)) {
            return null
        }
        val definition = MapSquareType(mapSquareId)
        decodeFile(cache, archive, MapSquare.File.LEGACY_TILES, definition) { data ->
            definition.surface = MapSquareTileDecoder.decode(data, MapSquareType.LEVELS)
        }
        decodeFile(cache, archive, MapSquare.File.LEGACY_UNDERWATER_TILES, definition) { data ->
            definition.underwater = MapSquareTileDecoder.decode(data, 1)
        }
        decodeFile(cache, archive, MapSquare.File.LOCS, definition) { data ->
            definition.objects = MapSquareLocDecoder.decode(data)
        }
        decodeFile(cache, archive, MapSquare.File.UNDERWATER_LOCS, definition) { data ->
            definition.underwaterObjects = MapSquareLocDecoder.decode(data)
        }
        decodeFile(cache, archive, MapSquare.File.NPCS, definition) { data ->
            definition.npcSpawns = MapSquareNpcDecoder.decode(data)
        }
        return definition
    }

    private fun decodeFile(
        cache: Cache,
        archive: Int,
        file: MapSquare.File,
        definition: MapSquareType,
        decode: (ByteArray) -> Unit,
    ) {
        val data = cache.data(Index.MAPS, archive, file.id)
        if (data == null || data.isEmpty()) {
            return
        }
        val type = "MapDecoder.${file.file}"
        try {
            decode(data)
        } catch (e: RuntimeException) {
            val report = report
            if (report == null) {
                logWarn("Map square ${definition.id} file ${file.file} failed", e)
            } else {
                report.failure(type, definition.id, e)
            }
            return
        }
        report?.record(type, definition.id, emptyList(), 0)
    }

    companion object {
        fun archive(mapSquareId: Int): Int = MapSquare.archive(mapSquareId shr 8, mapSquareId and 0xff)
    }
}
