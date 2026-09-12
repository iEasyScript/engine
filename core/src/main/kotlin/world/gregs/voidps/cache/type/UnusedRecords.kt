package world.gregs.voidps.cache.type

/**
 * A definition that keeps the payload of every record whose contents the client itself throws away.
 *
 * Nobody knows what most of these bytes mean, so they are held verbatim rather than given a field
 * apiece; keeping them is what lets the encoder put the record back. A payload list rather than one
 * payload because several of them legitimately appear once per element.
 */
interface UnusedRecords : OpcodeOrdered {

    var unusedRecords: Map<Int, List<ByteArray>>?

    @Suppress("UNCHECKED_CAST")
    fun keep(opcode: Int, payload: ByteArray) {
        val records = unusedRecords as? MutableMap<Int, MutableList<ByteArray>>
            ?: LinkedHashMap<Int, MutableList<ByteArray>>().also { unusedRecords = it }
        records.getOrPut(opcode) { ArrayList(1) }.add(payload)
    }

    override fun repeats(opcode: Int): Boolean = unusedRecords?.containsKey(opcode) == true
}
