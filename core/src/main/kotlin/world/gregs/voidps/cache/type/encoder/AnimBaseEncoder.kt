package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.AnimBaseType

/** The framebase decoder backwards, byte for byte. */
class AnimBaseEncoder : TypeEncoder<AnimBaseType> {

    override fun Writer.encode(definition: AnimBaseType) {
        val transforms = definition.transforms
        if (definition.version == AnimBaseType.DEFAULT_VERSION) {
            writeShort(transforms.size)
        } else {
            writeShort(EXTENDED_HEADER)
            writeByte(definition.version)
            writeShort(transforms.size)
        }
        for (transform in transforms) {
            writeByte(transform.type)
        }
        for (transform in transforms) {
            writeByte(transform.unusedA)
        }
        for (transform in transforms) {
            writeShort(transform.unusedB)
        }
        for (transform in transforms) {
            writeSmart(transform.labels.size)
        }
        for (transform in transforms) {
            for (label in transform.labels) {
                writeSmart(label)
            }
        }
        writeShort(definition.bones.size)
        writeByte(definition.matrixSetCount)
        for (bone in definition.bones) {
            writeShort(bone.label)
            for (value in bone.matrices) {
                writeFloat(value)
            }
        }
        writeShort(definition.remapCount)
        for (remap in definition.remaps) {
            writeShort(remap)
        }
        writeByte(definition.extraCount)
        if (definition.version <= AnimBaseType.DEFAULT_VERSION) {
            return
        }
        for (extra in definition.extras) {
            writeString(extra.name)
            writeShort(extra.key)
            writeShort(extra.b)
            for (value in extra.values) {
                writeFloat(value)
            }
        }
    }

    private companion object {
        private const val EXTENDED_HEADER = 0xffff
    }
}
