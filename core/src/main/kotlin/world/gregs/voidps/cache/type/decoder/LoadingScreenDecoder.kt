package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.LoadingScreenElement
import world.gregs.voidps.cache.type.data.LoadingScreenType

class LoadingScreenDecoder : TypeDecoder<LoadingScreenType>(Index.GAME_TIPS) {

    override fun create(size: Int) = Array(size) { LoadingScreenType(it) }

    override fun getFile(id: Int) = 0

    override fun readLoop(definition: LoadingScreenType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun LoadingScreenType.read(opcode: Int, buffer: Reader) = unknown(opcode, buffer)

    private fun LoadingScreenType.decode(buffer: Reader) {
        if (id == LoadingScreenType.MASTER) {
            format = buffer.readUnsignedByte()
            renderTypes = IntArray(buffer.readUnsignedByte()) { buffer.readUnsignedByte() }
            unknown1 = buffer.readUnsignedByte()
            unknown2 = buffer.readUnsignedByte()
            unknown3 = buffer.readUnsignedShort()
            unknown4 = buffer.readInt()
            unknown5 = buffer.readInt()
            unknown6 = buffer.readUnsignedByte()
            unknown7 = buffer.readUnsignedByte()
            unknown8 = buffer.readUnsignedByte()
            unknown9 = buffer.readUnsignedByte()
            unknown10 = buffer.readUnsignedShort()
            unknown11 = buffer.readUnsignedShort()
            unknown12 = buffer.readUnsignedByte()
            unknown13 = buffer.readUnsignedByte()
            unknown14 = buffer.readUnsignedMedium()
            unknown15 = buffer.readUnsignedShort()
            return
        }
        elements = Array(buffer.readUnsignedByte()) { readElement(buffer) }
    }

    private fun readElement(buffer: Reader): LoadingScreenElement {
        val type = buffer.readUnsignedByte()
        return when (type) {
            5 -> LoadingScreenElement(
                type,
                graphic = buffer.readUnsignedShort(),
                unknownA = buffer.readUnsignedByte(),
                unknownB = buffer.readUnsignedShort(),
                stage = buffer.readUnsignedByte(),
                unknownC = buffer.readUnsignedByte(),
                alpha = buffer.readUnsignedByte()
            )
            6 -> LoadingScreenElement(
                type,
                graphic = buffer.readUnsignedShort(),
                unknownA = buffer.readUnsignedByte(),
                unknownB = buffer.readUnsignedShort(),
                stage = buffer.readUnsignedByte(),
                unknownC = buffer.readUnsignedByte(),
                alpha = buffer.readUnsignedByte(),
                bytes = IntArray(3) { buffer.readUnsignedByte() }
            )
            7 -> LoadingScreenElement(
                type,
                text = buffer.readString(),
                unknownA = buffer.readUnsignedByte(),
                unknownB = buffer.readUnsignedShort(),
                stage = buffer.readUnsignedByte(),
                unknownC = buffer.readUnsignedByte(),
                bytes = IntArray(18) { buffer.readUnsignedByte() }
            )
            8 -> LoadingScreenElement(type, graphic = buffer.readUnsignedShort())
            10 -> LoadingScreenElement(
                type,
                unknownA = buffer.readUnsignedByte(),
                unknownB = buffer.readUnsignedShort(),
                stage = buffer.readUnsignedByte(),
                unknownC = buffer.readUnsignedByte(),
                ints = IntArray(2) { buffer.readInt() }
            )
            else -> error("Unhandled loading screen element $type at ${buffer.position()}")
        }
    }
}
