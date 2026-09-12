package world.gregs.voidps.cache.gameval

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter

/**
 * One archive of the gameval index as the file holds it: a version, a count, and a name per id.
 *
 * Two layouts exist. The dense one lists an offset per id from 0 until the count, `-1` for an id
 * with no name; the sparse one lists `(id, offset)` pairs. Both are followed by one blob of
 * null-terminated strings, which [encode] lays out in id order with nothing shared - what nearly
 * every archive Jagex ships does too.
 */
class GamevalFile(
    val version: Int,
    /** The dense layout's array length, which can run past the last named id. */
    val count: Int,
    /** Id to raw name bytes, in file order, absent ids left out. */
    val names: LinkedHashMap<Int, ByteArray>
) {

    val dense: Boolean
        get() = version == VERSION_DENSE

    fun encode(): ByteArray {
        val blob = ArrayList<ByteArray>(names.size)
        val offsets = HashMap<Int, Int>(names.size * 2)
        var position = 0
        for ((id, name) in names) {
            offsets[id] = position
            blob.add(name)
            position += name.size + 1
        }
        val header = 8 + if (dense) count * 4 else names.size * 8
        val writer = BufferWriter(header + position)
        writer.writeInt(version)
        writer.writeInt(if (dense) count else names.size)
        if (dense) {
            for (id in 0 until count) {
                writer.writeInt(offsets[id] ?: -1)
            }
        } else {
            for (id in names.keys) {
                writer.writeInt(id)
                writer.writeInt(offsets.getValue(id))
            }
        }
        for (name in blob) {
            writer.writeBytes(name)
            writer.writeByte(0)
        }
        return writer.toArray()
    }

    companion object {
        const val VERSION_DENSE = 1
        const val VERSION_SPARSE = 2

        fun decode(data: ByteArray): GamevalFile {
            val reader = BufferReader(data)
            val version = reader.readInt()
            val count = reader.readInt()
            require(count >= 0) { "A gameval file cannot hold $count entries." }
            val ids: IntArray
            val offsets: IntArray
            when (version) {
                VERSION_DENSE -> {
                    ids = IntArray(count) { it }
                    offsets = IntArray(count) { reader.readInt() }
                }
                VERSION_SPARSE -> {
                    ids = IntArray(count)
                    offsets = IntArray(count)
                    for (index in 0 until count) {
                        ids[index] = reader.readInt()
                        offsets[index] = reader.readInt()
                    }
                }
                else -> throw IllegalArgumentException("Unknown gameval file version $version.")
            }
            val base = reader.position()
            val names = LinkedHashMap<Int, ByteArray>(count.coerceAtMost(1 shl 20))
            for (index in 0 until count) {
                val offset = offsets[index]
                if (offset < 0) {
                    continue
                }
                var end = base + offset
                while (end < data.size && data[end] != 0.toByte()) {
                    end++
                }
                names[ids[index]] = data.copyOfRange(base + offset, end)
            }
            return GamevalFile(version, count, names)
        }
    }
}
