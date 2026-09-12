package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.type.decoder.MapSquareLocDecoder
import world.gregs.voidps.cache.type.data.MapSquareObject
import world.gregs.voidps.cache.type.data.MapSquareTransform

/**
 * The inverse of [MapSquareLocDecoder].
 *
 * The file's shape is the list's own order: consecutive objects sharing an id are one group, and
 * the deltas between groups and within them are what is written. A list that came from a decode
 * reproduces its file; a list an editor appended to still encodes, it just spends a delta per group.
 */
object MapSquareLocEncoder {

    fun Writer.encode(locations: List<MapSquareObject>) {
        var id = -1
        var index = 0
        while (index < locations.size) {
            val groupId = locations[index].id
            writeLargeSmart(groupId - id)
            id = groupId
            var position = 0
            while (index < locations.size && locations[index].id == groupId) {
                val location = locations[index++]
                val packed = (location.plane shl 12) or (location.localX shl 6) or location.localY
                writeSmart(packed - position + 1)
                position = packed
                val transform = location.transform
                val data = (location.shape shl 2) or location.rotation
                writeByte(if (transform == null) data else data or MapSquareLocDecoder.TRANSFORM)
                if (transform != null) {
                    write(transform)
                }
            }
            writeSmart(0)
        }
        writeLargeSmart(0)
    }

    private fun Writer.write(transform: MapSquareTransform) {
        writeByte(transform.flags())
        transform.rotation?.forEach { writeShort(it) }
        transform.translateX?.let { writeShort(it) }
        transform.translateY?.let { writeShort(it) }
        transform.translateZ?.let { writeShort(it) }
        transform.scale?.let {
            writeShort(it)
            return
        }
        transform.scaleX?.let { writeShort(it) }
        transform.scaleY?.let { writeShort(it) }
        transform.scaleZ?.let { writeShort(it) }
    }
}
