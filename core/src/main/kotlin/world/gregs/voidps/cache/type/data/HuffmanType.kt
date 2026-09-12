package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/** The chat Huffman table: one canonical code length per byte value, and nothing else. */
data class HuffmanType(
    override var id: Int = -1,
    var codeLengths: IntArray = IntArray(0),
) : CacheType
