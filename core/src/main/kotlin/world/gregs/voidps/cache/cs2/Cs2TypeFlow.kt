package world.gregs.voidps.cache.cs2

import world.gregs.voidps.cache.cs2.ir.ArrayAssign
import world.gregs.voidps.cache.cs2.ir.ArrayDefine
import world.gregs.voidps.cache.cs2.ir.ArrayRef
import world.gregs.voidps.cache.cs2.ir.AndAll
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
import world.gregs.voidps.cache.cs2.ir.TempRef
import world.gregs.voidps.cache.cs2.ir.TypedConst
import world.gregs.voidps.cache.cs2.ir.VarAssign
import world.gregs.voidps.cache.cs2.ir.VarRef
import world.gregs.voidps.cache.cs2.ir.VarSpace
import world.gregs.voidps.cache.cs2.ir.While

/** Which reading of the corpus settled an operand's type. */
enum class Cs2TypeSource {
    PARAMETER,
    LOCAL,
    VARIABLE,
    ARRAY,

    /** The variable's, param's or column's declared script-variable type. */
    DECLARED,

    /** What a call gives back - a script's return slot or an opcode's result. */
    RESULT,

    /** An opcode argument slot the corpus typed, where the client's own handler read did not. */
    ARGUMENT,

    /** One determined position elsewhere has been handed every one of these exact numbers. */
    WITNESS,
}

data class Cs2Determined(val type: ArgType, val source: Cs2TypeSource)

/** The call that produced a destructured binding, and which of its results it is. */
data class Cs2TempSource(val producer: Expr, val index: Int)

/**
 * The gameval table each integer operand indexes, where the corpus determines one.
 *
 * Every id space overlaps almost totally - a switch label is a valid loc, model, obj, sound and
 * npc id all at once - so "does a name exist" says nothing at all about which table to read. The
 * only thing that does is the type the value carries, and the ground truth for that is the argument
 * slots the client's own handlers type ([Cs2Op.argTypes]). This propagates those slots through the
 * corpus: values that provably hold one another's contents share a type, and a type is reported
 * only where exactly one survives every observation of it.
 *
 * Two families are read back out of the same constraints rather than into them. An opcode argument
 * slot the handler read left untyped takes the type of whatever the corpus repeatedly puts there,
 * and a set of numbers takes the type of a determined position that has been handed every one of
 * them. Both are aggregates over the settled carriers, computed after they settle, so neither can
 * feed a guess back into the evidence.
 *
 * A carrier that is ever used as two different types is *not* narrowed to the popular one; it is
 * dropped. Under-applying leaves a number, which is honest. Over-applying leaves a name that reads
 * as knowledge and is wrong.
 */
