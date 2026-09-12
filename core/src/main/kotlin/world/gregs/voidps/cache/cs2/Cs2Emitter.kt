package world.gregs.voidps.cache.cs2

import world.gregs.voidps.cache.cs2.ir.ArrayAssign
import world.gregs.voidps.cache.cs2.ir.ArrayDefine
import world.gregs.voidps.cache.cs2.ir.ArrayRef
import world.gregs.voidps.cache.cs2.ir.Binary
import world.gregs.voidps.cache.cs2.ir.Break
import world.gregs.voidps.cache.cs2.ir.Callback
import world.gregs.voidps.cache.cs2.ir.AndAll
import world.gregs.voidps.cache.cs2.ir.Compare
import world.gregs.voidps.cache.cs2.ir.Condition
import world.gregs.voidps.cache.cs2.ir.OrAny
import world.gregs.voidps.cache.cs2.ir.Continue
import world.gregs.voidps.cache.cs2.ir.Cs2Function
import world.gregs.voidps.cache.cs2.ir.Cs2Type
import world.gregs.voidps.cache.cs2.ir.Expr
import world.gregs.voidps.cache.cs2.ir.ExprStmt
import world.gregs.voidps.cache.cs2.ir.Goto
import world.gregs.voidps.cache.cs2.ir.If
import world.gregs.voidps.cache.cs2.ir.IntConst
import world.gregs.voidps.cache.cs2.ir.Join
import world.gregs.voidps.cache.cs2.ir.Label
import world.gregs.voidps.cache.cs2.ir.LocalAssign
import world.gregs.voidps.cache.cs2.ir.LocalRef
import world.gregs.voidps.cache.cs2.ir.LongConst
import world.gregs.voidps.cache.cs2.ir.MultiAssign
import world.gregs.voidps.cache.cs2.ir.OpCall
import world.gregs.voidps.cache.cs2.ir.ParallelAssign
import world.gregs.voidps.cache.cs2.ir.Return
import world.gregs.voidps.cache.cs2.ir.ScriptCall
import world.gregs.voidps.cache.cs2.ir.Stmt
import world.gregs.voidps.cache.cs2.ir.StrConst
import world.gregs.voidps.cache.cs2.ir.Switch
import world.gregs.voidps.cache.cs2.ir.TempRef
import world.gregs.voidps.cache.cs2.ir.TypedConst
import world.gregs.voidps.cache.cs2.ir.VarAssign
import world.gregs.voidps.cache.cs2.ir.VarRef
import world.gregs.voidps.cache.cs2.ir.VarSpace
import world.gregs.voidps.cache.cs2.ir.While

/** What becomes of a component reading that rests on the value's shape alone. */
enum class Cs2ShapeReading {
    /**
     * Kept, with a marker saying so, so the reader gets the name and can see it is a guess.
     * The marker is a comment, so the source still compiles back to the same bytes.
     */
    MARKED,

    /** Dropped, leaving the integer, so nothing unverified reaches the output at all. */
    NUMERIC,
}

/** How many component renderings each kind of evidence accounts for. */
class Cs2ComponentTally {
    var unambiguous = 0
    var determined = 0
    var shaped = 0

    fun add(other: Cs2ComponentTally) {
        unambiguous += other.unambiguous
        determined += other.determined
        shaped += other.shaped
    }

    fun clear() {
        unambiguous = 0
        determined = 0
        shaped = 0
    }
}

/**
 * Renders a decompiled script as TypeScript.
 *
 * Locals are declared up front in slot order, so they can be renamed freely
 * without changing which slot they compile back to: the compiler allocates
 * slots by declaration order within each type.
 */
