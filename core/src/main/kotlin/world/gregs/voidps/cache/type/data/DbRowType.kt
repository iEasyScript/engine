package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.UnusedRecords

/**
 * One column's payload. [values] is flattened row-major: [tupleCount] tuples of [types].size
 * entries each, so tuple `t` field `f` sits at `t * types.size + f`.
 */
class DbColumnValues(val types: IntArray, val values: List<Any>) {
    val tupleCount: Int
        get() = if (types.isEmpty()) 0 else values.size / types.size

    fun value(tuple: Int, field: Int = 0): Any = values[tuple * types.size + field]

    fun int(tuple: Int, field: Int = 0): Int = value(tuple, field) as Int

    fun string(tuple: Int, field: Int = 0): String = value(tuple, field) as String
}

data class DbRowType(
    override var id: Int = -1,
    var table: Int = -1,
    var columns: Map<Int, DbColumnValues> = emptyMap(),
    var columnCount: Int = 0,
) : CacheType, UnusedRecords {
    override var unusedRecords: Map<Int, List<ByteArray>>? = null
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null

    fun column(index: Int): DbColumnValues? = columns[index]

    fun int(index: Int, tuple: Int = 0, field: Int = 0): Int? =
        columns[index]?.takeIf { it.tupleCount > tuple }?.int(tuple, field)

    fun string(index: Int, tuple: Int = 0, field: Int = 0): String? =
        columns[index]?.takeIf { it.tupleCount > tuple }?.string(tuple, field)
}