class Cs2TypeFlow internal constructor(
    private val carriers: Map<Carrier, ArgType>,
    private val wears: Map<Carrier, Int>,
    private val declared: Map<Int, ArgType>,
    private val results: Map<ResultKey, ArgType>,
    private val arguments: Map<ArgumentKey, ArgType>,
    private val witnesses: Map<Int, Map<String, ArgType>>,
    val report: Report,
) {

    data class Report(
        val scripts: Int,
        val determinedCarriers: Int,
        val conflictedCarriers: Int,
        val declaredTypes: Int,
        val opcodeResults: Int,
        val argumentSlots: Int,
        val witnessedConstants: Int,
    )

    fun typeOf(script: Int, expr: Expr, temps: Map<String, Cs2TempSource>): Cs2Determined? =
        typeOf(script, expr, temps, 0)

    private fun typeOf(script: Int, expr: Expr, temps: Map<String, Cs2TempSource>, index: Int): Cs2Determined? =
        when (expr) {
            is LocalRef ->
                if (expr.type != Cs2Type.INT) null
                else carrier(SlotCarrier(script, expr.slot), Cs2TypeSource.LOCAL)
            is VarRef ->
                if (expr.type != Cs2Type.INT) null
                else carrier(VarCarrier(expr.space, expr.id), Cs2TypeSource.VARIABLE)
                    ?: spelled(declaredTypeOf(expr)?.let { declared[it] }, Cs2TypeSource.DECLARED)
            is ArrayRef -> carrier(ArrayCarrier(script, expr.array), Cs2TypeSource.ARRAY)
            is ScriptCall -> carrier(ReturnCarrier(expr.scriptId, index), Cs2TypeSource.RESULT)
            is TempRef -> temps[expr.name]?.let { typeOf(script, it.producer, temps, it.index) }
            is OpCall -> {
                val declaredResult = declaredResultOf(expr, index)
                if (declaredResult != null) spelled(declared[declaredResult], Cs2TypeSource.DECLARED)
                else spelled(results[ResultKey(expr.op.id, index)], Cs2TypeSource.RESULT)
            }
            else -> null
        }

    /**
     * A determination worth printing.
     *
     * Plain ints, flags, colours and the like are tracked all the same, because a value used as one
     * of them is evidence *against* every table; they simply have nothing to be spelled as.
     */
    private fun spelled(type: ArgType?, source: Cs2TypeSource): Cs2Determined? =
        if (type != null && spellable(type)) Cs2Determined(type, source) else null

    /** What the corpus settled on for a carrier, or failing that what its declared type says. */
    private fun carrier(carrier: Carrier, source: Cs2TypeSource): Cs2Determined? =
        spelled(carriers[carrier], source)
            ?: spelled(wears[carrier]?.let { declared[it] }, Cs2TypeSource.DECLARED)

    /** Script-variable type id to the table its values index, for the report. */
    fun declaredTables(): Map<Int, ArgType> = declared.filterValues(::spellable)

    /** Opcode id and result index to the table that result indexes, for the report. */
    fun resultTables(): Map<ResultKey, ArgType> = results.filterValues(::spellable)

    /** Opcode id and argument position to the table that argument indexes, for the report. */
    fun argumentTables(): Map<ArgumentKey, ArgType> = arguments.filterValues(::spellable)

    /**
     * What an opcode's argument slot holds, where the corpus settled it.
     *
     * This is the mirror of the client's own handler read: where [Cs2Op.argTypes] says nothing
     * about a slot, what the corpus repeatedly puts *into* that slot does. It only ever reports a
     * type every typed value reaching the slot agreed on, so one slot fed two kinds stays silent.
     */
    fun argumentType(opcode: Int, position: Int): Cs2Determined? =
        spelled(arguments[ArgumentKey(opcode, position)], Cs2TypeSource.ARGUMENT)

    /**
     * The table a whole set of numbers indexes, where one determined position elsewhere has been
     * handed every single one of them.
     *
     * Id spaces overlap almost totally, so neither a name existing in a table nor one number
     * turning up in a typed position says anything on its own - 25028 is a real obj, loc, npc, seq
     * and struct at once, and the corpus does use it as several of them. What is not a coincidence
     * is a *slot* the corpus already determined receiving this exact combination of numbers: the
     * set is the evidence, not any member of it. Requiring the whole set to land in one place is
     * what separates that from the popularity contest a per-number vote would be.
     */
    fun sharedType(values: List<Int>): ArgType? {
        var common: Map<String, ArgType>? = null
        for (value in values) {
            if (value == NOTHING) continue
            val sites = witnesses[value] ?: return null
            val previous = common
            common = if (previous == null) sites else sites.filterKeys { it in previous.keys }
            if (common.isEmpty()) return null
        }
        val single = common?.values?.toSet()?.singleOrNull() ?: return null
        return if (spellable(single)) single else null
    }

    /** Every determined position one number has been handed, for the `types` report. */
    fun sightings(value: Int): Map<String, ArgType> = witnesses[value].orEmpty()

    fun slotType(script: Int, slot: Int, source: Cs2TypeSource): Cs2Determined? =
        carrier(SlotCarrier(script, slot), source)

    fun returnType(script: Int, index: Int): Cs2Determined? =
        carrier(ReturnCarrier(script, index), Cs2TypeSource.RESULT)

    fun arrayType(script: Int, array: Int): Cs2Determined? =
        carrier(ArrayCarrier(script, array), Cs2TypeSource.ARRAY)

    /** True where spelling [value] as [type] produces a name rather than an invented one. */
    fun names(type: ArgType, value: Int): Boolean = when (type) {
        ArgType.COMPONENT -> Cs2Gamevals.componentOrNull(value) != null
        ArgType.COORD -> Cs2Packing.isPackedCoord(value)
        else -> Cs2Gamevals.table(type) != null && Cs2Gamevals.member(type, value) != null
    }

    companion object {

        /** No corpus behind it: every operand keeps the number it is. */
        val NONE = Cs2TypeFlow(
            emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
            Report(0, 0, 0, 0, 0, 0, 0),
        )

        /** Spelled the same in every table, so it never names one. */
        const val NOTHING = -1

        /** Kinds that spell out to something a reader gains from; the rest stay numbers. */
        fun spellable(type: ArgType): Boolean =
            type == ArgType.COMPONENT || type == ArgType.COORD || Cs2Gamevals.table(type) != null

        fun build(context: Cs2Context, ids: IntArray): Cs2TypeFlow {
            val builder = Builder()
            var scripts = 0
            for (id in ids) {
                val script = context.script(id) ?: continue
                val function = try {
                    Cs2Structurer(script, id, context).structure()
                } catch (e: Exception) {
                    continue
                }
                builder.collect(function)
                scripts++
            }
            return builder.solve(scripts)
        }
    }
}

