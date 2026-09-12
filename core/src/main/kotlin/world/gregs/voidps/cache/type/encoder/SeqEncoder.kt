package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.SeqType

/** Writes an index 20 seq file, whose frame ids are split across two short arrays. */
class SeqEncoder : OpcodeEncoder<SeqType>() {

    override fun opcodes(definition: SeqType): IntArray {
        val opcodes = IntArrayList()
        if (definition.frames != null) {
            opcodes.add(1)
        }
        if (definition.loopOffset != -1) {
            opcodes.add(2)
        }
        if (definition.priority != 5) {
            opcodes.add(5)
        }
        if (definition.leftHandItem != -1) {
            opcodes.add(6)
        }
        if (definition.rightHandItem != -1) {
            opcodes.add(7)
        }
        if (definition.maxLoops != 99) {
            opcodes.add(8)
        }
        if (definition.animatingPrecedence != -1) {
            opcodes.add(9)
        }
        if (definition.walkingPrecedence != -1) {
            opcodes.add(10)
        }
        if (definition.replayMode != 2) {
            opcodes.add(11)
        }
        if (definition.params != null) {
            opcodes.add(249)
        }
        opcodes.addUnused(definition)
        return opcodes.toIntArray().also { it.sort() }
    }

    override fun Writer.encodeOpcode(definition: SeqType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> {
                val frames = definition.frames ?: IntArray(0)
                val durations = definition.durations ?: IntArray(0)
                writeShort(frames.size)
                for (duration in durations) {
                    writeShort(duration)
                }
                for (frame in frames) {
                    writeShort(frame and SHORT)
                }
                for (frame in frames) {
                    writeShort(frame ushr 16)
                }
            }
            2 -> writeShort(definition.loopOffset)
            5 -> writeByte(definition.priority)
            6 -> writeShort(definition.leftHandItem)
            7 -> writeShort(definition.rightHandItem)
            8 -> writeByte(definition.maxLoops)
            9 -> writeByte(definition.animatingPrecedence)
            10 -> writeByte(definition.walkingPrecedence)
            11 -> writeByte(definition.replayMode)
            249 -> writeParams(definition)
            else -> writeUnused(definition, opcode, occurrence)
        }
    }

    private companion object {
        const val SHORT = 0xffff
    }
}
