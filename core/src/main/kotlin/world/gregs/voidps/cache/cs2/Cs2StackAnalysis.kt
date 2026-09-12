package world.gregs.voidps.cache.cs2

import world.gregs.voidps.cache.cs2.ir.Cs2Type

/** Values an instruction takes off and puts back on the three operand stacks. */
data class StackEffect(
    val popInt: Int = 0,
    val popStr: Int = 0,
    val popLong: Int = 0,
    val pushInt: Int = 0,
    val pushStr: Int = 0,
    val pushLong: Int = 0,
) {
    val counts: List<Int> get() = listOf(popInt, popStr, popLong, pushInt, pushStr, pushLong)
}

/** Everything the effective-signature calculation needs but cannot see locally. */
interface Cs2Context {
    /** Header + return signature of a called script, or null when it is missing. */
    fun script(id: Int): Cs2Script?

    /** True when a `*_PARAM` opcode with this param id yields a string. */
    fun paramIsString(paramId: Int): Boolean

    /** Which stack a variable access lands on, for the domain-tagged var opcodes. */
    fun variableStack(operand: Int): Cs2VarBase = Cs2VarBase.INT

    /** The gameval table each integer operand indexes, where the whole corpus determines one. */
    fun operandTypes(): Cs2TypeFlow = Cs2TypeFlow.NONE
}

/**
 * The stack effect of one instruction, given what is already on the stacks.
 *
 * Most opcodes have a fixed signature. The exceptions all depend on operands or
 * on constants sitting on the stack:
 *  - `JOIN_STRING` joins `operand` strings.
 *  - `GOSUB_WITH_PARAMS` takes the callee's arguments and leaves its returns.
 *  - hook setters pop a type-spec string, then one value per character it lists,
 *    plus a trigger list when the spec ends in `Y`.
 *  - `*_PARAM` opcodes push an int or a string depending on the param's type.
 *  - a typed variable access lands on the stack its own type names.
 */
fun effectiveSignature(
    instruction: Cs2Instruction,
    context: Cs2Context,
    stacks: SymbolicStacks,
): StackEffect {
    val op = instruction.op
    // A tagged push carries its stack in the operand itself, whatever the kind.
    if (op.operand == Cs2Operand.TAGGED) {
        return when (instruction.pushTag) {
            Cs2PushTag.INT -> StackEffect(pushInt = 1)
            Cs2PushTag.STRING -> StackEffect(pushStr = 1)
            Cs2PushTag.LONG -> StackEffect(pushLong = 1)
            else -> StackEffect()
        }
    }
    return when (op.kind) {
        OpKind.NORMAL -> StackEffect(
            op.popInt, op.popStr, op.popLong, op.pushInt, op.pushStr, op.pushLong,
        )

        OpKind.JOIN_STRING -> StackEffect(popStr = instruction.intOperand, pushStr = 1)

        OpKind.GOSUB -> {
            val callee = context.script(instruction.intOperand)
                ?: error("GOSUB to missing script ${instruction.intOperand}")
            val returns = callee.returnSignature ?: ReturnSignature.EMPTY
            StackEffect(
                popInt = callee.intArgsCount,
                popStr = callee.stringArgsCount,
                popLong = callee.longArgsCount,
                pushInt = returns.ints,
                pushStr = returns.strings,
                pushLong = returns.longs,
            )
        }

        OpKind.PARAM -> {
            val yieldsString = if (op.opName == "ENUM") enumYieldsString(stacks) else {
                val paramId = stacks.topInt()
                    ?: error("${op.opName} needs a constant param id to resolve its result type")
                context.paramIsString(paramId)
            }
            if (yieldsString) {
                StackEffect(op.popInt, op.popStr, op.popLong, pushStr = 1)
            } else {
                StackEffect(op.popInt, op.popStr, op.popLong, pushInt = 1)
            }
        }

        OpKind.HOOK -> hookSignature(op, stacks)

        OpKind.TYPED_PUSH -> when (context.variableStack(instruction.intOperand)) {
            Cs2VarBase.STRING -> StackEffect(popInt = op.popInt, pushStr = 1)
            Cs2VarBase.LONG -> StackEffect(popInt = op.popInt, pushLong = 1)
            else -> StackEffect(popInt = op.popInt, pushInt = 1)
        }

        // The value being stored is whatever was pushed last, whichever stack it
        // went on, so the store needs no table to know where to take it from.
        OpKind.TYPED_POP -> {
            val typed = when (stacks.order().lastOrNull()) {
                Cs2Type.STRING -> StackEffect(popStr = 1)
                Cs2Type.LONG -> StackEffect(popLong = 1)
                else -> StackEffect(popInt = 1)
            }
            StackEffect(
                op.popInt + typed.popInt, typed.popStr, typed.popLong,
                op.pushInt, op.pushStr, op.pushLong,
            )
        }

        OpKind.TAG_SELECTED_POP -> {
            val typed = tagSelectedStack(
                stacks.topInt() ?: error("${'$'}{op.opName} needs a constant type tag"),
            )
            StackEffect(
                op.popInt + if (typed == Cs2Type.INT) 1 else 0,
                if (typed == Cs2Type.STRING) 1 else 0,
                if (typed == Cs2Type.LONG) 1 else 0,
                op.pushInt, op.pushStr, op.pushLong,
            )
        }

        // No path through the handler completes, so nothing after it runs and
        // nothing it might have popped is observable.
        OpKind.ERROR_STUB -> StackEffect()

        OpKind.VARARG -> when (op.opName) {
            DB_GETFIELD -> dbFieldSignature(instruction, stacks)
            else -> error("${op.opName} is variadic in a way the binary does not spell out")
        }
    }
}