internal sealed interface Carrier

internal data class SlotCarrier(val script: Int, val slot: Int) : Carrier

internal data class VarCarrier(val space: VarSpace, val id: Int) : Carrier

/** [index] counts every returned value, strings included, so it matches a caller's binding. */
internal data class ReturnCarrier(val script: Int, val index: Int) : Carrier

internal data class ArrayCarrier(val script: Int, val array: Int) : Carrier

data class ResultKey(val opcode: Int, val index: Int)

/** An opcode's own argument slot, counted in push order across every int it takes. */
data class ArgumentKey(val opcode: Int, val position: Int)

/** Every destructured binding in a function, so a [TempRef] resolves to the call behind it. */
internal fun cs2Temps(function: Cs2Function): Map<String, Cs2TempSource> {
    val out = HashMap<String, Cs2TempSource>()
    fun walk(statements: List<Stmt>) {
        for (statement in statements) {
            when (statement) {
                is MultiAssign ->
                    statement.names.forEachIndexed { at, name -> out[name.name] = Cs2TempSource(statement.call, at) }
                is If -> {
                    walk(statement.then)
                    walk(statement.otherwise)
                }
                is While -> walk(statement.body)
                is Switch -> {
                    statement.cases.forEach { walk(it.body) }
                    walk(statement.default)
                }
                else -> Unit
            }
        }
    }
    walk(function.body)
    return out
}

/** The script-variable type a variable's own config record declares, where it declares one. */
internal fun declaredTypeOf(reference: VarRef): Int? {
    val domain = reference.space.domain
    if (domain == VarSpace.NO_DOMAIN) return null
    return Cs2VarDeclarations.declaredOf(domain, reference.id)
}

/**
 * The declared type an opcode's result wears, for the opcodes whose result type is not their own
 * but their argument's: a param yields whatever the param is declared as, an enum whatever its
 * value type says, a database field whatever the column it reads holds.
 *
 * A database field can leave a whole tuple at once, so [index] picks out which of the call's
 * results is being asked about.
 */
internal fun declaredResultOf(call: OpCall, index: Int = 0): Int? {
    if (Cs2EnumFields.reads(call)) return Cs2EnumFields.resultType(call)
    Cs2DbFields.fieldColumn(call)?.let { return Cs2DbFields.declaredType(it, index) }
    if (call.op.kind == OpKind.PARAM) {
        val paramId = call.args.lastOrNull { it.type == Cs2Type.INT }?.let(::constantOf) ?: return null
        return Cs2Records.params().getOrNull(paramId)?.typeId?.takeIf { it != 0 }
    }
    return null
}