class Cs2Emitter(
    private val function: Cs2Function,
    private val shapeReading: Cs2ShapeReading = Cs2ShapeReading.NUMERIC,
) {

    /** What the last [emit] spelled as a component, split by what vouched for it. */
    val components = Cs2ComponentTally()

    private val out = StringBuilder()
    private var indent = 0

    fun emit(): String {
        out.setLength(0)
        components.clear()
        emitHeader()
        emitSignature()
        indent = 1
        emitLocalDeclarations()
        emitAll(function.body)
        indent = 0
        line("}")
        return out.toString()
    }

    private fun emitHeader() {
        // A triple-slash reference makes a single file resolve the opcode names
        // on its own; cross-script calls still need the project's tsconfig.
        line("/// <reference path=\"./cs2.d.ts\" />")
        val recovered = Cs2ScriptNames.name(function.scriptId)
        line("// clientscript ${function.scriptId}" + if (recovered == null) "" else " $recovered")
        if (function.name != null) line("// name: ${function.name}")
        line("")
    }

    private fun emitSignature() {
        // Parameters follow the order callers push them, which can interleave the
        // stacks. Slots stay correct because they are assigned per type in
        // declaration order, so only the reading order changes.
        val total = function.intArgs + function.stringArgs + function.longArgs
        val order = function.argumentOrder.takeIf { it.size == total }
            ?: List(function.intArgs) { Cs2Type.INT } +
            List(function.stringArgs) { Cs2Type.STRING } +
            List(function.longArgs) { Cs2Type.LONG }

        val next = HashMap<Cs2Type, Int>()
        val params = order.map { type ->
            val slot = next.getOrDefault(type, 0)
            next[type] = slot + 1
            "${localName(slot, type)}: ${tsType(type)}"
        }

        val returns = when (function.returns.size) {
            0 -> "void"
            1 -> tsType(function.returns.first())
            else -> "[" + function.returns.joinToString(", ") { tsType(it) } + "]"
        }
        // Not exported: clientscripts share one flat namespace, and keeping the
        // files as scripts rather than modules is what lets one call another
        // without an import.
        line("function ${Cs2Symbols.scriptName(function.scriptId)}(${params.joinToString(", ")}): $returns {")
    }

    /**
     * Declares every local that is not a parameter, in slot order. Keeping the
     * order explicit is what makes renaming safe.
     */
    private fun emitLocalDeclarations() {
        var declared = false
        for (slot in function.intArgs until function.intLocals) {
            line("let ${localName(slot, Cs2Type.INT)}: number = 0;")
            declared = true
        }
        for (slot in function.stringArgs until function.stringLocals) {
            line("let ${localName(slot, Cs2Type.STRING)}: string = \"\";")
            declared = true
        }
        for (slot in function.longArgs until function.longLocals) {
            line("let ${localName(slot, Cs2Type.LONG)}: bigint = 0n;")
            declared = true
        }
        if (declared) line("")
    }

    private fun tsType(type: Cs2Type) = when (type) {
        Cs2Type.INT -> "number"
        Cs2Type.STRING -> "string"
        Cs2Type.LONG -> "bigint"
    }

    private fun localName(slot: Int, type: Cs2Type): String {
        val prefix = when (type) {
            Cs2Type.INT -> "int"
            Cs2Type.STRING -> "str"
            Cs2Type.LONG -> "long"
        }
        val args = when (type) {
            Cs2Type.INT -> function.intArgs
            Cs2Type.STRING -> function.stringArgs
            Cs2Type.LONG -> function.longArgs
        }
        return if (slot < args) "${prefix}Arg$slot" else "$prefix$slot"
    }

    // ----------------------------------------------------------- statements

    private fun emitAll(statements: List<Stmt>) = statements.forEach(::emit)

    private fun emit(statement: Stmt) {
        when (statement) {
            // A bare result that is thrown away reads better as an explicit
            // discard than as a lone identifier.
            is ExprStmt -> if (statement.call is TempRef) {
                line("discard(${expr(statement.call)});")
            } else {
                line("${expr(statement.call)};")
            }

            is LocalAssign ->
                line("${localName(statement.slot, statement.type)} = ${expr(statement.value)};")

            is ParallelAssign -> {
                val targets = statement.targets.joinToString(", ") { expr(it) }
                val values = statement.values.joinToString(", ") { expr(it) }
                line("[$targets] = [$values];")
            }

            is VarAssign -> line("${expr(statement.target)} = ${expr(statement.value)};")

            is ArrayAssign ->
                line("array${statement.array}[${expr(statement.index)}] = ${expr(statement.value)};")

            is ArrayDefine ->
                line("defineArray(${statement.array}, ${statement.elementType}, ${expr(statement.size)});")

            is MultiAssign -> {
                val single = statement.names.singleOrNull()
                val names = statement.names.joinToString(", ") { it.name }
                val binding = if (single != null) single.name else "[$names]"
                line("const $binding = ${expr(statement.call)};")
            }

            is Return -> when (statement.values.size) {
                0 -> line("return;")
                1 -> line("return ${expr(statement.values.first())};")
                else -> line("return [${statement.values.joinToString(", ") { expr(it) }}];")
            }

            is If -> {
                line("if (${condition(statement.condition)}) {")
                indented { emitAll(statement.then) }
                if (statement.otherwise.isEmpty()) {
                    line("}")
                } else {
                    line("} else {")
                    indented { emitAll(statement.otherwise) }
                    line("}")
                }
            }

            is While -> {
                line("while (${statement.condition?.let { condition(it) } ?: "true"}) {")
                indented { emitAll(statement.body) }
                line("}")
            }

            is Switch -> {
                val labels = caseLabels(statement)
                line("switch (${expr(statement.subject)}) {")
                indented {
                    // `default:` keeps the position it had in the bytecode:
                    // first when it falls out of the switch directly, last when
                    // a jump leads to it.
                    if (statement.defaultFirst && statement.default.isNotEmpty()) {
                        line("default:")
                        indented { emitAll(statement.default) }
                    }
                    for ((index, case) in statement.cases.withIndex()) {
                        if (!statement.defaultFirst && index == statement.defaultAt) {
                            line("default:")
                            indented { emitAll(statement.default) }
                        }
                        case.keys.forEach { line("case ${labels?.get(it) ?: it}:") }
                        indented { emitAll(case.body) }
                    }
                    if (!statement.defaultFirst && statement.defaultAt >= statement.cases.size) {
                        line("default:")
                        indented { emitAll(statement.default) }
                    }
                }
                line("}")
            }

            is Label -> {
                indent--
                // The empty statement keeps a label legal even when it lands at
                // the end of a block, which TypeScript otherwise rejects.
                line("${statement.name}: ;")
                indent++
            }

            is Goto -> line("goto(\"${statement.label}\");")
            is Break -> line(if (statement.label == null) "break;" else "break ${statement.label};")
            is Continue -> line(if (statement.label == null) "continue;" else "continue ${statement.label};")
        }
    }

    /**
     * Case labels spelled as the things they select, or null where nothing says what those are.
     *
     * The bar is the whole switch, not the single label. Where the corpus typed the subject every
     * label wears that type; failing that, the only reading a bare integer vouches for on its own
     * is a component, and then only when every key names a pair Jagex's own dictionary holds. One
     * key that merely looks packed - a loc id, a packed coordinate - leaves all of them numeric.
     * Once the switch clears that bar each key still answers for itself, so one whose component
     * reading rests on its shape alone carries the mark, or the number, like any other.
     */
    private fun caseLabels(statement: Switch): Map<Int, String>? {
        val kind = statement.keyType
        val keys = statement.cases.flatMap { it.keys }
        if (kind == null && keys.any { Cs2Gamevals.componentBasis(it) == null }) return null
        val labels = HashMap<Int, String>()
        for (key in keys) {
            labels[key] = if (kind == null) shapedComponent(key) ?: key.toString() else typedConst(TypedConst(key, kind))
        }
        return labels.ifEmpty { null }
    }

    /**
     * `BRANCH_IF_TRUE` / `BRANCH_IF_FALSE` test a single value against a literal
     * rather than comparing two. Rendering them as `x == 1` would re-compile to
     * a different opcode, so they keep a form of their own.
     */
    private fun condition(condition: Condition, parentBinds: Boolean = false): String = when (condition) {
        is Compare -> {
            val subject = expr(condition.left)
            when (condition.comparison.opName) {
                "BRANCH_IF_TRUE" -> if (condition.negated) "isFalsy($subject)" else "isTruthy($subject)"
                "BRANCH_IF_FALSE" -> if (condition.negated) "isTruthy($subject)" else "isFalsy($subject)"
                // A negated comparison is one the compiler used to *skip* the
                // body, so the opcode is kept and the sense written out.
                else -> {
                    val test = "$subject ${condition.naturalSymbol} ${expr(condition.right)}"
                    if (condition.negated) "!($test)" else test
                }
            }
        }
        is AndAll -> condition.terms.joinToString(" && ") { condition(it, parentBinds = true) }
            .let { if (parentBinds) "($it)" else it }
        is OrAny -> condition.terms.joinToString(" || ") { condition(it, parentBinds = true) }
            .let { if (parentBinds) "($it)" else it }
    }

    // ---------------------------------------------------------- expressions

    private fun expr(value: Expr): String = when (value) {
        is IntConst -> intConst(value)
        is TypedConst -> typedConst(value)
        is StrConst -> "\"${Cs2Strings.escape(value.value)}\""
        is LongConst -> "${value.value}n"
        is LocalRef -> localName(value.slot, value.type)
        is TempRef -> value.name
        is ArrayRef -> "array${value.array}[${expr(value.index)}]"
        is VarRef -> varAccess(value)
        is Binary -> "(${expr(value.left)} ${value.symbol} ${expr(value.right)})"
        is Join -> value.parts.joinToString(" + ") { expr(it) }
        is OpCall -> {
            // The instruction's own operand rides along as a type argument, so
            // it survives editing without cluttering the call's arguments.
            val variant = if (value.operand != 0) "<${value.operand}>" else ""
            "${value.op.tsName}$variant(${value.args.joinToString(", ") { expr(it) }})"
        }
        is ScriptCall -> "${Cs2Symbols.scriptName(value.scriptId)}(${value.args.joinToString(", ") { expr(it) }})"
        is Callback -> callback(value)
    }

    /**
     * A plain constant, named where something vouches for the name.
     *
     * Only a value whose two halves name a component Jagex's own dictionary
     * holds is spelled as one; nothing else about an integer says what it means,
     * so everything else stays a number. See [shapedComponent] for what happens
     * where that dictionary is not the only claim on the value.
     */
    private fun intConst(value: IntConst): String {
        val literal = shapedComponent(value.value) ?: value.value.toString()
        return if (value.dedicated) "pushConstantInt($literal)" else literal
    }

    /**
     * A bare integer spelled as the component it addresses, or null where nothing does that for it.
     *
     * Where the value reads equally well as a position the dictionary is no longer the only claim
     * on it, and a reading picked on shape alone is marked as one - or, where the reader wants
     * none, left as the integer it is.
     */
    private fun shapedComponent(value: Int): String? = when (Cs2Gamevals.componentBasis(value)) {
        Cs2ComponentBasis.UNAMBIGUOUS -> {
            components.unambiguous++
            Cs2Gamevals.component(value)
        }
        Cs2ComponentBasis.SHAPE -> {
            components.shaped++
            if (shapeReading == Cs2ShapeReading.NUMERIC) {
                null
            } else {
                "component($UNVERIFIED ${Cs2Gamevals.componentArgument(value)})"
            }
        }
        null -> null
    }

    /**
     * Spells out a composite constant. A component hash becomes the interface
     * and component it packs; a coordinate becomes x, y and plane. Values that
     * carry no such structure - `-1` and other sentinels - keep the single
     * argument form so they still round-trip.
     */
    private fun typedConst(value: TypedConst): String = when (value.kind) {
        ArgType.COMPONENT -> {
            components.determined++
            if (Cs2Packing.isPackedComponent(value.value)) {
                Cs2Gamevals.component(value.value)
            } else {
                "component(${value.value})"
            }
        }
        ArgType.COORD ->
            if (Cs2Packing.isPackedCoord(value.value)) {
                "tile(${Cs2Packing.xOf(value.value)}, ${Cs2Packing.yOf(value.value)}, " +
                    "${Cs2Packing.planeOf(value.value)})"
            } else {
                "tile(${value.value})"
            }
        ArgType.ITEM -> named("item", value)
        ArgType.NPC -> named("npc", value)
        ArgType.LOC -> named("loc", value)
        ArgType.SEQ -> named("seq", value)
        ArgType.SPOTANIM -> "spotanim(${value.value})"
        ArgType.ENUM -> named("enumId", value)
        ArgType.STRUCT -> named("struct", value)
        ArgType.PARAM -> named("param", value)
        ArgType.INV -> named("inv", value)
        ArgType.STAT -> "skill(${value.value})"
        ArgType.SCRIPT -> "scriptId(${value.value})"
        // A flag reads as a flag; anything outside 0/1 keeps its number so the
        // value still round-trips.
        ArgType.BOOLEAN -> when (value.value) {
            0 -> "false"
            1 -> "true"
            else -> value.value.toString()
        }
        ArgType.COLOUR -> "colour(0x%06X)".format(value.value and 0xFFFFFF)
        ArgType.GRAPHIC -> named("graphic", value)
        ArgType.MODEL -> named("model", value)
        ArgType.QUEST -> named("quest", value)
        ArgType.FONTMETRICS -> named("fontmetrics", value)
        ArgType.INTERFACE -> named("interfaceId", value)
        ArgType.VARBIT -> named("varbitId", value)
        ArgType.CURSOR -> named("cursor", value)
        ArgType.SOUND -> named("sound", value)
        ArgType.ACHIEVEMENT -> named("achievement", value)
        ArgType.MATERIAL -> named("material", value)
        ArgType.BAS -> named("bas", value)
        ArgType.MAPELEMENT -> named("mapelement", value)
        ArgType.IDKIT -> named("idkit", value)
        ArgType.STRING, ArgType.COORDFINE, ArgType.INT -> value.value.toString()
    }

    /**
     * `item(obj.coins)` where the opcode's own argument type says which table to
     * read, and `item(995)` where that table has no name for the id.
     */
    private fun named(spelling: String, value: TypedConst): String =
        "$spelling(${Cs2Gamevals.member(value.kind, value.value) ?: value.value})"

    private fun varAccess(value: VarRef): String = Cs2Symbols.variableName(value)

    /**
     * A cleared hook still carries whatever its type spec describes, so the
     * short form is only for the empty one that spec actually implies.
     */
    private fun callback(value: Callback): String {
        val format = "\"${Cs2Strings.escape(value.format)}\""
        if (value.cleared) return "noHook($format)"
        val id = value.target
        val script = if (id is IntConst && id.value >= 0) Cs2Symbols.scriptName(id.value) else expr(id)
        val target = "hook($script, $format"
        val args = "[${value.args.joinToString(", ") { hookArgument(it) }}]"
        if (!value.hasTriggerList) return "$target, $args)"
        val triggers = "[${value.triggers.joinToString(", ") { expr(it) }}]"
        return "$target, $args, $triggers)"
    }

    /**
     * A bound hook argument, named where it is a placeholder rather than a value.
     *
     * Only the int stack is substituted, so only an integer constant can be one;
     * the hook's type spec is consumed at registration and has no say in it.
     */
    private fun hookArgument(value: Expr): String {
        val placeholder = when (value) {
            is IntConst -> Cs2EventArg.of(value.value)
            is TypedConst -> Cs2EventArg.of(value.value)
            else -> null
        } ?: return expr(value)
        val name = placeholder.identifier
        return if (value is IntConst && value.dedicated) "pushConstantInt($name)" else name
    }

    // --------------------------------------------------------------- output

    private fun line(text: String) {
        if (text.isEmpty()) {
            out.append('\n')
            return
        }
        repeat(indent) { out.append("    ") }
        out.append(text).append('\n')
    }

    private inline fun indented(body: () -> Unit) {
        indent++
        body()
        indent--
    }

    private companion object {
        /**
         * The mark a shape-read component carries. It is a comment, which the compiler skips as
         * trivia, so a marked rendering re-compiles to exactly the bytes an unmarked one would.
         */
        const val UNVERIFIED = "/* unverified */"
    }
}
