package world.gregs.voidps.cache

import world.gregs.voidps.buffer.write.Writer

interface TypeEncoder<T : CacheType> {

    fun Writer.encode(definition: T)
}