internal fun constantOf(expr: Expr?): Int? = when (expr) {
    is IntConst -> expr.value
    is TypedConst -> expr.value
    else -> null
}

/**
 * Collects the corpus into equalities between carriers and observations against them, then reads
 * off the carriers that survived with exactly one type.
 */
private class Builder {

    private val parent = HashMap<Carrier, Carrier>()
    private val observed = HashMap<Carrier, MutableSet<ArgType>>()

    /** Carriers a declared type or an opcode result flows into, and slots it was seen used as. */
    private val declaredFeeds = HashMap<Int, MutableSet<Carrier>>()
    private val wears = HashMap<Carrier, Int>()
    private val ambiguous = HashSet<Carrier>()
    private val declaredSlots = HashMap<Int, MutableSet<ArgType>>()
    private val resultFeeds = HashMap<ResultKey, MutableSet<Carrier>>()
    private val resultSlots = HashMap<ResultKey, MutableSet<ArgType>>()

    /** Every typed place each bare integer literal was seen, so its own table can be read off. */
    private val witnessCarriers = HashMap<Int, MutableSet<Carrier>>()
    private val witnessArguments = HashMap<Int, MutableSet<ArgumentKey>>()

    /** What was seen in an opcode argument slot the client's handler read left untyped. */
    private val argumentFeeds = HashMap<ArgumentKey, MutableSet<Carrier>>()
    private val argumentDeclared = HashMap<ArgumentKey, MutableSet<Int>>()
    private val argumentResults = HashMap<ArgumentKey, MutableSet<ResultKey>>()

    fun collect(function: Cs2Function) {
        val script = function.scriptId
        val temps = cs2Temps(function)
        Collector(script, temps, this).walk(function.body)
    }

    fun find(carrier: Carrier): Carrier {
        var root = carrier
        while (true) {
            val next = parent[root] ?: break
            if (next == root) break
            root = next
        }
        var walk = carrier
        while (walk != root) {
            val next = parent[walk] ?: break
            parent[walk] = root
            walk = next
        }
        return root
    }

    fun union(left: Carrier, right: Carrier) {
        val a = find(left)
        val b = find(right)
        if (a == b) return
        parent[a] = b
        val moved = observed.remove(a) ?: return
        observed.getOrPut(b) { HashSet() }.addAll(moved)
    }

    fun observe(carrier: Carrier, type: ArgType) {
        observed.getOrPut(find(carrier)) { HashSet() }.add(type)
    }

    fun feedDeclared(typeId: Int, carrier: Carrier) {
        declaredFeeds.getOrPut(typeId) { HashSet() }.add(carrier)
        val existing = wears.put(carrier, typeId)
        if (existing != null && existing != typeId) ambiguous.add(carrier)
    }

    fun observeDeclared(typeId: Int, type: ArgType) {
        declaredSlots.getOrPut(typeId) { HashSet() }.add(type)
    }

    fun feedResult(key: ResultKey, carrier: Carrier) {
        resultFeeds.getOrPut(key) { HashSet() }.add(carrier)
    }

    fun observeResult(key: ResultKey, type: ArgType) {
        resultSlots.getOrPut(key) { HashSet() }.add(type)
    }

    fun feedArgument(key: ArgumentKey, carrier: Carrier) {
        argumentFeeds.getOrPut(key) { HashSet() }.add(carrier)
    }

    fun feedArgumentDeclared(key: ArgumentKey, typeId: Int) {
        argumentDeclared.getOrPut(key) { HashSet() }.add(typeId)
    }

    fun feedArgumentResult(key: ArgumentKey, result: ResultKey) {
        argumentResults.getOrPut(key) { HashSet() }.add(result)
    }

    fun witness(value: Int, carrier: Carrier) {
        witnessCarriers.getOrPut(value) { HashSet() }.add(carrier)
    }

    fun witness(value: Int, key: ArgumentKey) {
        witnessArguments.getOrPut(value) { HashSet() }.add(key)
    }

