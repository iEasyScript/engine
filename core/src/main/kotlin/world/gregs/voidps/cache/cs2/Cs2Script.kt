package world.gregs.voidps.cache.cs2

import world.gregs.voidps.cache.cs2.ir.Cs2Type
import world.gregs.voidps.cache.type.data.ClientScriptSwitchCase

/**
 * A single decoded CS2 instruction.
 *
 * Which operand field is meaningful follows [Cs2Op.operand]. A tagged push
 * carries its selector in [pushTag], because a tag the dispatcher does not
 * recognise has no payload at all and still has to survive re-encoding.
 *
 * [Cs2Operand.TRIBYTE] and [Cs2Operand.VAR] keep their whole operand packed in
 * [intOperand]; [Cs2Packing] takes it apart.
 */
data class Cs2Instruction(
    val op: Cs2Op,
    val intOperand: Int = 0,
    val strOperand: String? = null,
    val longOperand: Long = 0,
    val pushTag: Int = Cs2PushTag.NONE,
) {
    override fun toString(): String {
        // One opcode pushes an int, a long or a string depending on its tag, so
        // a listing that hides the tag reads as the wrong stack.
        val name = if (op.operand == Cs2Operand.TAGGED) "${op.opName}${tagName()}" else op.opName
        return when {
            strOperand != null -> "$name \"${Cs2Strings.escape(strOperand)}\""
            pushTag == Cs2PushTag.LONG || op.operand == Cs2Operand.LONG -> "$name $longOperand"
            pushTag == Cs2PushTag.NONE && op.operand == Cs2Operand.TAGGED -> name
            else -> "$name $intOperand"
        }
    }

    private fun tagName(): String = when (pushTag) {
        Cs2PushTag.INT -> "<int>"
        Cs2PushTag.LONG -> "<long>"
        Cs2PushTag.STRING -> "<string>"
        else -> ""
    }
}

/**
 * The int this instruction pushes as a constant, or null when it pushes none.
 *
 * RS3 spells nearly every constant as a tagged push, so reading only the
 * dedicated int push would miss almost all of them.
 */
val Cs2Instruction.intConstant: Int?
    get() = when {
        op.operand == Cs2Operand.TAGGED -> intOperand.takeIf { pushTag == Cs2PushTag.INT }
        op.opName == "PUSH_CONSTANT_INT" -> intOperand
        else -> null
    }

typealias Cs2SwitchCase = ClientScriptSwitchCase

/**
 * A decoded clientscript.
 *
 * Field order mirrors the on-disk header so the encoder can walk it directly.
 * [switchTables] preserves both table order and within-table case order, which
 * the client does not care about but byte-identical re-encoding does.
 */
class Cs2Script(
    /** Script name from the file header, or null when the file stores a bare 0 byte. */
    val name: String?,
    val instructions: List<Cs2Instruction>,
    val intLocalsCount: Int,
    val stringLocalsCount: Int,
    val longLocalsCount: Int,
    val intArgsCount: Int,
    val stringArgsCount: Int,
    val longArgsCount: Int,
    val switchTables: List<List<Cs2SwitchCase>>,
) {
    val size: Int get() = instructions.size

    operator fun get(index: Int): Cs2Instruction = instructions[index]

    /**
     * Number of values this script leaves on each stack for its caller.
     * Filled in by interprocedural analysis; null until then.
     */
    var returnSignature: ReturnSignature? = null

    /**
     * Types of the returned values in the order they were pushed. The grouped
     * counts in [returnSignature] cannot express that order, and callers have to
     * destructure the results in it.
     */
    var returnOrder: List<Cs2Type> = emptyList()

    /**
     * Types of this script's arguments in the order callers push them. Like
     * [returnOrder] this can interleave the stacks, and the parameter list has to
     * be written in it for a call to line up with the declaration.
     */
    var argumentOrder: List<Cs2Type> = emptyList()

    override fun toString(): String =
        "Cs2Script(name=$name, instructions=${instructions.size}, " +
            "args=$intArgsCount/$stringArgsCount/$longArgsCount, " +
            "locals=$intLocalsCount/$stringLocalsCount/$longLocalsCount, " +
            "switches=${switchTables.size})"
}

/** Values a script returns, by stack. */
data class ReturnSignature(val ints: Int, val strings: Int, val longs: Int) {
    val total: Int get() = ints + strings + longs

    companion object {
        val EMPTY = ReturnSignature(0, 0, 0)
    }
}
