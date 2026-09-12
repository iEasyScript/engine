package world.gregs.voidps.cache.type

import world.gregs.voidps.buffer.read.Reader

interface Transforms {
    var varbit: Int
    var varp: Int
    var transforms: IntArray?

    fun readTransforms(buffer: Reader, isLast: Boolean, wideVarbit: Boolean) {
        varbit = buffer.readVarbit(wideVarbit)
        varp = buffer.readUnsignedShort().nullTransform()
        var last = -1
        if (isLast) {
            last = buffer.readBigSmart()
        }
        val length = buffer.readSmart()
        transforms = IntArray(length + 2)
        for (count in 0..length) {
            transforms!![count] = buffer.readBigSmart()
        }
        transforms!![length + 1] = last
    }

    fun readShortTransforms(buffer: Reader, isLast: Boolean, wideVarbit: Boolean) {
        varbit = buffer.readVarbit(wideVarbit)
        varp = buffer.readUnsignedShort().nullTransform()
        val last = if (isLast) buffer.readUnsignedShort().nullTransform() else -1
        val length = buffer.readUnsignedByte()
        val ids = IntArray(length + 2)
        for (count in 0..length) {
            ids[count] = buffer.readUnsignedShort().nullTransform()
        }
        ids[length + 1] = last
        transforms = ids
    }

    private fun Int.nullTransform() = if (this == NULL_SHORT) -1 else this

    companion object {

        /** The client never tests a decoded id, so absence is only ever all-ones of the field's width. */
        const val NULL_SHORT = 0xffff

        const val NULL_WIDE_VARBIT = 0xffffff

        fun Reader.readVarbit(wide: Boolean): Int {
            if (!wide) {
                return readUnsignedShort().let { if (it == NULL_SHORT) -1 else it }
            }
            return readUnsignedMedium().let { if (it == NULL_WIDE_VARBIT) -1 else it }
        }
    }
}
