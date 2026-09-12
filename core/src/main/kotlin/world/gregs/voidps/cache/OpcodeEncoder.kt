package world.gregs.voidps.cache

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.type.OpcodeOrdered

/**
 * Writes an opcode keyed definition file: every record the definition carries, then the 0 terminator.
 *
 * The encode is driven by a list of opcodes rather than by a fixed sequence of `if (field != default)`
 * blocks - [OpcodeOrdered.opcodeOrder] when the file had an order of its own, [opcodes] when it did not -
 * so [encodeOpcode] only ever writes one payload and never decides whether to write a record at all.
 */
abstract class OpcodeEncoder<T> : TypeEncoder<T> where T : CacheType, T : OpcodeOrdered {

    final override fun Writer.encode(definition: T) {
        val order = definition.opcodeOrder ?: opcodes(definition)
        val shadowed = definition.shadowedPayloads
        val occurrences = HashMap<Int, Int>(order.size)
        for (index in order.indices) {
            val opcode = order[index]
            val occurrence = occurrences.merge(opcode, 1, Int::plus)!! - 1
            writeByte(opcode)
            val payload = if (shadowed == null || index >= shadowed.size) null else shadowed[index]
            if (payload != null) {
                writeBytes(payload)
            } else {
                encodeOpcode(definition, opcode, occurrence)
            }
        }
        writeByte(0)
    }

    /**
     * Every opcode this encoder writes for [definition] on its own, in the order it writes them, an
     * opcode [OpcodeOrdered.repeats] declares repeatable listed once per element.
     */
    abstract fun opcodes(definition: T): IntArray

    /**
     * Writes one record's payload, the opcode byte already written.
     *
     * @param occurrence how many records of this opcode came before it, for the opcodes that repeat one
     * record per array element
     */
    protected abstract fun Writer.encodeOpcode(definition: T, opcode: Int, occurrence: Int)
}
