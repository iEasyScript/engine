package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.UnusedRecords

/**
 * A column's declared shape. [types] is the tuple layout every value in the column repeats,
 * and [default] holds the table-level fallback when a row leaves the column unset.
 */
class DbColumn(val index: Int, val types: IntArray, val default: List<Any> = emptyList(), val flags: Int = 0)

data class DbTableType(
    override var id: Int = -1,
    var columns: Map<Int, DbColumn> = emptyMap(),
    var header: Int = 0,
    var columnCount: Int = 0,
) : CacheType, UnusedRecords {
    override var unusedRecords: Map<Int, List<ByteArray>>? = null
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null

    fun column(index: Int): DbColumn? = columns[index]

    fun types(index: Int): IntArray? = columns[index]?.types
}
