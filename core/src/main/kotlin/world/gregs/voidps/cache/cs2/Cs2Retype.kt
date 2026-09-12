package world.gregs.voidps.cache.cs2

import world.gregs.voidps.cache.cs2.ir.AndAll
import world.gregs.voidps.cache.cs2.ir.ArrayAssign
import world.gregs.voidps.cache.cs2.ir.ArrayDefine
import world.gregs.voidps.cache.cs2.ir.ArrayRef
import world.gregs.voidps.cache.cs2.ir.Binary
import world.gregs.voidps.cache.cs2.ir.Callback
import world.gregs.voidps.cache.cs2.ir.Compare
import world.gregs.voidps.cache.cs2.ir.Condition
import world.gregs.voidps.cache.cs2.ir.Cs2Function
import world.gregs.voidps.cache.cs2.ir.Cs2Type
import world.gregs.voidps.cache.cs2.ir.Expr
import world.gregs.voidps.cache.cs2.ir.ExprStmt
import world.gregs.voidps.cache.cs2.ir.If
import world.gregs.voidps.cache.cs2.ir.IntConst
import world.gregs.voidps.cache.cs2.ir.Join
import world.gregs.voidps.cache.cs2.ir.LocalAssign
import world.gregs.voidps.cache.cs2.ir.LocalRef
import world.gregs.voidps.cache.cs2.ir.MultiAssign
import world.gregs.voidps.cache.cs2.ir.OpCall
import world.gregs.voidps.cache.cs2.ir.OrAny
import world.gregs.voidps.cache.cs2.ir.ParallelAssign
import world.gregs.voidps.cache.cs2.ir.Return
import world.gregs.voidps.cache.cs2.ir.ScriptCall
import world.gregs.voidps.cache.cs2.ir.Stmt
import world.gregs.voidps.cache.cs2.ir.Switch
import world.gregs.voidps.cache.cs2.ir.SwitchCase
import world.gregs.voidps.cache.cs2.ir.TypedConst
import world.gregs.voidps.cache.cs2.ir.VarAssign
import world.gregs.voidps.cache.cs2.ir.While

/** What the corpus-wide type flow actually changed, counted so it can be audited. */
class Cs2TypeTally {
    var switches = 0
    var switchesTyped = 0
    var switchesUnnamed = 0
    var switchesSentinelOnly = 0
    var labels = 0
    var labelsNamed = 0
    var labelsUnknownSubject = 0
    var labelsUnnamed = 0
    var constants = 0
    var argumentSlots = 0
    var ambiguousSeen = 0
    var ambiguousComponent = 0
    var ambiguousCoord = 0
    var suppressedColumns = 0
    var suppressedConstants = 0
    var suppressedLabels = 0
    var suppressedHookArgs = 0
    val switchSources = HashMap<Cs2TypeSource, Int>()
    val constantSources = HashMap<Cs2TypeSource, Int>()
    val switchTypes = HashMap<ArgType, Int>()
    val constantTypes = HashMap<ArgType, Int>()

    fun switchTyped(source: Cs2TypeSource, type: ArgType) {
        switchSources[source] = (switchSources[source] ?: 0) + 1
        switchTypes[type] = (switchTypes[type] ?: 0) + 1
    }

    fun constant(source: Cs2TypeSource, type: ArgType) {
        constants++
        constantSources[source] = (constantSources[source] ?: 0) + 1
        constantTypes[type] = (constantTypes[type] ?: 0) + 1
    }
}

/**
 * Spells out the integer operands whose type the corpus determined.
 *
 * The rule everywhere is the one the component labels already used: the *position* is typed, not
 * the number. A switch is typed as a whole - one subject, one type, every label - and a single
 * label the determined table has no name for leaves the entire switch numeric, because a table
 * that cannot name what a script switches on is the wrong table.
 */
class Cs2Retype(private val flow: Cs2TypeFlow, private val tally: Cs2TypeTally = Cs2TypeTally()) {

    fun apply(function: Cs2Function): Cs2Function {
        if (flow === Cs2TypeFlow.NONE) return function
        val rewriter = Rewriter(function.scriptId, function.intArgs, cs2Temps(function))
        return Cs2Function(
            scriptId = function.scriptId,
            name = function.name,
            intArgs = function.intArgs,
            stringArgs = function.stringArgs,
            longArgs = function.longArgs,
            intLocals = function.intLocals,
            stringLocals = function.stringLocals,
            longLocals = function.longLocals,
            body = rewriter.statements(function.body),
            returns = function.returns,
            argumentOrder = function.argumentOrder,
        )
    }

