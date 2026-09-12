package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.ConfigGroup76Block
import world.gregs.voidps.cache.type.data.ConfigGroup76Type
import world.gregs.voidps.cache.type.decoder.ConfigGroup76Decoder.Companion.BLOCK_ENTRIES
import world.gregs.voidps.cache.type.decoder.ConfigGroup76Decoder.Companion.FIRST_BLOCK_OPCODE

class ConfigGroup76Encoder : OpcodeEncoder<ConfigGroup76Type>() {

    override fun opcodes(definition: ConfigGroup76Type): IntArray {
        val opcodes = IntArrayList()
        with(definition) {
            if (diffuseMaterialA != null) opcodes.add(1)
            if (normalScaleA != null) opcodes.add(2)
            if (diffuseMaterialB != null) opcodes.add(3)
            if (normalScaleB != null) opcodes.add(4)
            if (unknown5 != null) opcodes.add(5)
            if (colour != null) opcodes.add(6)
            if (unknown7 != null) opcodes.add(7)
            if (unknown8 != null) opcodes.add(8)
            if (edgeMaterial != null) opcodes.add(9)
            if (edgeScale != null) opcodes.add(10)
            if (unknown11 != null) opcodes.add(11)
            if (packedColour != null) opcodes.add(12)
            if (unknown13 != null) opcodes.add(13)
            if (unknown14 != null) opcodes.add(14)
            if (unknown15 != null) opcodes.add(15)
            if (unknown16 != null) opcodes.add(16)
            if (unknown17 != null) opcodes.add(17)
            if (unknown18 != null) opcodes.add(18)
            if (unknown19 != null) opcodes.add(19)
            if (unknown20 != null) opcodes.add(20)
            if (unknown21 != null) opcodes.add(21)
            if (maskMaterial != null) opcodes.add(22)
            if (unknown23 != null) opcodes.add(23)
            if (unknown24 != null) opcodes.add(24)
            if (unknown25 != null) opcodes.add(25)
            if (unknown26 != null) opcodes.add(26)
            if (unknown27 != null) opcodes.add(27)
            if (unknown28 != null) opcodes.add(28)
            if (normalMaterialA != null) opcodes.add(29)
            if (normalMaterialB != null) opcodes.add(30)
            if (unusedMaterial != null) opcodes.add(31)
            if (unusedMaterialScale != null) opcodes.add(32)
            for (index in blocks.indices) {
                for (entry in present(blocks[index])) {
                    opcodes.add(FIRST_BLOCK_OPCODE + index * BLOCK_ENTRIES + entry)
                }
            }
            if (unknown81 != null) opcodes.add(81)
            if (unknown82 != null) opcodes.add(82)
            if (unknown83 != null) opcodes.add(83)
            if (discarded != null) opcodes.add(84)
            if (unknown85 != null) opcodes.add(85)
            if (unknown86 != null) opcodes.add(86)
            if (unknown87 != null) opcodes.add(87)
            if (unknown88 != null) opcodes.add(88)
            if (colourA != null) opcodes.add(89)
            if (unknown90 != null) opcodes.add(90)
            if (unknown91 != null) opcodes.add(91)
            if (unknown92 != null) opcodes.add(92)
            if (unknown93 != null) opcodes.add(93)
            if (unknown94 != null) opcodes.add(94)
            if (unknown95 != null) opcodes.add(95)
            if (unknown96 != null) opcodes.add(96)
            if (unknown97 != null) opcodes.add(97)
            if (colourB != null) opcodes.add(98)
            if (unknown99 != null) opcodes.add(99)
            if (unknown100 != null) opcodes.add(100)
            if (unknown101 != null) opcodes.add(101)
            if (unknown102 != null) opcodes.add(102)
            if (unknown103 != null) opcodes.add(103)
            if (unknown104 != null) opcodes.add(104)
            if (unknown105 != null) opcodes.add(105)
            if (unknown106 != null) opcodes.add(106)
            if (unknown107 != null) opcodes.add(107)
            if (unknown108 != null) opcodes.add(108)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: ConfigGroup76Type, opcode: Int, occurrence: Int) {
        if (opcode in FIRST_BLOCK_OPCODE until FIRST_BLOCK_OPCODE + ConfigGroup76Type.BLOCKS * BLOCK_ENTRIES) {
            encodeBlock(definition.blocks[(opcode - FIRST_BLOCK_OPCODE) / BLOCK_ENTRIES], (opcode - FIRST_BLOCK_OPCODE) % BLOCK_ENTRIES)
            return
        }
        with(definition) {
            when (opcode) {
                1 -> writeShort(diffuseMaterialA!!)
                2 -> writeShort(normalScaleA!!)
                3 -> writeShort(diffuseMaterialB!!)
                4 -> writeShort(normalScaleB!!)
                5 -> writeShort(unknown5!!)
                6 -> writeMedium(colour!!)
                7 -> for (value in unknown7!!) writeShort(value)
                8 -> writeShort(unknown8!!)
                9 -> writeShort(edgeMaterial!!)
                10 -> writeShort(edgeScale!!)
                11 -> writeShort(unknown11!!)
                12 -> writeInt(packedColour!!)
                13 -> writeShort(unknown13!!)
                14 -> writeShort(unknown14!!)
                15 -> writeInt(unknown15!!)
                16 -> writeShort(unknown16!!)
                17 -> writeShort(unknown17!!)
                18 -> writeByte(unknown18!!)
                19 -> writeByte(unknown19!!)
                20 -> writeShort(unknown20!!)
                21 -> writeShort(unknown21!!)
                22 -> writeShort(maskMaterial!!)
                23 -> writeShort(unknown23!!)
                24 -> writeByte(unknown24!!)
                25 -> writeShort(unknown25!!)
                26 -> for (value in unknown26!!) writeShort(value)
                27 -> writeShort(unknown27!!)
                28 -> writeShort(unknown28!!)
                29 -> writeShort(normalMaterialA!!)
                30 -> writeShort(normalMaterialB!!)
                31 -> writeShort(unusedMaterial!!)
                32 -> writeShort(unusedMaterialScale!!)
                81 -> writeFloat(unknown81!!)
                82 -> writeFloat(unknown82!!)
                83 -> writeFloat(unknown83!!)
                84 -> writeFloat(discarded!!)
                85 -> writeByte(unknown85!!)
                86 -> writeShort(unknown86!!)
                87 -> writeShort(unknown87!!)
                88 -> for (value in unknown88!!) writeFloat(value)
                89 -> writeInt(colourA!!)
                90 -> writeFloat(unknown90!!)
                91 -> writeFloat(unknown91!!)
                92 -> writeByte(unknown92!!)
                93 -> writeFloat(unknown93!!)
                94 -> writeFloat(unknown94!!)
                95 -> writeFloat(unknown95!!)
                96 -> writeByte(unknown96!!)
                97 -> for (value in unknown97!!) writeFloat(value)
                98 -> writeInt(colourB!!)
                99 -> writeFloat(unknown99!!)
                100 -> writeFloat(unknown100!!)
                101 -> writeFloat(unknown101!!)
                102 -> writeFloat(unknown102!!)
                103 -> writeFloat(unknown103!!)
                104 -> writeFloat(unknown104!!)
                105 -> writeFloat(unknown105!!)
                106 -> writeFloat(unknown106!!)
                107 -> writeFloat(unknown107!!)
                108 -> writeFloat(unknown108!!)
            }
        }
    }

    private fun Writer.encodeBlock(block: ConfigGroup76Block, entry: Int) {
        when (entry) {
            0 -> writeByte(block.enabled!!)
            1 -> writeFloat(block.scalarA!!)
            2 -> writeFloat(block.scalarB!!)
            3 -> writeFloat(block.scalarC!!)
            4 -> for (value in block.pairA!!) writeFloat(value)
            5 -> for (value in block.pairB!!) writeFloat(value)
            6 -> writeFloat(block.scalarD!!)
            7 -> writeFloat(block.scalarE!!)
        }
    }

    private fun present(block: ConfigGroup76Block): IntArray {
        val entries = IntArrayList(BLOCK_ENTRIES)
        if (block.enabled != null) entries.add(0)
        if (block.scalarA != null) entries.add(1)
        if (block.scalarB != null) entries.add(2)
        if (block.scalarC != null) entries.add(3)
        if (block.pairA != null) entries.add(4)
        if (block.pairB != null) entries.add(5)
        if (block.scalarD != null) entries.add(6)
        if (block.scalarE != null) entries.add(7)
        return entries.toIntArray()
    }
}