const val DB_GETFIELD = "DB_GETFIELD"

/** `db_getfield` takes a row, a packed column and a tuple index. */
const val DB_FIELD_ARGS = 3

/**
 * `db_getfield` leaves the column's whole tuple, so the packed column has to be
 * a constant for the schema to say what that is.
 */
private fun dbFieldSignature(instruction: Cs2Instruction, stacks: SymbolicStacks): StackEffect {
    val column = stacks.intBelowTop(1)
        ?: error("${instruction.op.opName} needs a constant column")
    val results = Cs2DbFields.signature(column)
    return StackEffect(
        popInt = DB_FIELD_ARGS,
        pushInt = results.ints,
        pushStr = results.strings,
        pushLong = results.longs,
    )
}

/**
 * Which stack one character of a hook's type spec names.
 *
 * The parser compares the raw byte against `s` and `l` and treats everything
 * else as an int, so this is not the script-var-type table's legacy character -
 * that table spells `l` as an int.
 */
fun hookArgStack(char: Char): Cs2Type = when (char) {
    's' -> Cs2Type.STRING
    'l' -> Cs2Type.LONG
    else -> Cs2Type.INT
}

/**
 * Which stack a tag-selected pop draws its one typed value from.
 *
 * The tag sits on top of the int stack and the handler maps it through the
 * client's base script-var-type table: `0` int, `1` long, `2` and `3` string.
 */
fun tagSelectedStack(tag: Int): Cs2Type = when (tag) {
    1 -> Cs2Type.LONG
    2, 3 -> Cs2Type.STRING
    else -> Cs2Type.INT
}

/**
 * `ENUM` pops `keyType, valueType, enumId, key`. It is self-describing: the
 * value type is pushed as a constant, so no param table is needed to know which
 * stack the result lands on.
 */
private fun enumYieldsString(stacks: SymbolicStacks): Boolean {
    val valueType = stacks.intBelowTop(2)
        ?: error("ENUM needs a constant value type to resolve its result type")
    return Cs2VarTypes.baseOfId(valueType) == Cs2VarBase.STRING
}

/**
 * What a hook setter takes off the stacks.
 *
 * The callback is described by a type-spec string: one character per bound
 * argument, each naming the stack of one value. Its alphabet is the hook
 * parser's own three letters, not the script-var-type table's legacy characters,
 * which spell `l` as an int. A trailing `Y` means a trigger list follows,
 * written as its own length and then that many ints. Everything else the caller contributes is the fixed prefix the opcode
 * carries in [Cs2Op.popInt]: one int for the `if_` forms, whose component is on
 * the stack, and none for the `cc_` forms, which take it from the active
 * component instead. Most hooks leave nothing behind; the ones that do carry
 * their push counts on the entry.
 */
