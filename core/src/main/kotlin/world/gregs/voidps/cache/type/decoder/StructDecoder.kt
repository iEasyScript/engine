package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.type.data.StructType
import world.gregs.voidps.cache.type.encoder.StructEncoder

class StructDecoder : TypeDecoder<StructType>(Index.STRUCTS) {
    override fun create(size: Int) = Array(size) { StructType(it) }

    private val encoder = StructEncoder()

    override fun canonicalOpcodes(definition: StructType): IntArray = encoder.opcodes(definition)

    override fun getArchive(id: Int) = id ushr 5

    override fun getFile(id: Int) = id and 0x1f

    override fun size(cache: Cache): Int {
        val lastArchive = cache.lastArchiveId(index)
        return lastArchive * 32 + cache.fileCount(index, lastArchive)
    }

    override fun StructType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            PARAMETERS -> readParameters(buffer)
            else -> unknown(opcode, buffer)
        }
    }

    private companion object {
        const val PARAMETERS = 249
    }
}
