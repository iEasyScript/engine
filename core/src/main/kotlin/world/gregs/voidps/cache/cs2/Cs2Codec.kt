package world.gregs.voidps.cache.cs2

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.type.ClientScriptCodec
import world.gregs.voidps.cache.type.data.ClientScriptType

/**
 * Splits a clientscript's instruction stream into instructions and puts it back together.
 *
 * The framing around the stream is [ClientScriptCodec]'s; only the walk across it belongs here,
 * because only here is the operand-width table that says how wide each instruction is.
 */
object Cs2Codec {

    fun decode(data: ByteArray): Cs2Script = decode(ClientScriptCodec.decode(data))

    /** Splits a container whose framing has already been read, so a caller holding one need not re-read it. */
    fun decode(container: ClientScriptType): Cs2Script {
        val reader = BufferReader(container.instructions)
        val instructions = ArrayList<Cs2Instruction>(container.instructionCount)
        while (reader.position() < container.instructions.size) {
            val opcodeId = reader.readUnsignedShort()
            val op = Cs2Opcodes.find(opcodeId)
                ?: error("Unknown CS2 opcode $opcodeId at byte ${reader.position() - 2}")
            instructions.add(readOperand(op, reader))
        }
        require(instructions.size == container.instructionCount) {
            "Decoded ${instructions.size} instructions but the header declares ${container.instructionCount}"
        }

        return Cs2Script(
            name = container.name,
            instructions = instructions,
            intLocalsCount = container.intLocalCount,
            stringLocalsCount = container.stringLocalCount,
            longLocalsCount = container.longLocalCount,
            intArgsCount = container.intArgumentCount,
            stringArgsCount = container.stringArgumentCount,
            longArgsCount = container.longArgumentCount,
            switchTables = container.switchTables,
        )
    }

    fun encode(script: Cs2Script): ByteArray {
        val writer = BufferWriter(instructionBytes(script))
        for (instruction in script.instructions) {
            writer.writeShort(instruction.op.id)
            writeOperand(instruction, writer)
        }
        return ClientScriptCodec.encode(
            ClientScriptType(
                name = script.name,
                instructions = writer.toArray(),
                instructionCount = script.instructions.size,
                intLocalCount = script.intLocalsCount,
                stringLocalCount = script.stringLocalsCount,
                longLocalCount = script.longLocalsCount,
                intArgumentCount = script.intArgsCount,
                stringArgumentCount = script.stringArgsCount,
                longArgumentCount = script.longArgsCount,
                switchTables = script.switchTables,
            )
        )
    }

    /** Upper bound on the encoded instruction stream, since the writer cannot grow. */
    private fun instructionBytes(script: Cs2Script): Int {
        var size = 0
        for (instruction in script.instructions) {
            val op = instruction.op
            size += 2 + (op.operand.fixedBytes ?: ((instruction.strOperand?.length ?: 0) + 10))
        }
        return size
    }

    private fun readOperand(op: Cs2Op, reader: BufferReader): Cs2Instruction = when (op.operand) {
        Cs2Operand.BYTE -> Cs2Instruction(op, intOperand = reader.readUnsignedByte())
        Cs2Operand.TRIBYTE -> Cs2Instruction(op, intOperand = reader.readUnsignedMedium())
        Cs2Operand.INT, Cs2Operand.VAR, Cs2Operand.WIDE_VARBIT -> Cs2Instruction(op, intOperand = reader.readInt())
        Cs2Operand.LONG -> Cs2Instruction(op, longOperand = reader.readLong())
        Cs2Operand.STRING -> Cs2Instruction(op, strOperand = reader.readString())
        Cs2Operand.TAGGED -> when (val tag = reader.readUnsignedByte()) {
            Cs2PushTag.INT -> Cs2Instruction(op, intOperand = reader.readInt(), pushTag = tag)
            Cs2PushTag.LONG -> Cs2Instruction(op, longOperand = reader.readLong(), pushTag = tag)
            Cs2PushTag.STRING -> Cs2Instruction(op, strOperand = reader.readString(), pushTag = tag)
            else -> Cs2Instruction(op, pushTag = tag)
        }
    }

    private fun writeOperand(instruction: Cs2Instruction, writer: BufferWriter) {
        when (instruction.op.operand) {
            Cs2Operand.BYTE -> writer.writeByte(instruction.intOperand)
            Cs2Operand.TRIBYTE -> writer.writeMedium(instruction.intOperand)
            Cs2Operand.INT, Cs2Operand.VAR, Cs2Operand.WIDE_VARBIT -> writer.writeInt(instruction.intOperand)
            Cs2Operand.LONG -> writer.writeLong(instruction.longOperand)
            Cs2Operand.STRING -> writer.writeString(instruction.strOperand ?: "")
            Cs2Operand.TAGGED -> {
                writer.writeByte(instruction.pushTag)
                when (instruction.pushTag) {
                    Cs2PushTag.INT -> writer.writeInt(instruction.intOperand)
                    Cs2PushTag.LONG -> writer.writeLong(instruction.longOperand)
                    Cs2PushTag.STRING -> writer.writeString(instruction.strOperand ?: "")
                }
            }
        }
    }
}
