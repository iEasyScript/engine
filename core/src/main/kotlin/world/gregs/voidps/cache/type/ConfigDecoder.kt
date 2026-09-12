package world.gregs.voidps.cache.type

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.Index

abstract class ConfigDecoder<T : CacheType>(internal val archive: Int) : TypeDecoder<T>(Index.CONFIGS) {

    override fun getArchive(id: Int) = archive

    override fun readId(reader: Reader): Int {
        return reader.readShort()
    }

    override fun size(cache: Cache): Int {
        return cache.lastFileId(Index.CONFIGS, archive)
    }
}