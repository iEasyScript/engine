package world.gregs.voidps.cache

import world.gregs.voidps.buffer.read.BufferReader

class Types<T : CacheType>(
    private val decoder: TypeDecoder<T>,
    private val cache: Cache,
    private val dependencies: ((T) -> IntArray)? = null,
) {
    private val size = decoder.size(cache) + 1
    private val definitions: Array<T> = decoder.create(if (size > 0) size else 0)
    private val decoded = BooleanArray(definitions.size)

    fun getOrNull(id: Int): T? {
        if (id < 0 || id >= definitions.size) {
            return null
        }
        if (!decoded[id]) {
            synchronized(this) {
                if (!decoded[id]) {
                    decode(id)
                }
            }
        }
        return definitions[id]
    }

    private fun decode(id: Int) {
        decoded[id] = true
        val dependencies = dependencies
        if (dependencies == null) {
            decoder.load(definitions, cache, id)
            return
        }
        val data = cache.data(decoder.index, decoder.getArchive(id), decoder.getFile(id)) ?: return
        val definition = definitions[id]
        decoder.readLoop(definition, BufferReader(data))
        for (dependency in dependencies(definition)) {
            if (dependency in 0 until definitions.size && !decoded[dependency]) {
                decode(dependency)
            }
        }
        decoder.changeValues(definitions, definition)
    }
}
