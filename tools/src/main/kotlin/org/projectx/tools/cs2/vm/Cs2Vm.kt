package org.projectx.tools.cs2.vm

import world.gregs.voidps.cache.cs2.Cs2Cfg
import world.gregs.voidps.cache.cs2.Cs2Context
import world.gregs.voidps.cache.cs2.Cs2Instruction
import world.gregs.voidps.cache.cs2.Cs2Op
import world.gregs.voidps.cache.cs2.Cs2Operand
import world.gregs.voidps.cache.cs2.Cs2PushTag
import world.gregs.voidps.cache.cs2.Cs2Script
import world.gregs.voidps.cache.cs2.OpKind
import world.gregs.voidps.cache.cs2.ReturnSignature
import kotlin.math.pow
import kotlin.random.Random

/**
 * Executes CS2 bytecode.
 *
 * This mirrors the client's interpreter: three typed operand stacks, per-call
 * locals, five global int arrays, and a call stack of saved frames. Only the
 * core instruction set is implemented here; everything else goes to [Cs2Host],
 * which is what lets the interface editor answer component opcodes while the
 * machine still tracks the stacks exactly.
 */
class Cs2Vm(
    private val context: Cs2Context,
    private val host: Cs2Host,
    /** Guards against runaway scripts, as the client's own limit does. */
    private val instructionLimit: Int = 200_000,
) {
    private val intStack = ArrayList<Int>(256)
    private val stringStack = ArrayList<String>(64)
    private val longStack = ArrayList<Long>(32)

    private val globalArrays = Array(5) { IntArray(5000) }
    private val globalArrayLengths = IntArray(5)

    private class Frame(
        /** Script id, or -1 when the caller did not name one. */
        val id: Int,
        val script: Cs2Script,
        var pointer: Int,
        val ints: IntArray,
        val strings: Array<String>,
        val longs: LongArray,
    ) {
        /** `id (name)`, whichever parts are known, for diagnostics. */
        fun label(): String {
            val name = script.name
            return when {
                id >= 0 && name != null -> "$id ($name)"
                id >= 0 -> id.toString()
                name != null -> name
                else -> "?"
            }
        }
    }

    private val frames = ArrayList<Frame>(16)

    /**
     * Source for `RANDOM`/`RANDOMINC`. Seeded so a script that draws on it renders
     * the same way twice - an editor that changed under the user on every frame
     * would be unusable, and no interface depends on the values differing.
     */
    private val random = Random(SEED)

    /** Opcodes that have already reported a zero divisor during this [execute]. */
    private val zeroDivisorsReported = HashSet<String>()

    /** Instructions executed by the last [execute] call. */
    var instructionsRun: Int = 0
        private set

    /**
     * Runs [script] with the given arguments and returns whatever it leaves on
     * the stacks.
     */
    fun execute(
        script: Cs2Script,
        intArgs: List<Int> = emptyList(),
        stringArgs: List<String> = emptyList(),
        longArgs: List<Long> = emptyList(),
        /** Id of [script], used only to name it in error messages. */
        scriptId: Int = -1,
    ): Cs2Values {
        intStack.clear()
        stringStack.clear()
        longStack.clear()
        frames.clear()
        instructionsRun = 0
        zeroDivisorsReported.clear()

        frames.add(newFrame(scriptId, script, intArgs, stringArgs, longArgs))

        while (frames.isNotEmpty()) {
            if (++instructionsRun > instructionLimit) {
                host.onError(runawayMessage())
                break
            }
            val frame = frames.last()
            if (frame.pointer >= frame.script.size) break
            val instruction = frame.script[frame.pointer]
            frame.pointer++
            if (!step(frame, instruction)) {
                break
            }
        }
        return Cs2Values(intStack.toList(), stringStack.toList(), longStack.toList())
    }

    /**
     * Names the script the limit tripped in, where in it the machine was, and the
     * opcode it was on, plus the chain of callers. Without the id a runaway script
     * cannot be found again to be decompiled, which is the only way to fix one.
     */
    private fun runawayMessage(): String {
        val frame = frames.lastOrNull()
            ?: return "Script ? exceeded $instructionLimit instructions"
        val instruction = frame.script.instructions.getOrNull(frame.pointer)
        val where = "at ${frame.pointer}/${frame.script.size}"
        val what = if (instruction == null) "past end of script" else "on $instruction"
        val callers = frames.dropLast(1).joinToString(" -> ") { it.label() }
        val via = if (callers.isEmpty()) "" else ", called from $callers"
        return "Script ${frame.label()} exceeded $instructionLimit instructions $where $what$via"
    }

    private fun newFrame(
        id: Int,
        script: Cs2Script,
        intArgs: List<Int>,
        stringArgs: List<String>,
        longArgs: List<Long>,
    ) = Frame(
        id = id,
        script = script,
        pointer = 0,
        ints = IntArray(script.intLocalsCount) { intArgs.getOrElse(it) { 0 } },
        strings = Array(script.stringLocalsCount) { stringArgs.getOrElse(it) { "" } },
        longs = LongArray(script.longLocalsCount) { longArgs.getOrElse(it) { 0L } },
    )

    // ------------------------------------------------------------ stack access

    private fun popInt(): Int =
        if (intStack.isEmpty()) 0 else intStack.removeAt(intStack.size - 1)

    private fun popString(): String =
        if (stringStack.isEmpty()) "" else stringStack.removeAt(stringStack.size - 1)

    private fun popLong(): Long =
        if (longStack.isEmpty()) 0L else longStack.removeAt(longStack.size - 1)

    private fun popInts(count: Int): List<Int> {
        val out = ArrayList<Int>(count)
        repeat(count) { out.add(popInt()) }
        out.reverse()
        return out
    }

    private fun popStrings(count: Int): List<String> {
        val out = ArrayList<String>(count)
        repeat(count) { out.add(popString()) }
        out.reverse()
        return out
    }

    private fun popLongs(count: Int): List<Long> {
        val out = ArrayList<Long>(count)
        repeat(count) { out.add(popLong()) }
        out.reverse()
        return out
    }

    // ------------------------------------------------------------- execution

    /** Runs one instruction. Returns false to stop the whole script. */
    private fun step(frame: Frame, instruction: Cs2Instruction): Boolean {
        val op = instruction.op
        val operand = instruction.intOperand
        val text = instruction.strOperand
        val long = instruction.longOperand

        if (op.operand == Cs2Operand.TAGGED) {
            when (instruction.pushTag) {
                Cs2PushTag.INT -> intStack.add(operand)
                Cs2PushTag.LONG -> longStack.add(long)
                Cs2PushTag.STRING -> stringStack.add(text ?: "")
            }
            return true
        }
        when (op.opName) {
            "PUSH_CONSTANT_INT" -> intStack.add(operand)
            "PUSH_CONSTANT_STRING" -> stringStack.add(text ?: "")
            "PUSH_LONG_CONSTANT" -> longStack.add(long)

            "PUSH_INT_LOCAL" -> intStack.add(frame.ints.getOrElse(operand) { 0 })
            "PUSH_STRING_LOCAL" -> stringStack.add(frame.strings.getOrElse(operand) { "" })
            "PUSH_LONG_LOCAL" -> longStack.add(frame.longs.getOrElse(operand) { 0L })

            "POP_INT_LOCAL" -> frame.ints.setIfPresent(operand, popInt())
            "POP_STRING_LOCAL" -> if (operand in frame.strings.indices) frame.strings[operand] = popString()
            "POP_LONG_LOCAL" -> if (operand in frame.longs.indices) frame.longs[operand] = popLong()

            "POP_INT_DISCARD" -> popInt()
            "POP_STRING_DISCARD" -> popString()
            "POP_LONG_DISCARD" -> popLong()

            "PUSH_VAR", "GET_VARP_OLD" -> intStack.add(host.varp(operand))
            "POP_VAR" -> host.setVarp(operand, popInt())
            "PUSH_VARBIT", "GET_VARPBIT_OLD" -> intStack.add(host.varbit(operand))
            "POP_VARBIT" -> host.setVarbit(operand, popInt())
            "GET_VARN_OLD", "GET_VARNBIT_OLD" -> intStack.add(0)
            "LOAD_VARC" -> intStack.add(host.varc(operand))
            "STORE_VARC" -> host.setVarc(operand, popInt())
            "LOAD_VARC_STRING" -> stringStack.add(host.varcString(operand))
            "STORE_VARC_STRING" -> host.setVarcString(operand, popString())

            "JOIN_STRING" -> stringStack.add(popStrings(operand).joinToString(""))

            "DEFINE_ARRAY" -> defineArray(operand)
            "PUSH_ARRAY_INT" -> intStack.add(readArray(operand, popInt()))
            "POP_ARRAY_INT" -> {
                val value = popInt()
                writeArray(operand, popInt(), value)
            }

            "BRANCH" -> frame.pointer += operand
            "SWITCH" -> switch(frame, operand)
            "RETURN" -> return returnFromFrame()
            "GOSUB_WITH_PARAMS" -> return call(operand)

            else -> when {
                Cs2Cfg.isConditionalBranch(op) -> branch(frame, op, operand)
                op.kind == OpKind.HOOK -> setHook(op)
                !primitive(op) -> callHost(op, operand)
            }
        }
        return true
    }

    /**
     * Arithmetic, bitwise and string primitives; false when [op] is not one.
     *
     * These belong to the language, not to a host: they read and write only the
     * operand stacks, and every script relies on them. Routing them through
     * [Cs2Host] like the rest of the opcode space meant an unimplemented host
     * silently evaluated `i + 1` to `0`, which turns every counting loop into an
     * infinite one - the machine has to own them.
     *
     * Transcribed from the client's `MathStringOps` and `CS2SharedOps`, with one
     * deliberate difference: where the client throws and abandons the script - a
     * zero divisor, a substring outside its string - this reports the problem and
     * yields a harmless value instead. The reason is that such an argument almost
     * never comes from the script; it comes from an opcode this host has not
     * implemented returning zero, and abandoning the script would throw away the
     * rest of a widget over a fault of our own making. Degrading matches what
     * [Cs2Host] already does for every opcode it does not know.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun primitive(op: Cs2Op): Boolean {
        when (op.opName) {
            // Two ints in, one out. The first operand pushed is the left-hand side.
            "ADD" -> binaryInt { left, right -> left + right }
            "SUB" -> binaryInt { left, right -> left - right }
            "MULTIPLY" -> binaryInt { left, right -> left * right }
            "DIVIDE" -> divideBy("DIVIDE") { left, right -> left / right }
            "MODULO" -> divideBy("MODULO") { left, right -> left % right }
            "AND" -> binaryInt { left, right -> left and right }
            "OR" -> binaryInt { left, right -> left or right }
            "MIN" -> binaryInt { left, right -> minOf(left, right) }
            "MAX" -> binaryInt { left, right -> maxOf(left, right) }
            "SETBIT" -> binaryInt { value, bit -> value or (1 shl bit) }
            "CLEARBIT" -> binaryInt { value, bit -> value and (1 shl bit).inv() }
            "TESTBIT" -> binaryInt { value, bit -> if ((value and (1 shl bit)) != 0) 1 else 0 }
            "POW" -> binaryInt { base, exponent ->
                if (base == 0) 0 else base.toDouble().pow(exponent.toDouble()).toInt()
            }
            "INVPOW" -> binaryInt { base, exponent ->
                when {
                    base == 0 -> 0
                    exponent == 0 -> Int.MAX_VALUE
                    else -> base.toDouble().pow(1.0 / exponent).toInt()
                }
            }
            // Widened to long before dividing, as the client does, so a large
            // numerator does not wrap on the way through.
            "ADDPERCENT" -> binaryInt { value, percent ->
                (value + value.toLong() * percent.toLong() / 100L).toInt()
            }
            "NOT" -> intStack.add(popInt().inv())

            // `scale(numerator, denominator, value)` is `value * numerator / denominator`.
            "SCALE" -> {
                val (numerator, denominator, value) = popInts(3)
                intStack.add(
                    if (denominator == 0) {
                        reportZeroDivisor("SCALE")
                        0
                    } else {
                        (value.toLong() * numerator.toLong() / denominator.toLong()).toInt()
                    }
                )
            }

            "INTERPOLATE" -> {
                val values = popInts(5)
                val (fromY, toY, fromX, toX) = values
                val at = values[4]
                if (toX == fromX) {
                    reportZeroDivisor("INTERPOLATE")
                    intStack.add(fromY)
                } else {
                    intStack.add(fromY + (at - fromX) * (toY - fromY) / (toX - fromX))
                }
            }

            "RANDOM" -> intStack.add(popInt().let { if (it <= 0) 0 else random.nextInt(it) })
            "RANDOMINC" -> intStack.add(popInt().let { if (it < 0) 0 else random.nextInt(it + 1) })

            "CHAR_ISPRINTABLE" -> intStack.add(boolean(!Character.isISOControl(popInt().toChar())))
            "CHAR_ISALPHANUMERIC" -> intStack.add(boolean(popInt().toChar().isLetterOrDigit()))
            "CHAR_ISALPHA" -> intStack.add(boolean(popInt().toChar().isLetter()))
            "CHAR_ISNUMERIC" -> intStack.add(boolean(popInt().toChar().isDigit()))
            "CHAR_TOLOWERCASE" -> intStack.add(popInt().toChar().lowercaseChar().code)
            "CHAR_TOUPPERCASE" -> intStack.add(popInt().toChar().uppercaseChar().code)

            "STRING_LENGTH" -> intStack.add(popString().length)
            "TOSTRING" -> stringStack.add(popInt().toString())
            "LOWERCASE" -> stringStack.add(popString().lowercase())
            "APPEND" -> stringStack.add(popStrings(2).joinToString(""))
            "APPEND_NUM" -> {
                val text = popString()
                stringStack.add(text + popInt())
            }
            "APPEND_SIGNNUM" -> {
                val text = popString()
                stringStack.add(text + signed(popInt()))
            }
            "APPEND_CHAR" -> {
                val text = popString()
                val char = popInt()
                // -1 is the client's "no character", which it treats as fatal.
                stringStack.add(if (char == -1) text else text + char.toChar())
            }
            "SUBSTRING" -> {
                val text = popString()
                val (start, end) = popInts(2)
                val from = start.coerceIn(0, text.length)
                val to = end.coerceIn(from, text.length)
                if (from != start || to != end) {
                    host.onError("SUBSTRING $start..$end clamped to $from..$to of \"$text\"")
                }
                stringStack.add(text.substring(from, to))
            }
            "REMOVETAGS" -> stringStack.add(removeTags(popString()))
            "ESCAPE" -> stringStack.add(popString().replace("<", "<lt>").replace(">", "<gt>"))
            "GET_COL_TAG" -> stringStack.add("<col=" + Integer.toHexString(popInt()) + ">")
            // The client compares locale-aware, treating embedded digit runs as
            // numbers; a plain lexicographic compare is close enough for a tool and
            // agrees on the sign for the ASCII text interfaces actually hold.
            "COMPARE" -> {
                val (left, right) = popStrings(2)
                intStack.add(left.compareTo(right))
            }
            "TEXT_SWITCH" -> {
                val (whenTrue, whenFalse) = popStrings(2)
                stringStack.add(if (popInt() == 1) whenTrue else whenFalse)
            }
            "STRING_INDEXOF_CHAR" -> {
                val text = popString()
                val (char, from) = popInts(2)
                intStack.add(text.indexOf(char.toChar(), from))
            }
            "STRING_INDEXOF_STRING" -> {
                val (text, search) = popStrings(2)
                intStack.add(text.indexOf(search, popInt()))
            }

            else -> return false
        }
        return true
    }

    private fun boolean(value: Boolean) = if (value) 1 else 0

    private inline fun binaryInt(combine: (Int, Int) -> Int) {
        val right = popInt()
        val left = popInt()
        intStack.add(combine(left, right))
    }

    /** `DIVIDE` and `MODULO`, yielding 0 rather than throwing on a zero divisor. */
    private inline fun divideBy(name: String, combine: (Int, Int) -> Int) {
        val right = popInt()
        val left = popInt()
        if (right == 0) {
            reportZeroDivisor(name)
            intStack.add(0)
        } else {
            intStack.add(combine(left, right))
        }
    }

    /**
     * Notes a zero divisor once per script run. A divide inside a loop would
     * otherwise fill the log with the same line hundreds of times and bury whatever
     * else the script reported.
     */
    private fun reportZeroDivisor(name: String) {
        if (zeroDivisorsReported.add(name)) {
            host.onError("$name divided by zero; the divisor is 0 because some opcode this host does not implement returned it")
        }
    }

    /** Everything outside a `<...>` tag, as the client's `removetags` does. */
    private fun removeTags(text: String): String {
        val out = StringBuilder(text.length)
        var inTag = false
        for (char in text) {
            when {
                char == '<' -> inTag = true
                char == '>' -> inTag = false
                !inTag -> out.append(char)
            }
        }
        return out.toString()
    }

    /**
     * `APPEND_SIGNNUM`'s explicit sign: the client spells a positive number with a
     * leading `+` and leaves a negative one to its own minus sign.
     */
    private fun signed(value: Int): String = if (value >= 0) "+$value" else value.toString()

    private fun IntArray.setIfPresent(index: Int, value: Int) {
        if (index in indices) this[index] = value
    }

    private fun defineArray(operand: Int) {
        val array = ((operand shr 16) and 0x7).coerceAtMost(globalArrays.lastIndex)
        val size = popInt().coerceIn(0, 5000)
        globalArrayLengths[array] = size
        val fill = if ((operand and 0xFFFF) == 's'.code) 0 else -1
        globalArrays[array].fill(fill, 0, size)
    }

    private fun readArray(array: Int, index: Int): Int {
        val slot = array.coerceIn(0, globalArrays.lastIndex)
        return if (index in 0 until globalArrayLengths[slot]) globalArrays[slot][index] else 0
    }

    private fun writeArray(array: Int, index: Int, value: Int) {
        val slot = array.coerceIn(0, globalArrays.lastIndex)
        if (index in 0 until globalArrayLengths[slot]) globalArrays[slot][index] = value
    }

    private fun branch(frame: Frame, op: Cs2Op, operand: Int) {
        val taken = when (op.opName) {
            "BRANCH_IF_TRUE" -> popInt() == 1
            "BRANCH_IF_FALSE" -> popInt() == 0
            "BRANCH_EQUALS" -> compareInts() == 0
            "BRANCH_NOT" -> compareInts() != 0
            "BRANCH_LESS_THAN" -> compareInts() < 0
            "BRANCH_GREATER_THAN" -> compareInts() > 0
            "BRANCH_LESS_THAN_OR_EQUALS" -> compareInts() <= 0
            "BRANCH_GREATER_THAN_OR_EQUALS" -> compareInts() >= 0
            "LONG_BRANCH_EQUALS" -> compareLongs() == 0
            "LONG_BRANCH_NOT" -> compareLongs() != 0
            "LONG_BRANCH_LESS_THAN" -> compareLongs() < 0
            "LONG_BRANCH_GREATER_THAN" -> compareLongs() > 0
            "LONG_BRANCH_LESS_THAN_OR_EQUALS" -> compareLongs() <= 0
            "LONG_BRANCH_GREATER_THAN_OR_EQUALS" -> compareLongs() >= 0
            else -> false
        }
        if (taken) frame.pointer += operand
    }

    private fun compareInts(): Int {
        val right = popInt()
        val left = popInt()
        return left.compareTo(right)
    }

    private fun compareLongs(): Int {
        val right = popLong()
        val left = popLong()
        return left.compareTo(right)
    }

    private fun switch(frame: Frame, table: Int) {
        val key = popInt()
        val cases = frame.script.switchTables.getOrNull(table) ?: return
        cases.firstOrNull { it.key == key }?.let { frame.pointer += it.offset }
    }

    private fun call(scriptId: Int): Boolean {
        val callee = context.script(scriptId)
        if (callee == null) {
            host.onError("Called missing script $scriptId")
            return false
        }
        if (frames.size >= 50) {
            host.onError("Call stack overflow at script $scriptId")
            return false
        }
        val longArgs = popLongs(callee.longArgsCount)
        val stringArgs = popStrings(callee.stringArgsCount)
        val intArgs = popInts(callee.intArgsCount)
        frames.add(newFrame(scriptId, callee, intArgs, stringArgs, longArgs))
        return true
    }

    private fun returnFromFrame(): Boolean {
        frames.removeAt(frames.size - 1)
        return frames.isNotEmpty()
    }

    /**
     * Hook setters pop a format string describing the callback's arguments,
     * optionally a trigger list, then the arguments and the script id.
     */
    private fun setHook(op: Cs2Op) {
        val component = if (op.popInt - op.hookTrailingPops == 1) popInt() else -1
        val format = popString()

        var spec = format
        var triggers = emptyList<Int>()
        if (op.hasTriggerArray && spec.endsWith("Y")) {
            spec = spec.dropLast(1)
            triggers = popInts(popInt().coerceAtLeast(0))
        }

        val ints = ArrayList<Int>()
        val strings = ArrayList<String>()
        val longs = ArrayList<Long>()
        for (char in spec.reversed()) {
            when (char) {
                's' -> strings.add(0, popString())
                '§' -> longs.add(0, popLong())
                else -> ints.add(0, popInt())
            }
        }
        val scriptId = popInt()
        repeat(op.hookTrailingPops) { popInt() }
        val hook = if (scriptId == -1) null else Cs2Hook(scriptId, Cs2Values(ints, strings, longs), triggers)
        host.setHook(op, component, hook)
        repeat(op.pushLong) { longStack.add(0L) }
    }

    private fun callHost(op: Cs2Op, operand: Int) {
        val longArgs = popLongs(op.popLong)
        val stringArgs = popStrings(op.popStr)
        val intArgs = popInts(op.popInt)
        val results = host.invoke(op, operand, Cs2Values(intArgs, stringArgs, longArgs))

        // The host may not know the opcode; pad so the stacks stay consistent
        // with the signature the rest of the script was compiled against.
        val expected = expectedResults(op, intArgs)
        repeat(expected.ints) { intStack.add(results.ints.getOrElse(it) { 0 }) }
        repeat(expected.strings) { stringStack.add(results.strings.getOrElse(it) { "" }) }
        repeat(expected.longs) { longStack.add(results.longs.getOrElse(it) { 0L }) }
    }

    /** What an opcode pushes, resolving the int-or-string param opcodes. */
    private fun expectedResults(op: Cs2Op, intArgs: List<Int>): ReturnSignature {
        if (op.kind != OpKind.PARAM) {
            return ReturnSignature(op.pushInt, op.pushStr, op.pushLong)
        }
        val yieldsString = if (op.opName == "ENUM") {
            intArgs.getOrNull(1) == 's'.code
        } else {
            intArgs.lastOrNull()?.let { context.paramIsString(it) } ?: false
        }
        return if (yieldsString) ReturnSignature(0, 1, 0) else ReturnSignature(1, 0, 0)
    }

    private companion object {
        /** Fixed seed for [random]; the value itself carries no meaning. */
        const val SEED = 0x5CB1EL
    }
}
