package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.HuffmanType

class HuffmanDecoder : TypeDecoder<HuffmanType>(INDEX) {

    override fun create(size: Int) = Array(size) { HuffmanType(it) }

    /** One entity per archive, single file, archives numbered from 1 - not the 128-file config layout. */
    override fun size(cache: Cache) = cache.lastArchiveId(index)

    override fun getFile(id: Int) = 0

    override fun readLoop(definition: HuffmanType, buffer: Reader) {
        recordDecode(definition.id, buffer) {
            definition.codeLengths = IntArray(buffer.readableBytes()) { buffer.readUnsignedByte() }
        }
    }

    override fun HuffmanType.read(opcode: Int, buffer: Reader) = Unit

    companion object {
        const val INDEX = 10
    }
}