    fun solve(scripts: Int): Cs2TypeFlow {
        val settled = HashMap<Carrier, ArgType>()
        for (carrier in parent.keys + observed.keys) {
            val single = observed[find(carrier)]?.singleOrNull() ?: continue
            settled[carrier] = single
        }
        val conflicted = observed.values.count { it.size > 1 }

        val declared = HashMap<Int, ArgType>()
        for ((typeId, feeds) in declaredFeeds) {
            aggregate(feeds, settled, declaredSlots[typeId])?.let { declared[typeId] = it }
        }
        val results = HashMap<ResultKey, ArgType>()
        for ((key, feeds) in resultFeeds) {
            aggregate(feeds, settled, resultSlots[key])?.let { results[key] = it }
        }
        val arguments = HashMap<ArgumentKey, ArgType>()
        for (key in argumentFeeds.keys + argumentDeclared.keys + argumentResults.keys) {
            val elsewhere = argumentDeclared[key].orEmpty().mapNotNull { declared[it] } +
                argumentResults[key].orEmpty().mapNotNull { results[it] }
            aggregate(argumentFeeds[key].orEmpty(), settled, elsewhere.toSet())?.let { arguments[key] = it }
        }
        val witnesses = HashMap<Int, Map<String, ArgType>>()
        for (value in witnessCarriers.keys + witnessArguments.keys) {
            val seen = HashMap<String, ArgType>()
            for (carrier in witnessCarriers[value].orEmpty()) {
                // Keyed by the position itself, not by the family it joined: the
                // whole point is that one *place* received every one of these
                // numbers, and a family is far too large a place to mean that.
                observed[find(carrier)]?.singleOrNull()?.let { seen["$carrier"] = it }
            }
            for (key in witnessArguments[value].orEmpty()) {
                arguments[key]?.let { seen["$key"] = it }
            }
            if (seen.isNotEmpty()) witnesses[value] = seen
        }
        val worn = wears.filterKeys { it !in ambiguous }
        return Cs2TypeFlow(
            settled, worn, declared, results, arguments, witnesses,
            Cs2TypeFlow.Report(
                scripts, settled.size, conflicted, declared.size, results.size, arguments.size,
                witnesses.size,
            ),
        )
    }

    /**
     * One type for a whole family, or nothing.
     *
     * Every member that has a type has to agree, and enough of them have to have one that the
     * answer is about the family rather than about a single site that happened to be typed.
     */
    private fun aggregate(
        feeds: Set<Carrier>,
        settled: Map<Carrier, ArgType>,
        slots: Set<ArgType>?,
    ): ArgType? {
        val types = HashSet<ArgType>()
        var agreeing = 0
        for (carrier in feeds) {
            val type = settled[carrier] ?: continue
            types.add(type)
            agreeing++
        }
        slots?.let {
            types.addAll(it)
            agreeing += it.size
        }
        val single = types.singleOrNull() ?: return null
        return if (agreeing >= MIN_AGREEING) single else null
    }

    private companion object {
        const val MIN_AGREEING = 3
    }
}

