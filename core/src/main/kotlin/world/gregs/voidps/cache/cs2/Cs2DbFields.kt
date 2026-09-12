package world.gregs.voidps.cache.cs2

import world.gregs.voidps.cache.cs2.ir.Cs2Type
import world.gregs.voidps.cache.cs2.ir.Expr
import world.gregs.voidps.cache.cs2.ir.IntConst
import world.gregs.voidps.cache.cs2.ir.OpCall
import world.gregs.voidps.cache.cs2.ir.TypedConst

/**
 * What one `db_getfield` leaves on the stacks.
 *
 * A column repeats a tuple of declared types. The packed operand carries the table, the column
 * and a selector: unset, the opcode reads the whole tuple, one value per type, each on the stack
 * its own type names; set, it reads only the tuple element the selector counts to.
 */
object Cs2DbFields {

    fun tableOf(column: Int): Int = column ushr 12

    fun columnOf(column: Int): Int = (column ushr 4) and 0xFF

    /** Which single tuple element the call wants, counted from one; zero asks for all of them. */
    fun selectorOf(column: Int): Int = column and 0xF

    /** Stacks the result lands on, in declaration order. */
    fun results(column: Int): List<Cs2Type> = selected(column)?.mapNotNull(::stackOf) ?: listOf(Cs2Type.INT)

    fun signature(column: Int): ReturnSignature = signatureOf(results(column))

    /** The declared type of each value a call leaves, one for one with [results]. */
    fun declaredTypes(column: Int): List<Int> = selected(column)?.filter { stackOf(it) != null } ?: emptyList()

    /** The declared type of the result at [index], or null where the column declares a plain int. */
    fun declaredType(column: Int, index: Int): Int? =
        declaredTypes(column).getOrNull(index)?.takeIf { it != UNTYPED }

    /** What a search compares its value against, where the column selects exactly one element. */
    fun searchedType(column: Int): Int? = selected(column)?.singleOrNull()?.takeIf { it != UNTYPED }

    /** The packed column a call reads its results out of, where that is what its opcode does. */
    fun fieldColumn(call: OpCall): Int? =
        if (call.op.naming.canonical == GET_FIELD) constantOf(call.args.getOrNull(FIELD_COLUMN)) else null

    /** Which argument of a call carries a packed column, where the call addresses one. */
    fun columnPosition(call: OpCall): Int? =
        call.op.naming.names.firstNotNullOfOrNull { COLUMN_ARGUMENTS[it] }

    /** The packed column a call searches, paired with the value it matches against it. */
    fun searched(call: OpCall): Pair<Int, Expr>? {
        if (call.op.naming.canonical !in SEARCHES) return null
        val column = constantOf(call.args.getOrNull(SEARCH_COLUMN)) ?: return null
        val value = call.args.getOrNull(SEARCHED_VALUE) ?: return null
        return column to value
    }

    fun signatureOf(results: List<Cs2Type>) = ReturnSignature(
        ints = results.count { it == Cs2Type.INT },
        strings = results.count { it == Cs2Type.STRING },
        longs = results.count { it == Cs2Type.LONG },
    )

    /** The packed column a call carries, as its second argument. */
    fun columnArgument(args: List<Expr>): Int = when (val argument = args.getOrNull(FIELD_COLUMN)) {
        is IntConst -> argument.value
        is TypedConst -> argument.value
        else -> -1
    }

    private const val WHOLE_TUPLE = 0

    /** The type that says only "a number"; a column declaring it names nothing worth carrying. */
    private const val UNTYPED = 0

    private const val GET_FIELD = "db_getfield"

    private val SEARCHES = setOf("db_find", "db_find_with_count")

    private const val FIELD_COLUMN = 1

    private const val SEARCH_COLUMN = 0

    private const val SEARCHED_VALUE = 1

    /**
     * Which argument carries the packed column, for every opcode that addresses one.
     *
     * A reading opcode names the row first and the column second; a searching one has no row yet,
     * so the column leads, and a filtering one puts the column where its own shape leaves room.
     * Wherever it lands the operand packs a table, a column and a selector together, which is not
     * the interface and component a value of that shape otherwise reads as.
     */
    private val COLUMN_ARGUMENTS: Map<String, Int> = mapOf(
        GET_FIELD to FIELD_COLUMN,
        "db_getfieldcount" to FIELD_COLUMN,
        "db_find_filtered" to FIELD_COLUMN,
        "db_find_filtered_limit" to FIELD_COLUMN,
        "db_find_refine" to SEARCH_COLUMN,
        "dbfilter" to SEARCH_COLUMN,
    ) + SEARCHES.associateWith { SEARCH_COLUMN }

    /** The tuple elements a packed column asks for, or null where the cache has no such column. */
    private fun selected(column: Int): List<Int>? {
        val types = Cs2Records.dbTable(tableOf(column))?.types(columnOf(column)) ?: return null
        val selector = selectorOf(column)
        if (selector == WHOLE_TUPLE) return types.toList()
        return listOfNotNull(types.getOrNull(selector - 1))
    }

    /** A type the client keeps off all three operand stacks contributes nothing. */
    private fun stackOf(type: Int): Cs2Type? = when (Cs2VarTypes.baseOfId(type)) {
        Cs2VarBase.STRING -> Cs2Type.STRING
        Cs2VarBase.LONG -> Cs2Type.LONG
        Cs2VarBase.WIDE -> null
        else -> Cs2Type.INT
    }
}
