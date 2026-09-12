package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.INVENTORIES
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.InvType
import world.gregs.voidps.cache.type.encoder.InventoryEncoder

class InventoryDecoder : ConfigDecoder<InvType>(INVENTORIES) {

    override fun create(size: Int) = Array(size) { InvType(it) }

    private val encoder = InventoryEncoder()

    override fun canonicalOpcodes(definition: InvType): IntArray = encoder.opcodes(definition)

    override fun InvType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            2 -> length = buffer.readUnsignedShort()
            4 -> {
                val size = buffer.readUnsignedByte()
                ids = IntArray(size)
                amounts = IntArray(size)
                for (i in 0 until size) {
                    ids!![i] = buffer.readUnsignedShort()
                    amounts!![i] = buffer.readUnsignedShort()
                }
            }
            21 -> {
                val size = buffer.readUnsignedByte()
                ids = IntArray(size)
                amounts = IntArray(size)
                for (i in 0 until size) {
                    ids!![i] = buffer.readUnsignedMedium()
                    amounts!![i] = buffer.readUnsignedShort()
                }
            }
            else -> unknown(opcode, buffer)
        }
    }
}