private fun hookSignature(op: Cs2Op, stacks: SymbolicStacks): StackEffect {
    val format = stacks.topString()
        ?: error("${op.opName} needs a constant format string")

    var popInt = op.popInt + 1
    var popStr = 1
    var popLong = 0

    var spec = format
    if (spec.endsWith("Y")) {
        spec = spec.dropLast(1)
        val count = stacks.intBelowTop(op.popInt)
            ?: error("${op.opName} needs a constant trigger-list size")
        popInt += 1 + count
    }
    for (char in spec) {
        when (hookArgStack(char)) {
            Cs2Type.STRING -> popStr++
            Cs2Type.LONG -> popLong++
            else -> popInt++
        }
    }
    return StackEffect(popInt, popStr, popLong, op.pushInt, op.pushStr, op.pushLong)
}

/**
 * Operand stacks during simulation, tracking constant values where they are
 * known. Hook setters and `*_PARAM` opcodes need to read constants that were
 * pushed a few instructions earlier, so depth alone is not enough.
 */
class SymbolicStacks {
    private val ints = ArrayList<Int?>()
    private val strings = ArrayList<String?>()
    private var longDepth = 0

    /**
     * Types of the live values in the order they were pushed. The three stacks
     * are independent, so this is the only record of how they interleave - which
     * is exactly what a script's return order is.
     */
    private val live = ArrayList<Cs2Type>()

    /** The live values' types, oldest first. */
    fun order(): List<Cs2Type> = live.toList()

    val intDepth: Int get() = ints.size
    val stringDepth: Int get() = strings.size
    val longs: Int get() = longDepth

    val isEmpty: Boolean get() = ints.isEmpty() && strings.isEmpty() && longDepth == 0

    fun topInt(): Int? = ints.lastOrNull()

    /** The int `depth` slots below the top, or null when unknown. */
    fun intBelowTop(depth: Int): Int? = ints.getOrNull(ints.size - 1 - depth)

    fun topString(): String? = strings.lastOrNull()

    fun apply(
        effect: StackEffect,
        pushedIntConstant: Int? = null,
        pushedString: String? = null,
        pushOrder: List<Cs2Type>? = null,
    ) {
        repeat(effect.popInt) {
            require(ints.isNotEmpty()) { "int stack underflow" }
            ints.removeAt(ints.size - 1)
            live.removeLastOfType(Cs2Type.INT)
        }
        repeat(effect.popStr) {
            require(strings.isNotEmpty()) { "string stack underflow" }
            strings.removeAt(strings.size - 1)
            live.removeLastOfType(Cs2Type.STRING)
        }
        repeat(effect.popLong) {
            longDepth--
            live.removeLastOfType(Cs2Type.LONG)
        }
        require(longDepth >= 0) { "long stack underflow" }

        val pushes = pushOrder ?: buildList {
            repeat(effect.pushInt) { add(Cs2Type.INT) }
            repeat(effect.pushStr) { add(Cs2Type.STRING) }
            repeat(effect.pushLong) { add(Cs2Type.LONG) }
        }
        for (type in pushes) {
            when (type) {
                Cs2Type.STRING -> strings.add(pushedString)
                Cs2Type.LONG -> longDepth++
                else -> ints.add(pushedIntConstant)
            }
            live.add(type)
        }
    }

    private fun MutableList<Cs2Type>.removeLastOfType(type: Cs2Type) {
        val at = indexOfLast { it == type }
        if (at >= 0) removeAt(at)
    }

    fun clear() {
        ints.clear()
        strings.clear()
        longDepth = 0
        live.clear()
    }

    fun copy(): SymbolicStacks {
        val copy = SymbolicStacks()
        copy.ints.addAll(ints)
        copy.strings.addAll(strings)
        copy.longDepth = longDepth
        copy.live.addAll(live)
        return copy
    }

    fun sameDepthAs(other: SymbolicStacks): Boolean =
        intDepth == other.intDepth && stringDepth == other.stringDepth && longs == other.longs

    /**
     * Merges another state in at a control-flow join. Depths must already agree;
     * constants that differ become unknown.
     */
    fun mergeConstantsFrom(other: SymbolicStacks) {
        for (i in ints.indices) if (ints[i] != other.ints[i]) ints[i] = null
        for (i in strings.indices) if (strings[i] != other.strings[i]) strings[i] = null
    }

    override fun toString() = "int=$intDepth str=$stringDepth long=$longDepth"
}
