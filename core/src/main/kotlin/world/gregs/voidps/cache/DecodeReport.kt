package world.gregs.voidps.cache

/**
 * Opt-in decode telemetry: an unrecognised opcode consumes no payload, so the opcode loop reads the
 * next payload byte as an opcode and every field after it is silently garbage. Trailing bytes prove
 * the record was not fully understood; a zero-advance opcode localises where it broke, but is only a
 * suspicion because zero-payload flag opcodes are legal.
 */
class DecodeReport {

    data class Opcode(val opcode: Int, val position: Int)

    class Record(
        val type: String,
        val id: Int,
        val zeroAdvance: List<Opcode>,
        val trailingBytes: Int,
        val failure: Throwable?,
    ) {
        override fun toString(): String = buildString {
            append(type).append(' ').append(id)
            if (trailingBytes > 0) append(" trailing=").append(trailingBytes)
            if (zeroAdvance.isNotEmpty()) append(" zeroAdvance=").append(zeroAdvance.joinToString { "${it.opcode}@${it.position}" })
            failure?.let { append(" failure=").append(it) }
        }
    }

    class Unknown(val type: String, val id: Int, val opcode: Int, val position: Int)

    private val lock = Any()
    private val issues = ArrayList<Record>()
    private val unknowns = ArrayList<Unknown>()
    private val counts = HashMap<String, Int>()

    val records: List<Record> get() = synchronized(lock) { issues.toList() }

    fun decoded(type: String): Int = synchronized(lock) { counts[type] ?: 0 }

    val types: Set<String> get() = synchronized(lock) { counts.keys.toSet() }

    val decoded: Int get() = synchronized(lock) { counts.values.sum() }

    fun record(type: String, id: Int, zeroAdvance: List<Opcode>, trailingBytes: Int) {
        synchronized(lock) {
            counts[type] = (counts[type] ?: 0) + 1
            if (trailingBytes > 0 || zeroAdvance.isNotEmpty()) {
                issues.add(Record(type, id, zeroAdvance, trailingBytes, null))
            }
        }
    }

    fun failure(type: String, id: Int, cause: Throwable) {
        synchronized(lock) {
            counts[type] = (counts[type] ?: 0) + 1
            issues.add(Record(type, id, emptyList(), 0, cause))
        }
    }

    fun unknown(type: String, id: Int, opcode: Int, position: Int) {
        synchronized(lock) { unknowns.add(Unknown(type, id, opcode, position)) }
    }

    fun unknowns(type: String): List<Unknown> = synchronized(lock) { unknowns.filter { it.type == type } }

    /** Opcode -> number of definitions whose decoder had no arm for it. */
    fun unknownOpcodeCounts(type: String): Map<Int, Int> {
        val counts = HashMap<Int, Int>()
        for (unknown in unknowns(type).distinctBy { it.id to it.opcode }) {
            counts[unknown.opcode] = (counts[unknown.opcode] ?: 0) + 1
        }
        return counts
    }

    fun trailing(type: String): List<Record> = records.filter { it.type == type && it.trailingBytes > 0 }

    fun zeroAdvance(type: String): List<Record> = records.filter { it.type == type && it.zeroAdvance.isNotEmpty() }

    fun failures(type: String): List<Record> = records.filter { it.type == type && it.failure != null }

    /** Opcode -> number of definitions in which it advanced the reader by zero bytes. */
    fun zeroAdvanceOpcodeCounts(type: String): Map<Int, Int> {
        val counts = HashMap<Int, Int>()
        for (record in records) {
            if (record.type != type) continue
            for (opcode in record.zeroAdvance.distinctBy { it.opcode }) {
                counts[opcode.opcode] = (counts[opcode.opcode] ?: 0) + 1
            }
        }
        return counts
    }

    private fun StringBuilder.appendCounts(label: String, counts: Map<Int, Int>) {
        if (counts.isEmpty()) return
        append(" [").append(label).append(' ')
        append(counts.entries.sortedByDescending { it.value }.joinToString { "op${it.key} in ${it.value} defs" })
        append(']')
    }

    fun summary(): String = buildString {
        for (type in types.sorted()) {
            append(type).append(": ").append(decoded(type)).append(" decoded, ")
            append(trailing(type).size).append(" with trailing bytes, ")
            append(zeroAdvance(type).size).append(" with zero-advance opcodes, ")
            append(failures(type).size).append(" failed")
            appendCounts("zero-advance", zeroAdvanceOpcodeCounts(type))
            appendCounts("unknown", unknownOpcodeCounts(type))
            append('\n')
        }
    }
}
