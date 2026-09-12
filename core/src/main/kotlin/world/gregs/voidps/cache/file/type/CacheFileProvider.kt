package world.gregs.voidps.cache.file.type

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.file.FileProvider

/**
 * Reads sectors directly from the [cache] on each request.
 * Average read speeds, low memory usage.
 *
 * Block framing for the NXT JS5 protocol is handled by [FileProvider.serve].
 */
class CacheFileProvider(private val cache: Cache) : FileProvider {
    override fun data(index: Int, archive: Int): ByteArray? {
        if (index == 255 && archive == 255)
            return cache.versionTable
        return cache.sector(index, archive)
    }
}
