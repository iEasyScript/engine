package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/**
 * One indexed value: an int, a long or a string in [value], or the tagged triple's four numbers in
 * [triple], since converting those to floats would not survive the trip back.
 */
class DbIndexRangeValue(
    var value: Any = 0,
    var triple: IntArray? = null,
)

/** One indexed value and the rows that hold it. */
class DbIndexValue(
    var value: Any = 0,
    var triple: IntArray? = null,
    var rowIds: IntArray = IntArray(0),
)

/** Every distinct value of one column, each with the rows that hold it. */
class DbIndexField(
    var type: Int = 0,
    var values: List<DbIndexValue> = emptyList(),
)

/**
 * The same values again in ascending order, sharing one concatenated row id list: value `i` owns
 * `lengths[i]` ids, starting where the values before it left off.
 */
class DbIndexRanges(
    var type: Int = 0,
    var values: List<DbIndexRangeValue> = emptyList(),
    var rowIds: IntArray = IntArray(0),
    var lengths: IntArray = IntArray(0),
) {
    val starts: IntArray
        get() {
            val starts = IntArray(lengths.size)
            var start = 0
            for (index in lengths.indices) {
                starts[index] = start
                start += lengths[index]
            }
            return starts
        }

    val ends: IntArray
        get() {
            val starts = starts
            return IntArray(lengths.size) { starts[it] + lengths[it] - 1 }
        }
}

/**
 * One column's index, which is one file of the dbtableindex group its table owns.
 *
 * [version] is the marker byte's value, or [UNMARKED] for a file that carries no marker and so is
 * version 1; the ranges block exists only above version 1.
 */
data class DbColumnIndexType(
    override var id: Int = -1,
    var version: Int = UNMARKED,
    var fields: List<DbIndexField> = emptyList(),
    var flags: Int = 0,
    var ranges: List<DbIndexRanges> = emptyList(),
) : CacheType {

    val table: Int
        get() = id shr 8

    val column: Int
        get() = id and 0xff

    val rowIds: Sequence<Int>
        get() = fields.asSequence().flatMap { it.values }.flatMap { it.rowIds.asSequence() }

    companion object {
        const val UNMARKED = -1
    }
}

/** One index per column the table indexes; in this cache index a file id *is* the column id. */
data class DbTableIndexType(
    override var id: Int = -1,
    var columns: Map<Int, DbColumnIndexType> = emptyMap(),
    var rowIds: IntArray = IntArray(0),
) : CacheType {

    val indexedColumns: IntArray
        get() = columns.keys.toIntArray()

    fun column(id: Int): DbColumnIndexType? = columns[id]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DbTableIndexType
        return id == other.id && columns == other.columns && rowIds.contentEquals(other.rowIds)
    }

    override fun hashCode(): Int = 31 * (31 * id + columns.hashCode()) + rowIds.contentHashCode()
}
