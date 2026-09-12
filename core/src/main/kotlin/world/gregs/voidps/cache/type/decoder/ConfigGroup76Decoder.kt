package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.ConfigGroup76Block
import world.gregs.voidps.cache.type.data.ConfigGroup76Type
import world.gregs.voidps.cache.type.encoder.ConfigGroup76Encoder

class ConfigGroup76Decoder : ConfigDecoder<ConfigGroup76Type>(ARCHIVE) {

    override fun create(size: Int) = Array(size) { ConfigGroup76Type(it) }

    private val encoder = ConfigGroup76Encoder()

    override fun canonicalOpcodes(definition: ConfigGroup76Type): IntArray = encoder.opcodes(definition)

    override fun ConfigGroup76Type.read(opcode: Int, buffer: Reader) {
        if (opcode in FIRST_BLOCK_OPCODE until FIRST_BLOCK_OPCODE + ConfigGroup76Type.BLOCKS * BLOCK_ENTRIES) {
            readBlock(blocks[(opcode - FIRST_BLOCK_OPCODE) / BLOCK_ENTRIES], (opcode - FIRST_BLOCK_OPCODE) % BLOCK_ENTRIES, buffer)
            return
        }
        when (opcode) {
            1 -> diffuseMaterialA = buffer.readUnsignedShort()
            2 -> normalScaleA = buffer.readUnsignedShort()
            3 -> diffuseMaterialB = buffer.readUnsignedShort()
            4 -> normalScaleB = buffer.readUnsignedShort()
            5 -> unknown5 = buffer.readUnsignedShort()
            6 -> colour = buffer.readUnsignedMedium()
            7 -> unknown7 = IntArray(2) { buffer.readUnsignedShort() }
            8 -> unknown8 = buffer.readUnsignedShort()
            9 -> edgeMaterial = buffer.readUnsignedShort()
            10 -> edgeScale = buffer.readUnsignedShort()
            11 -> unknown11 = buffer.readUnsignedShort()
            12 -> packedColour = buffer.readInt()
            13 -> unknown13 = buffer.readUnsignedShort()
            14 -> unknown14 = buffer.readUnsignedShort()
            15 -> unknown15 = buffer.readInt()
            16 -> unknown16 = buffer.readUnsignedShort()
            17 -> unknown17 = buffer.readUnsignedShort()
            18 -> unknown18 = buffer.readUnsignedByte()
            19 -> unknown19 = buffer.readUnsignedByte()
            20 -> unknown20 = buffer.readUnsignedShort()
            21 -> unknown21 = buffer.readUnsignedShort()
            22 -> maskMaterial = buffer.readUnsignedShort()
            23 -> unknown23 = buffer.readUnsignedShort()
            24 -> unknown24 = buffer.readUnsignedByte()
            25 -> unknown25 = buffer.readUnsignedShort()
            26 -> unknown26 = IntArray(3) { buffer.readUnsignedShort() }
            27 -> unknown27 = buffer.readUnsignedShort()
            28 -> unknown28 = buffer.readShort()
            29 -> normalMaterialA = buffer.readUnsignedShort()
            30 -> normalMaterialB = buffer.readUnsignedShort()
            31 -> unusedMaterial = buffer.readUnsignedShort()
            32 -> unusedMaterialScale = buffer.readUnsignedShort()
            81 -> unknown81 = buffer.readFloat()
            82 -> unknown82 = buffer.readFloat()
            83 -> unknown83 = buffer.readFloat()
            84 -> discarded = buffer.readFloat()
            85 -> unknown85 = buffer.readUnsignedByte()
            86 -> unknown86 = buffer.readUnsignedShort()
            87 -> unknown87 = buffer.readUnsignedShort()
            88 -> unknown88 = List(2) { buffer.readFloat() }
            89 -> colourA = buffer.readInt()
            90 -> unknown90 = buffer.readFloat()
            91 -> unknown91 = buffer.readFloat()
            92 -> unknown92 = buffer.readUnsignedByte()
            93 -> unknown93 = buffer.readFloat()
            94 -> unknown94 = buffer.readFloat()
            95 -> unknown95 = buffer.readFloat()
            96 -> unknown96 = buffer.readUnsignedByte()
            97 -> unknown97 = List(3) { buffer.readFloat() }
            98 -> colourB = buffer.readInt()
            99 -> unknown99 = buffer.readFloat()
            100 -> unknown100 = buffer.readFloat()
            101 -> unknown101 = buffer.readFloat()
            102 -> unknown102 = buffer.readFloat()
            103 -> unknown103 = buffer.readFloat()
            104 -> unknown104 = buffer.readFloat()
            105 -> unknown105 = buffer.readFloat()
            106 -> unknown106 = buffer.readFloat()
            107 -> unknown107 = buffer.readFloat()
            108 -> unknown108 = buffer.readFloat()
            else -> unknown(opcode, buffer)
        }
    }

    private fun readBlock(block: ConfigGroup76Block, entry: Int, buffer: Reader) {
        when (entry) {
            0 -> block.enabled = buffer.readUnsignedByte()
            1 -> block.scalarA = buffer.readFloat()
            2 -> block.scalarB = buffer.readFloat()
            3 -> block.scalarC = buffer.readFloat()
            4 -> block.pairA = List(2) { buffer.readFloat() }
            5 -> block.pairB = List(2) { buffer.readFloat() }
            6 -> block.scalarD = buffer.readFloat()
            7 -> block.scalarE = buffer.readFloat()
        }
    }

    companion object {
        const val ARCHIVE = 76

        internal const val FIRST_BLOCK_OPCODE = 33

        internal const val BLOCK_ENTRIES = 8
    }
}
