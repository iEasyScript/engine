package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.ParticleField
import world.gregs.voidps.cache.type.ParticleModifierLayout
import world.gregs.voidps.cache.type.data.ParticleCurve
import world.gregs.voidps.cache.type.data.ParticleEmitter
import world.gregs.voidps.cache.type.data.ParticleModifier
import world.gregs.voidps.cache.type.data.ParticleShape
import world.gregs.voidps.cache.type.data.ParticleSystemType
import world.gregs.voidps.cache.type.writeVersionedRawString

class ParticleSystemEncoder : TypeEncoder<ParticleSystemType> {

    override fun Writer.encode(definition: ParticleSystemType) {
        writeByte(definition.version)
        writeVersionedRawString(definition.name)
        writeByte(definition.flags)
        writeByte(definition.emitters.size)
        for (emitter in definition.emitters) {
            writeEmitter(emitter)
        }
        skip(definition.padding)
    }

    private fun Writer.writeEmitter(emitter: ParticleEmitter) {
        writeVersionedRawString(emitter.name)
        writeByte(emitter.fieldA)
        writeByte(emitter.fieldB)
        writeByte(emitter.fieldC)
        writeByte(emitter.flags)
        writeShort(emitter.material)
        writeShort(emitter.particleCap)
        writeByte(emitter.uvColumns)
        writeByte(emitter.uvRows)
        writeFloats(emitter.vectorA)
        writeFloats(emitter.vectorB)
        writeFloat(emitter.fieldG)
        writeByte(emitter.fieldJ)
        writeFloat(emitter.fieldK)
        writeFloat(emitter.fieldL)
        writeFloat(emitter.fieldM)
        writeFloats(emitter.vectorC)
        writeFloats(emitter.vectorD)
        writeByte(emitter.modifiers.size)
        for (modifier in emitter.modifiers) {
            writeModifier(modifier)
        }
    }

    private fun Writer.writeModifier(modifier: ParticleModifier) {
        writeByte(modifier.type)
        var bytes = 0
        var integers = 0
        var floats = 0
        var shapes = 0
        var curves = 0
        for (field in ParticleModifierLayout.of(modifier.type)) {
            when (field) {
                ParticleField.BYTE -> writeByte(modifier.bytes[bytes++])
                ParticleField.INTEGER -> writeInt(modifier.integers[integers++])
                ParticleField.FLOAT -> writeFloat(modifier.floats[floats++])
                ParticleField.SHAPE -> writeShape(modifier.shapes[shapes++])
                else -> writeCurve(modifier.curves[curves++])
            }
        }
    }

    private fun Writer.writeShape(shape: ParticleShape) {
        writeByte(shape.kind)
        writeByte(shape.flag)
        writeFloats(shape.position)
        writeFloats(shape.rotation)
        writeFloats(shape.extents)
    }

    private fun Writer.writeCurve(curve: ParticleCurve) {
        writeByte(curve.count)
        for (key in curve.keys) {
            writeFloat(key.time)
            writeFloats(key.values)
        }
    }

    private fun Writer.writeFloats(values: List<Float>) {
        for (value in values) {
            writeFloat(value)
        }
    }
}