    private inner class Rewriter(
        private val script: Int,
        private val intArgs: Int,
        private val temps: Map<String, Cs2TempSource>,
    ) {

        fun statements(body: List<Stmt>): List<Stmt> = body.map(::statement)

        private fun statement(statement: Stmt): Stmt = when (statement) {
            is ExprStmt -> ExprStmt(expr(statement.call))
            is LocalAssign -> statement.copy(
                value = typed(expr(statement.value), flow.slotType(script, statement.slot, sourceOf(statement.slot))),
            )
            is VarAssign -> statement.copy(value = typed(expr(statement.value), typeOf(statement.target)))
            is ArrayAssign -> statement.copy(
                index = expr(statement.index),
                value = typed(expr(statement.value), flow.arrayType(script, statement.array)),
            )
            is ArrayDefine -> statement.copy(size = expr(statement.size))
            is ParallelAssign -> statement.copy(
                values = statement.values.mapIndexed { at, value ->
                    typed(expr(value), statement.targets.getOrNull(at)?.let(::typeOf))
                },
            )
            is MultiAssign -> statement.copy(call = expr(statement.call))
            is Return -> statement.copy(values = returned(statement.values))
            is If -> If(condition(statement.condition), statements(statement.then), statements(statement.otherwise))
            is While -> While(statement.condition?.let(::condition), statements(statement.body))
            is Switch -> switch(statement)
            else -> statement
        }

        private fun returned(values: List<Expr>): List<Expr> =
            values.mapIndexed { at, value ->
                if (value.type != Cs2Type.INT) expr(value)
                else typed(expr(value), flow.returnType(script, at))
            }

        private fun switch(statement: Switch): Stmt {
            tally.switches++
            val keys = statement.cases.flatMap { it.keys }
            tally.labels += keys.size
            val rewritten = statement.copy(
                subject = expr(statement.subject),
                cases = statement.cases.map { case -> SwitchCase(case.keys, statements(case.body)) },
                default = statements(statement.default),
            )

            val determined = typeOf(statement.subject) ?: witnessedSubject(keys)
            if (determined == null) {
                tally.labelsUnknownSubject += keys.size
                return rewritten
            }
            if (!keys.all { flow.names(determined.type, it) }) {
                tally.switchesUnnamed++
                tally.labelsUnnamed += keys.size
                if (keys.all { flow.names(determined.type, it) || it == SENTINEL }) tally.switchesSentinelOnly++
                if (keys.all { Cs2Gamevals.componentOrNull(it) != null }) tally.suppressedLabels += keys.size
                return rewritten.copy(keyType = ArgType.INT)
            }
            tally.switchesTyped++
            tally.labelsNamed += keys.size
            tally.switchTyped(determined.source, determined.type)
            return rewritten.copy(keyType = determined.type)
        }

        /**
         * What a switch selects, read off its own labels where nothing types the subject.
         *
         * This is the weakest evidence available and the bar is correspondingly high: every single
         * label has to be a number the corpus repeatedly puts in typed positions, and every one of
         * them has to agree on which table that is. A switch on small integers has labels used as
         * everything, which conflicts and reports nothing - exactly the right answer.
         */
        private fun witnessedSubject(keys: List<Int>): Cs2Determined? {
            if (keys.count { it != SENTINEL } < MIN_WITNESSED_KEYS) return null
            val shared = flow.sharedType(keys) ?: return null
            return Cs2Determined(shared, Cs2TypeSource.WITNESS)
        }

        private fun condition(condition: Condition): Condition = when (condition) {
            is Compare -> compare(condition)
            is AndAll -> AndAll(condition.terms.map(::condition))
            is OrAny -> OrAny(condition.terms.map(::condition))
        }

        /** A test against a literal reads the literal as whatever the tested value holds. */
        private fun compare(condition: Compare): Condition {
            val name = condition.comparison.opName
            if (name.endsWith("_IF_TRUE") || name.endsWith("_IF_FALSE")) return condition
            return condition.copy(
                left = typed(expr(condition.left), typeOf(condition.right)),
                right = typed(expr(condition.right), typeOf(condition.left)),
            )
        }

        private fun expr(value: Expr): Expr = when (value) {
            // Already spelled out by the lifter, off the opcode's own argument slot.
            is TypedConst -> value.also { tally.argumentSlots++ }
            is OpCall -> value.copy(args = arguments(value))
            is ScriptCall -> value.copy(args = arguments(value))
            is Binary -> value.copy(left = expr(value.left), right = expr(value.right))
            is Join -> value.copy(parts = value.parts.map(::expr))
            is ArrayRef -> value.copy(index = expr(value.index))
            is Callback -> value.copy(args = bound(value), triggers = value.triggers.map(::expr))
            else -> value
        }

        /** A call's arguments are typed by the slots of the script they land in. */
        private fun arguments(call: ScriptCall): List<Expr> {
            var index = 0
            return call.args.map { argument ->
                if (argument.type != Cs2Type.INT) return@map expr(argument)
                typed(expr(argument), flow.slotType(call.scriptId, index++, Cs2TypeSource.PARAMETER))
            }
        }

        /**
         * An opcode's arguments, spelled by the slots they sit in.
         *
         * A slot the client's own handler read typed is already spelled out by the lifter; one the
         * corpus typed instead is spelled here. The column argument is neither: it addresses a
         * database, which is not an interface, so a component reading there is contradicted.
         */
        private fun arguments(call: OpCall): List<Expr> {
            val column = Cs2DbFields.columnPosition(call)
            return call.args.mapIndexed { at, argument ->
                if (at == column) {
                    val shaped = shaped(argument)
                    return@mapIndexed if (shaped == null) expr(argument)
                    else plain(shaped).also { tally.suppressedColumns++ }
                }
                if (argument.type != Cs2Type.INT) return@mapIndexed expr(argument)
                typed(expr(argument), flow.argumentType(call.op.id, at))
            }
        }

        /** A hook's bound arguments are typed by the slots of the script the hook runs. */
        private fun bound(hook: Callback): List<Expr> {
            val target = constantOf(hook.target)?.takeIf { it >= 0 } ?: return hook.args.map(::expr)
            var index = 0
            return hook.args.map { argument ->
                if (argument.type != Cs2Type.INT) return@map expr(argument)
                val slot = flow.slotType(target, index++, Cs2TypeSource.PARAMETER) ?: return@map expr(argument)
                val shaped = shaped(argument)?.takeUnless { flow.names(slot.type, it.value) }
                    ?: return@map typed(expr(argument), slot)
                tally.suppressedHookArgs++
                plain(shaped)
            }
        }

        private fun sourceOf(slot: Int) =
            if (slot < intArgs) Cs2TypeSource.PARAMETER else Cs2TypeSource.LOCAL

        private fun typeOf(value: Expr): Cs2Determined? {
            val determined = flow.typeOf(script, value, temps) ?: return null
            if (value is LocalRef && value.slot < intArgs) {
                return determined.copy(source = Cs2TypeSource.PARAMETER)
            }
            return determined
        }

        private fun typed(value: Expr, determined: Cs2Determined?): Expr {
            if (value !is IntConst || value.dedicated) return value
            if (Cs2Gamevals.componentBasis(value.value) == Cs2ComponentBasis.SHAPE) {
                tally.ambiguousSeen++
                when (determined?.type) {
                    ArgType.COMPONENT -> tally.ambiguousComponent++
                    ArgType.COORD -> tally.ambiguousCoord++
                    else -> Unit
                }
            }
            val kind = determined?.type ?: return value
            if (!flow.names(kind, value.value)) {
                val shaped = shaped(value) ?: return value
                tally.suppressedConstants++
                return plain(shaped)
            }
            tally.constant(determined.source, kind)
            return TypedConst(value.value, kind)
        }

        /** The shape the emitter reads off a bare integer where nothing else says what it is. */
        private fun shaped(value: Expr): IntConst? =
            (value as? IntConst)?.takeIf { !it.dedicated && Cs2Gamevals.componentOrNull(it.value) != null }

        /**
         * The value spelled as the number it is, because the position it sits in says it is one.
         *
         * A determined position is not somewhere to read an integer's shape: there the reading is
         * not merely unsupported but contradicted, and a wrong name that reads as knowledge is
         * worse than the number it replaced.
         */
        private fun plain(value: IntConst): Expr = TypedConst(value.value, ArgType.INT)
    }

    private companion object {
        /** "Nothing", spelled the same in every table, so it never names one. */
        const val SENTINEL = Cs2TypeFlow.NOTHING

        /**
         * How many labels a switch needs before its label set is evidence in its own right.
         *
         * One number landing in one determined slot somewhere is a coincidence; a combination
         * of them landing in the same slot is not.
         */
        const val MIN_WITNESSED_KEYS = 2
    }
}
