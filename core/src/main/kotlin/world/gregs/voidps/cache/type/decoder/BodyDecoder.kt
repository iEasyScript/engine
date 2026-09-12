package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.Index.DEFAULTS
import world.gregs.voidps.cache.type.data.BodyType

class BodyDecoder : TypeDecoder<BodyType>(DEFAULTS) {
    var definition: BodyType? = null

    override fun create(size: Int) = Array(size) { BodyType(it) }

    override fun getFile(id: Int) = 0

    override fun getArchive(id: Int) = 6

    override fun BodyType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> disabledSlots = IntArray(buffer.readUnsignedByte()) { buffer.readUnsignedByte() }
            3 -> unknown3 = buffer.readUnsignedByte()
            4 -> unknown4 = buffer.readUnsignedByte()
            5 -> unknown5 = IntArray(buffer.readUnsignedByte()) { buffer.readUnsignedByte() }
            6 -> unknown6 = IntArray(buffer.readUnsignedByte()) { buffer.readUnsignedByte() }
            else -> unknown(opcode, buffer)
        }
    }
}