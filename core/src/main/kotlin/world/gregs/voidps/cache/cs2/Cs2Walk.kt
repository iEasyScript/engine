package world.gregs.voidps.cache.cs2

import world.gregs.voidps.buffer.read.BufferReader

/**
 * One clientscript's instruction stream, read with a width table rather than an
 * installed opcode set.
 *
 * The cross-build work needs to disassemble two builds at once, each with its
 * own numbering, which the globally installed table cannot express. Only the
 * shape matters here - which opcode sits where, and how wide its operand is -
 * so nothing is decoded beyond that.
 */
class Cs2Walked(
    val id: Int,
    val start: Int,
    val end: Int,
    /** Byte offset of each instruction's opcode field. */
    val offsets: IntArray,
    val opcodes: IntArray,
    val widths: Array<Cs2Operand>,
) {
    val size: Int get() = opcodes.size

    /** The operand-width sequence, which two builds of the same script share. */
    fun widthKey(): String = widths.joinToString(",") { it.ordinal.toString() }
}

object Cs2Walk {

    private const val FOOTER_BYTES = 18

    /**
     * Walks [raw] under [widths], or null when it does not resolve.
     *
     * A null is not a diagnosis: the widths may be wrong, the script may use an
     * opcode the table has never seen, or the file may be a stub. Every caller
     * here treats it the same way - as a script that cannot vote.
     */
    fun walk(id: Int, raw: ByteArray, widths: Map<Int, Cs2Operand>): Cs2Walked? {
        if (raw.size <= FOOTER_BYTES) return null
        val reader = BufferReader(raw)
        reader.position(raw.size - 2)
        val switchBlockSize = reader.readUnsignedShort()
        val end = raw.size - FOOTER_BYTES - switchBlockSize
        if (end < 1 || end >= raw.size) return null
        reader.position(end)
        val instructionCount = reader.readInt()
        if (instructionCount < 0 || instructionCount * 3 > end) return null

        var start = 0
        while (start < raw.size && raw[start] != 0.toByte()) start++
        start++
        if (start > end) return null

        val offsets = IntArray(instructionCount)
        val opcodes = IntArray(instructionCount)
        val widthsAt = arrayOfNulls<Cs2Operand>(instructionCount)
        var at = start
        var index = 0
        while (at != end) {
            if (index == instructionCount || at + 2 > end) return null
            val opcode = ((raw[at].toInt() and 0xFF) shl 8) or (raw[at + 1].toInt() and 0xFF)
            val width = widths[opcode] ?: return null
            val next = advance(raw, at, end, width)
            if (next < 0) return null
            offsets[index] = at
            opcodes[index] = opcode
            widthsAt[index] = width
            index++
            at = next
        }
        if (index != instructionCount) return null
        @Suppress("UNCHECKED_CAST")
        return Cs2Walked(id, start, end, offsets, opcodes, widthsAt as Array<Cs2Operand>)
    }

    /** Rewrites every opcode field through [renumber], leaving all other bytes alone. */
    fun renumber(raw: ByteArray, walked: Cs2Walked, renumber: (Int) -> Int): ByteArray {
        val out = raw.copyOf()
        for (index in walked.opcodes.indices) {
            val id = renumber(walked.opcodes[index])
            val at = walked.offsets[index]
            out[at] = (id ushr 8).toByte()
            out[at + 1] = id.toByte()
        }
        return out
    }

    private fun advance(raw: ByteArray, at: Int, end: Int, width: Cs2Operand): Int {
        val next = when (width) {
            Cs2Operand.BYTE -> at + 3
            Cs2Operand.TRIBYTE -> at + 5
            Cs2Operand.INT, Cs2Operand.VAR, Cs2Operand.WIDE_VARBIT -> at + 6
            Cs2Operand.LONG -> at + 10
            Cs2Operand.STRING -> terminator(raw, at + 2, end)
            Cs2Operand.TAGGED -> when (raw[at + 2].toInt() and 0xFF) {
                Cs2PushTag.INT -> at + 7
                Cs2PushTag.LONG -> at + 11
                Cs2PushTag.STRING -> terminator(raw, at + 3, end)
                else -> -1
            }
        }
        return if (next in 0..end) next else -1
    }

    private fun terminator(raw: ByteArray, from: Int, end: Int): Int {
        var at = from
        while (at < end && raw[at] != 0.toByte()) at++
        return if (at < end) at + 1 else -1
    }
}
