package world.gregs.voidps.cache.type

/**
 * A definition whose encoder reproduces its file byte for byte.
 *
 * Two things about an opcode keyed file survive nowhere in the decoded fields: the order Jagex's packer
 * emitted the records in, and the payload of a record a later record of the same opcode overwrote. These
 * two hold both, so the encoder can replay what the fields cannot say.
 */
interface OpcodeOrdered {

    /**
     * Every opcode the file held, in order, repeats included; null when that is exactly the order the
     * encoder derives from the fields on its own, which is the common shape and what a hand authored
     * definition gets for free.
     */
    var opcodeOrder: IntArray?

    /**
     * The payload of any record a later record of the same opcode overwrote, indexed to match
     * [opcodeOrder]; null when nothing was overwritten.
     */
    var shadowedPayloads: Array<ByteArray?>?

    /**
     * Whether [opcode] is one record per array element rather than a field written twice, and so is
     * written from the fields on every occurrence instead of being shadowed.
     */
    fun repeats(opcode: Int): Boolean = false
}
