package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.type.data.MapSquareObject
import world.gregs.voidps.cache.type.data.MapSquareTransform

/**
 * The lossless read of a locations file.
 *
 * Objects are grouped by id: an id delta of 0 ends the file, and within a group a position delta of
 * 0 ends the group. Both deltas accumulate, so the ids of the groups ascend and the positions inside
 * one group never go backwards - which is what `MapSquareLocEncoder` has to reproduce to write the
 * same bytes back.
 */
object MapSquareLocDecoder {

    fun decode(data: ByteArray): MutableList<MapSquareObject> {
        val locations = ArrayList<MapSquareObject>()
        val buffer = BufferReader(data)
        var id = -1
        while (true) {
            val idDelta = buffer.readSmartSizeVar()
            if (idDelta == 0) {
                break
            }
            id += idDelta
            var position = 0
            while (true) {
                val positionDelta = buffer.readUnsignedSmart()
                if (positionDelta == 0) {
                    break
                }
                position += positionDelta - 1
                val data = buffer.readUnsignedByte()
                locations.add(
                    MapSquareObject(
                        id = id,
                        localX = (position shr 6) and 0x3f,
                        localY = position and 0x3f,
                        plane = position shr 12,
                        shape = (data shr 2) and 0x1f,
                        rotation = data and 0x3,
                        transform = if (data and TRANSFORM != 0) readTransform(buffer) else null
                    )
                )
            }
        }
        return locations
    }

    private fun readTransform(buffer: Reader): MapSquareTransform {
        val flags = buffer.readUnsignedByte()
        val transform = MapSquareTransform()
        if (flags and 0x1 != 0) {
            transform.rotation = IntArray(MapSquareTransform.ROTATION_SIZE) { buffer.readShort() }
        }
        if (flags and 0x2 != 0) transform.translateX = buffer.readShort()
        if (flags and 0x4 != 0) transform.translateY = buffer.readShort()
        if (flags and 0x8 != 0) transform.translateZ = buffer.readShort()
        if (flags and 0x10 != 0) {
            transform.scale = buffer.readShort()
        } else {
            if (flags and 0x20 != 0) transform.scaleX = buffer.readShort()
            if (flags and 0x40 != 0) transform.scaleY = buffer.readShort()
            if (flags and 0x80 != 0) transform.scaleZ = buffer.readShort()
        }
        return transform
    }

    /** The bit of an object's data byte that says a placement override follows. */
    const val TRANSFORM = 0x80
}
