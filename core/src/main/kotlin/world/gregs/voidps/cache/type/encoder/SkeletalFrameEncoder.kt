package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.SkeletalFrameType

/**
 * The frame decoder backwards.
 *
 * No framebase is needed: which values a frame carries is what the decode resolved and the file already
 * holds them in wire order, so the whole run is written back as it stands.
 */
class SkeletalFrameEncoder : TypeEncoder<SkeletalFrameType> {

    override fun Writer.encode(definition: SkeletalFrameType) {
        writeByte(definition.version)
        writeShort(definition.frameBaseId)
        writeShort(definition.masks.size)
        for (mask in definition.masks) {
            writeByte(mask)
        }
        var wide = 0
        for ((position, value) in definition.values.withIndex()) {
            if (wide < definition.wide.size && definition.wide[wide] == position) {
                wide++
                writeShort(value - 0x4000 and 0xffff)
            } else {
                writeSignedSmart(value)
            }
        }
    }
}
