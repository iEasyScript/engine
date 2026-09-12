package world.gregs.voidps.cache.file.type

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.file.FileProvider

/**
 * Reads all [Cache] sectors into memory as raw container data.
 * Fast read speed, high memory usage.
 *
 * Block framing for the NXT JS5 protocol is handled by [FileProvider.serve].
 */
class MemoryFileProvider(cache: Cache) : FileProvider {
    private val sectors: Array<Array<ByteArray?>?> = arrayOfNulls(256)

    init {
        val index255 = arrayOfNulls<ByteArray>(256)
        sectors[255] = index255
        index255[255] = cache.versionTable
        for (index in cache.indices()) {
            val archives = arrayOfNulls<ByteArray>(cache.lastArchiveId(index) + 1)
            sectors[index] = archives
            for (archive in cache.archives(index)) {
                archives[archive] = cache.sector(index, archive) ?: continue
            }
            index255[index] = cache.sector(255, index) ?: continue
        }
    }

    override fun data(index: Int, archive: Int): ByteArray? {
        return sectors.getOrNull(index)?.getOrNull(archive)
    }
}