/** Walks one function, turning every place a value moves or is used into a constraint. */
private class Collector(
    private val script: Int,
    private val temps: Map<String, Cs2TempSource>,
    private val builder: Builder,
) {

    fun walk(statements: List<Stmt>) {
        for (statement in statements) walk(statement)
    }

    private fun walk(statement: Stmt) {
        when (statement) {
            is ExprStmt -> value(statement.call)
            is LocalAssign -> {
                if (statement.type == Cs2Type.INT) link(SlotCarrier(script, statement.slot), statement.value)
                value(statement.value)
            }
            is VarAssign -> {
                carrierOf(statement.target)?.let { link(it, statement.value) }
                value(statement.target)
                value(statement.value)
            }
            is ArrayAssign -> {
                link(ArrayCarrier(script, statement.array), statement.value)
                value(statement.index)
                value(statement.value)
            }
            is ArrayDefine -> {
                if (statement.elementType != 0) {
                    builder.feedDeclared(statement.elementType, ArrayCarrier(script, statement.array))
                }
                value(statement.size)
            }
            is ParallelAssign -> {
                statement.targets.forEachIndexed { at, target ->
                    val source = statement.values.getOrNull(at) ?: return@forEachIndexed
                    carrierOf(target)?.let { link(it, source) }
                }
                statement.values.forEach(::value)
            }
            is MultiAssign -> value(statement.call)
            is Return -> statement.values.forEachIndexed { at, returned ->
                if (returned.type == Cs2Type.INT) link(ReturnCarrier(script, at), returned)
                value(returned)
            }
            is If -> {
                condition(statement.condition)
                walk(statement.then)
                walk(statement.otherwise)
            }
            is While -> {
                statement.condition?.let(::condition)
                walk(statement.body)
            }
            is Switch -> {
                value(statement.subject)
                carrierOf(statement.subject)?.let { subject ->
                    statement.cases.flatMap { it.keys }.forEach { builder.witness(it, subject) }
                }
                statement.cases.forEach { walk(it.body) }
                walk(statement.default)
            }
            else -> Unit
        }
    }

    /**
     * Two sides of a comparison hold the same kind of thing, which is what lets a bare literal
     * tested against a typed value be spelled out.
     */
    private fun condition(condition: Condition) {
        when (condition) {
            is Compare -> {
                value(condition.left)
                value(condition.right)
                if (condition.comparison.opName.endsWith("_IF_TRUE")) return
                if (condition.comparison.opName.endsWith("_IF_FALSE")) return
                val left = carrierOf(condition.left) ?: return
                link(left, condition.right)
            }
            is AndAll -> condition.terms.forEach(::condition)
            is OrAny -> condition.terms.forEach(::condition)
        }
    }

    /** Descends into an expression, recording what its own arguments say about them. */
    private fun value(expr: Expr) {
        when (expr) {
            is OpCall -> {
                arguments(expr)
                cacheDeclared(expr)
                expr.args.forEach(::value)
            }
            is ScriptCall -> {
                var index = 0
                for (argument in expr.args) {
                    if (argument.type == Cs2Type.INT) link(SlotCarrier(expr.scriptId, index++), argument)
                    value(argument)
                }
            }
            is Callback -> {
                val target = constantOf(expr.target)
                var index = 0
                for (argument in expr.args) {
                    if (argument.type != Cs2Type.INT) continue
                    if (target != null && target >= 0) {
                        val slot = SlotCarrier(target, index)
                        link(slot, argument)
                        // The dispatcher swaps the live event's own value in over these.
                        when (constantOf(argument)?.let(Cs2EventArg::of)) {
                            Cs2EventArg.SOURCE_COMPONENT, Cs2EventArg.TARGET_COMPONENT ->
                                builder.observe(slot, ArgType.COMPONENT)
                            else -> Unit
                        }
                    }
                    index++
                }
                expr.args.forEach(::value)
                expr.triggers.forEach(::value)
            }
            is Binary -> {
                value(expr.left)
                value(expr.right)
            }
            is Join -> expr.parts.forEach(::value)
            // A variable is both a carrier of its own and one sample of its declared type.
            is VarRef -> {
                if (expr.type == Cs2Type.INT) {
                    declaredTypeOf(expr)?.let { builder.feedDeclared(it, VarCarrier(expr.space, expr.id)) }
                }
            }
            else -> Unit
        }
    }

    /**
     * What each of a call's argument slots holds.
     *
     * Where the client's own handler read typed the slot that reading is ground truth, and anything
     * reaching the slot is that type. Where it did not, the slot is a family of its own: whatever
     * the corpus repeatedly puts there types the slot, which is what lets a bare integer in it be
     * spelled out. A hook setter's call carries a callback rather than the arguments the slot list
     * describes, so its list never applies positionally - but its own slots still gather evidence.
     */
    private fun arguments(call: OpCall) {
        val types = if (call.op.kind == OpKind.HOOK) emptyList() else call.op.argTypes
        // The column argument addresses a database rather than anything with a table of names.
        val column = Cs2DbFields.columnPosition(call)
        call.args.forEachIndexed { at, argument ->
            if (argument.type != Cs2Type.INT || at == column) return@forEachIndexed
            val kind = types.getOrNull(at)
            if (kind != null && kind != ArgType.INT && kind != ArgType.STRING) {
                observe(argument, kind)
                return@forEachIndexed
            }
            if (kind != null) return@forEachIndexed
            val key = ArgumentKey(call.op.id, at)
            literalOf(argument)?.let {
                builder.witness(it, key)
                return@forEachIndexed
            }
            carrierOf(argument)?.let {
                builder.feedArgument(key, it)
                return@forEachIndexed
            }
            declaredOf(argument)?.let {
                builder.feedArgumentDeclared(key, it)
                return@forEachIndexed
            }
            resultOf(argument)?.let { builder.feedArgumentResult(key, it) }
        }
    }

    /**
     * The types the cache itself declares for what a call moves.
     *
     * An enum record names the type of its keys and of its values; a database column names the
     * tuple of types every value in it repeats. Both are Jagex's own declarations, so an argument
     * that reaches one of those positions carries that type wherever else it goes.
     */
    private fun cacheDeclared(call: OpCall) {
        Cs2EnumFields.enumArgument(call)?.let { observe(it, ArgType.ENUM) }
        for ((argument, type) in Cs2EnumFields.declarations(call)) declare(type, argument)
        val searched = Cs2DbFields.searched(call) ?: return
        Cs2DbFields.searchedType(searched.first)?.let { declare(it, searched.second) }
    }

    private fun declare(typeId: Int, expr: Expr) {
        carrierOf(expr)?.let { builder.feedDeclared(typeId, it) }
    }

    private fun link(target: Carrier, source: Expr) {
        literalOf(source)?.let {
            builder.witness(it, target)
            return
        }
        carrierOf(source)?.let {
            builder.union(target, it)
            return
        }
        declaredOf(source)?.let {
            builder.feedDeclared(it, target)
            return
        }
        resultOf(source)?.let { builder.feedResult(it, target) }
    }

    private fun observe(expr: Expr, type: ArgType) {
        carrierOf(expr)?.let {
            builder.observe(it, type)
            return
        }
        declaredOf(expr)?.let {
            builder.observeDeclared(it, type)
            return
        }
        resultOf(expr)?.let { builder.observeResult(it, type) }
    }

    /** The number a bare literal is, sentinels excluded - `-1` means "nothing" in every table. */
    private fun literalOf(expr: Expr): Int? = when (expr) {
        is IntConst -> expr.value.takeIf { it != NOTHING }
        is TypedConst -> expr.value.takeIf { it != NOTHING }
        else -> null
    }

    private fun carrierOf(expr: Expr, index: Int = 0): Carrier? = when (expr) {
        is LocalRef -> if (expr.type == Cs2Type.INT) SlotCarrier(script, expr.slot) else null
        is VarRef -> if (expr.type == Cs2Type.INT) VarCarrier(expr.space, expr.id) else null
        is ArrayRef -> ArrayCarrier(script, expr.array)
        is ScriptCall -> ReturnCarrier(expr.scriptId, index)
        is TempRef -> temps[expr.name]?.let { carrierOf(it.producer, it.index) }
        else -> null
    }

    private fun declaredOf(expr: Expr, index: Int = 0): Int? = when (expr) {
        is VarRef -> declaredTypeOf(expr)
        is OpCall -> declaredResultOf(expr, index)
        is TempRef -> temps[expr.name]?.let { declaredOf(it.producer, it.index) }
        else -> null
    }

    private fun resultOf(expr: Expr, index: Int = 0): ResultKey? = when (expr) {
        is OpCall -> if (declaredResultOf(expr, index) == null) ResultKey(expr.op.id, index) else null
        is TempRef -> temps[expr.name]?.let { resultOf(it.producer, it.index) }
        else -> null
    }

    private companion object {
        /** Spelled the same in every table, so it never names one. */
        const val NOTHING = Cs2TypeFlow.NOTHING
    }
}
