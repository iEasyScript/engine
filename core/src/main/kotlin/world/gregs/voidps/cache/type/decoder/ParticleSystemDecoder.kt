package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.ParticleField
import world.gregs.voidps.cache.type.ParticleModifierLayout
import world.gregs.voidps.cache.type.data.ParticleCurve
import world.gregs.voidps.cache.type.data.ParticleCurveKey
import world.gregs.voidps.cache.type.data.ParticleEmitter
import world.gregs.voidps.cache.type.data.ParticleModifier
import world.gregs.voidps.cache.type.data.ParticleShape
import world.gregs.voidps.cache.type.data.ParticleSystemType
import world.gregs.voidps.cache.type.particleShapeExtents
import world.gregs.voidps.cache.type.readVersionedRawString

/**
 * The current version is the only one this reads: the six older branches the client still carries
 * change which emitter fields exist, and nothing in the served cache exercises them.
 */
class ParticleSystemDecoder : TypeDecoder<ParticleSystemType>(INDEX) {

    override fun create(size: Int) = Array(size) { ParticleSystemType(it) }

    override fun readLoop(definition: ParticleSystemType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun ParticleSystemType.read(opcode: Int, buffer: Reader) = Unit

    private fun ParticleSystemType.decode(buffer: Reader) {
        version = buffer.readUnsignedByte()
        require(version == VERSION) { "Particle system $id is version $version, not $VERSION." }
        name = buffer.readVersionedRawString()
        flags = buffer.readUnsignedByte()
        emitters = List(buffer.readUnsignedByte()) { readEmitter(buffer) }
        padding = buffer.readableBytes()
        buffer.skip(padding)
    }

    private fun readEmitter(buffer: Reader) = ParticleEmitter(
        name = buffer.readVersionedRawString(),
        fieldA = buffer.readUnsignedByte(),
        fieldB = buffer.readUnsignedByte(),
        fieldC = buffer.readUnsignedByte(),
        flags = buffer.readUnsignedByte(),
        material = buffer.readUnsignedShort(),
        particleCap = buffer.readUnsignedShort(),
        uvColumns = buffer.readUnsignedByte(),
        uvRows = buffer.readUnsignedByte(),
        vectorA = readFloats(buffer, 2),
        vectorB = readFloats(buffer, 2),
        fieldG = buffer.readFloat(),
        fieldJ = buffer.readUnsignedByte(),
        fieldK = buffer.readFloat(),
        fieldL = buffer.readFloat(),
        fieldM = buffer.readFloat(),
        vectorC = readFloats(buffer, 3),
        vectorD = readFloats(buffer, 3),
        modifiers = List(buffer.readUnsignedByte()) { readModifier(buffer) },
    )

    private fun readModifier(buffer: Reader): ParticleModifier {
        val type = buffer.readUnsignedByte()
        val bytes = ArrayList<Int>()
        val integers = ArrayList<Int>()
        val floats = ArrayList<Float>()
        val shapes = ArrayList<ParticleShape>()
        val curves = ArrayList<ParticleCurve>()
        for (field in ParticleModifierLayout.of(type)) {
            when (field) {
                ParticleField.BYTE -> bytes.add(buffer.readUnsignedByte())
                ParticleField.INTEGER -> integers.add(buffer.readInt())
                ParticleField.FLOAT -> floats.add(buffer.readFloat())
                ParticleField.SHAPE -> shapes.add(readShape(buffer))
                else -> curves.add(readCurve(buffer, field.dimensions))
            }
        }
        return ParticleModifier(type, bytes, integers, floats, shapes, curves)
    }

    private fun readShape(buffer: Reader): ParticleShape {
        val kind = buffer.readUnsignedByte()
        val flag = buffer.readUnsignedByte()
        return ParticleShape(
            kind,
            flag,
            readFloats(buffer, 3),
            readFloats(buffer, 3),
            readFloats(buffer, particleShapeExtents(kind)),
        )
    }

    private fun readCurve(buffer: Reader, dimensions: Int): ParticleCurve {
        val count = buffer.readUnsignedByte()
        val keys = if (count == CONSTANT_CURVE) 1 else count * 2
        return ParticleCurve(count, List(keys) { ParticleCurveKey(buffer.readFloat(), readFloats(buffer, dimensions)) })
    }

    private fun readFloats(buffer: Reader, count: Int) = List(count) { buffer.readFloat() }

    companion object {
        const val INDEX = 61

        private const val VERSION = 8

        private const val CONSTANT_CURVE = 1
    }
}
