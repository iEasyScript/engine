package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index.BILLBOARDS
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.BillboardType
import world.gregs.voidps.cache.type.encoder.BillboardEncoder

class BillboardDecoder : TypeDecoder<BillboardType>(BILLBOARDS) {

    private val encoder = BillboardEncoder()

    override fun create(size: Int) = Array(size) { BillboardType(it) }

    override fun getArchive(id: Int) = id ushr 10

    override fun getFile(id: Int) = id and 0x3ff

    override fun size(cache: Cache): Int {
        val lastArchive = cache.lastArchiveId(index)
        return (lastArchive shl 10) or cache.lastFileId(index, lastArchive)
    }

    override fun canonicalOpcodes(definition: BillboardType): IntArray = encoder.opcodes(definition)

    override fun BillboardType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> material = buffer.readUnsignedShort()
            2 -> {
                size2d = buffer.readUnsignedShort()
                size3d = buffer.readUnsignedShort()
            }
            3 -> unknown3 = buffer.readUnsignedByte()
            4 -> unknown4 = buffer.readUnsignedByte()
            5 -> unknown5 = buffer.readUnsignedByte()
            7 -> unknown7 = true
            else -> unknown(opcode, buffer)
        }
    }
}
