package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.AnimBaseBone
import world.gregs.voidps.cache.type.data.AnimBaseExtra
import world.gregs.voidps.cache.type.data.AnimBaseTransform
import world.gregs.voidps.cache.type.data.AnimBaseType

/**
 * The lossless read of a framebase: a transform list, then a skeleton block, with no opcodes and no
 * terminator - the file ends exactly where the last record does.
 */
class AnimBaseDecoder : TypeDecoder<AnimBaseType>(Index.ANIMATION_SKELETONS) {

    override fun create(size: Int) = Array(size) { AnimBaseType(it) }

    override fun size(cache: Cache): Int = cache.lastArchiveId(index)

    override fun getFile(id: Int) = 0

    override fun AnimBaseType.read(opcode: Int, buffer: Reader) = Unit

    override fun readLoop(definition: AnimBaseType, buffer: Reader) {
        recordDecode(definition.id, buffer) { decode(definition, buffer) }
    }

    private fun decode(definition: AnimBaseType, buffer: Reader) {
        var transformCount = buffer.readUnsignedShort()
        if (transformCount == EXTENDED_HEADER) {
            definition.version = buffer.readUnsignedByte()
            transformCount = buffer.readUnsignedShort()
        } else {
            definition.version = AnimBaseType.DEFAULT_VERSION
        }
        readTransforms(definition, buffer, transformCount)
        readSkeleton(definition, buffer)
        definition.extraCount = buffer.readUnsignedByte()
        if (definition.version > AnimBaseType.DEFAULT_VERSION) {
            readExtras(definition, buffer)
        }
    }

    private fun readTransforms(definition: AnimBaseType, buffer: Reader, count: Int) {
        val types = IntArray(count) { buffer.readUnsignedByte() }
        val unusedA = IntArray(count) { buffer.readUnsignedByte() }
        val unusedB = IntArray(count) { buffer.readUnsignedShort() }
        val labelCounts = IntArray(count) { buffer.readUnsignedSmart() }
        val transforms = ArrayList<AnimBaseTransform>(count)
        for (transform in 0 until count) {
            val labels = IntArray(labelCounts[transform]) { buffer.readUnsignedSmart() }
            transforms.add(AnimBaseTransform(types[transform], unusedA[transform], unusedB[transform], labels))
        }
        definition.transforms = transforms
    }

    private fun readSkeleton(definition: AnimBaseType, buffer: Reader) {
        val boneCount = buffer.readUnsignedShort()
        definition.matrixSetCount = buffer.readUnsignedByte()
        val floats = definition.matrixSetCount * AnimBaseBone.MATRIX_FLOATS
        val bones = ArrayList<AnimBaseBone>(boneCount)
        for (bone in 0 until boneCount) {
            val label = buffer.readUnsignedShort()
            bones.add(AnimBaseBone(label, FloatArray(floats) { buffer.readFloat() }))
        }
        definition.bones = bones
        definition.remapCount = buffer.readShort()
        definition.remaps = IntArray(maxOf(definition.remapCount, 0)) { buffer.readUnsignedShort() }
    }

    private fun readExtras(definition: AnimBaseType, buffer: Reader) {
        val extras = ArrayList<AnimBaseExtra>(definition.extraCount)
        for (extra in 0 until definition.extraCount) {
            val name = buffer.readString()
            val key = buffer.readUnsignedShort()
            val b = buffer.readUnsignedShort()
            extras.add(AnimBaseExtra(name, key, b, FloatArray(AnimBaseExtra.FLOATS) { buffer.readFloat() }))
        }
        definition.extras = extras
    }

    private companion object {
        /** A transform count that instead selects the header carrying an explicit version. */
        private const val EXTENDED_HEADER = 0xffff
    }
}
