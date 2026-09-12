package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.IDKType

class IDKEncoder : OpcodeEncoder<IDKType>() {

    override fun opcodes(definition: IDKType): IntArray {
        val opcodes = IntArrayList()
        if (definition.bodyPart != -1) {
            opcodes.add(1)
        }
        if (definition.modelIds != null) {
            opcodes.add(2)
        }
        if (definition.nonSelectable) {
            opcodes.add(3)
        }
        if (definition.recolorSrc != null) {
            opcodes.add(40)
        }
        if (definition.retextureSrc != null) {
            opcodes.add(41)
        }
        for (index in definition.headModelIds.indices) {
            if (definition.headModelIds[index] != -1) {
                opcodes.add(60 + index)
            }
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: IDKType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeByte(definition.bodyPart)
            2 -> {
                val models = definition.modelIds ?: IntArray(0)
                writeByte(models.size)
                for (model in models) {
                    writeBigSmart(model)
                }
            }
            3 -> Unit
            40 -> writePairs(definition.recolorSrc, definition.recolorDst)
            41 -> writePairs(definition.retextureSrc, definition.retextureDst)
            in 60..64 -> writeBigSmart(definition.headModelIds[opcode - 60])
            else -> error("Unhandled idk opcode $opcode in ${definition.id}")
        }
    }

    private fun Writer.writePairs(source: IntArray?, destination: IntArray?) {
        val src = source ?: IntArray(0)
        val dst = destination ?: IntArray(0)
        writeByte(src.size)
        for (index in src.indices) {
            writeShort(src[index])
            writeShort(dst[index])
        }
    }
}
