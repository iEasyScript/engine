package world.gregs.voidps.cache.cs2

/**
 * The little the stack solver is allowed to start from.
 *
 * Stack arithmetic alone is satisfied by every opcode doing nothing, so the
 * corpus has to be anchored somewhere. The anchor is the tagged push: its
 * operand *is* an int, a long or a string, which the byte-exact codec proves, so
 * it puts exactly one value on the stack that tag names. That comes from the
 * file format rather than from a name, and it is enough - RS3 spells every
 * constant that way, so the anchor is in almost every script.
 *
 * The two opcodes here have no constant effect to solve for. A call takes its
 * callee's arguments and leaves its returns, and a join takes as many strings as
 * its operand says; both are modelled, not assumed. `RETURN` ends a path before
 * anything is applied to it, so it needs a name and no effect.
 *
 * Everything else - the arithmetic, the stores, the comparisons - is solved,
 * including the opcodes the rest of the toolchain implements by name. That is
 * deliberate: a name worked out from an opcode's role is a hypothesis, and
 * putting it into the solve as fact would make the solve agree with it for free.
 */
object Cs2CoreOps {

    /** Spellings of the same core opcode that have been used in different tables. */
    private val ALIASES = mapOf("PUSH_CONSTANT_LONG" to "PUSH_LONG_CONSTANT")

    fun normalise(name: String): String = ALIASES[name] ?: name

    val effects: Map<String, StackEffect> = mapOf("RETURN" to StackEffect())

    /** Opcodes whose effect comes from their operand or their callee rather than a constant. */
    val kinds: Map<String, OpKind> = mapOf(
        "JOIN_STRING" to OpKind.JOIN_STRING,
        "GOSUB_WITH_PARAMS" to OpKind.GOSUB,
    )

    fun effectOf(naming: Cs2Naming): StackEffect? = naming.structural?.let { effects[it] }

    fun kindOf(naming: Cs2Naming): OpKind? = naming.structural?.let { kinds[it] }
}
