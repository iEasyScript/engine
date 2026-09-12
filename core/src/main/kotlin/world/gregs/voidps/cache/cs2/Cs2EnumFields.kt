package world.gregs.voidps.cache.cs2

import world.gregs.voidps.cache.cs2.ir.Expr
import world.gregs.voidps.cache.cs2.ir.OpCall
import world.gregs.voidps.cache.type.data.EnumType

/** Which half of an enum's declaration a value belongs to. */
enum class Cs2EnumSide { KEY, VALUE }

/**
 * What the cache says the halves of an enum hold, and which arguments of a call carry them.
 *
 * An enum record declares the type of its keys and the type of its values. A call repeats those
 * two declarations as literal arguments, so the record is a check on the call rather than the only
 * source: a call whose literals contradict the enum it names is not believed at all.
 */
object Cs2EnumFields {

    /** True where the opcode reads an enum, so nothing else may claim its result. */
    fun reads(call: OpCall): Boolean = shapeOf(call) != null

    /** The argument holding the enum a call reads, where the call is consistent with its record. */
    fun enumArgument(call: OpCall): Expr? {
        val shape = shapeOf(call) ?: return null
        if (!consistent(call, shape)) return null
        return call.args.getOrNull(shape.enumArg)
    }

    /** Arguments whose type the call declares, each paired with the type id declared for it. */
    fun declarations(call: OpCall): List<Pair<Expr, Int>> {
        val shape = shapeOf(call) ?: return emptyList()
        if (!consistent(call, shape)) return emptyList()
        val declared = ArrayList<Pair<Expr, Int>>(2)
        declare(call, shape, Cs2EnumSide.KEY, shape.keyArg, declared)
        declare(call, shape, Cs2EnumSide.VALUE, shape.valueArg, declared)
        return declared
    }

    /** The declared type of what a call leaves behind, where it leaves one of the enum's halves. */
    fun resultType(call: OpCall): Int? {
        val shape = shapeOf(call) ?: return null
        val side = shape.result ?: return null
        if (!consistent(call, shape)) return null
        return typeOf(call, shape, side)
    }

    private data class Shape(
        val enumArg: Int,
        val keyTypeArg: Int = ABSENT,
        val valueTypeArg: Int = ABSENT,
        val keyArg: Int = ABSENT,
        val valueArg: Int = ABSENT,
        val result: Cs2EnumSide? = null,
    )

    private const val ABSENT = -1

    /** The type that says only "a number"; a half declaring it names nothing worth carrying. */
    private const val UNTYPED = 0

    private val SHAPES = mapOf(
        "enum" to Shape(enumArg = 2, keyTypeArg = 0, valueTypeArg = 1, keyArg = 3, result = Cs2EnumSide.VALUE),
        "enum_string" to Shape(enumArg = 0, keyArg = 1),
        "enum_hasoutput" to Shape(enumArg = 1, valueTypeArg = 0, valueArg = 2),
        "enum_getreversecount" to Shape(enumArg = 1, valueTypeArg = 0, valueArg = 2),
        "enum_getreverseindex" to Shape(
            enumArg = 2,
            valueTypeArg = 0,
            keyTypeArg = 1,
            valueArg = 3,
            result = Cs2EnumSide.KEY,
        ),
    )

    private fun shapeOf(call: OpCall): Shape? = SHAPES[call.op.naming.canonical]

    private fun declare(
        call: OpCall,
        shape: Shape,
        side: Cs2EnumSide,
        slot: Int,
        out: MutableList<Pair<Expr, Int>>,
    ) {
        val argument = call.args.getOrNull(slot) ?: return
        val type = typeOf(call, shape, side) ?: return
        out.add(argument to type)
    }

    /** The call's own literal for a half, or failing that what the enum it names declares. */
    private fun typeOf(call: OpCall, shape: Shape, side: Cs2EnumSide): Int? {
        val slot = if (side == Cs2EnumSide.KEY) shape.keyTypeArg else shape.valueTypeArg
        val literal = constantOf(call.args.getOrNull(slot))
        val declared = literal ?: recordOf(call, shape)?.let { sideOf(it, side) }
        return declared?.takeIf { it != UNTYPED }
    }

    private fun sideOf(record: EnumType, side: Cs2EnumSide): Int =
        if (side == Cs2EnumSide.KEY) record.keyTypeId else record.valueTypeId

    private fun consistent(call: OpCall, shape: Shape): Boolean {
        val record = recordOf(call, shape) ?: return true
        return agrees(call.args.getOrNull(shape.keyTypeArg), record.keyTypeId) &&
            agrees(call.args.getOrNull(shape.valueTypeArg), record.valueTypeId)
    }

    private fun agrees(declaration: Expr?, declared: Int): Boolean {
        val literal = constantOf(declaration) ?: return true
        return literal == declared
    }

    private fun recordOf(call: OpCall, shape: Shape): EnumType? =
        constantOf(call.args.getOrNull(shape.enumArg))?.let { Cs2Records.enums().getOrNull(it) }
}
