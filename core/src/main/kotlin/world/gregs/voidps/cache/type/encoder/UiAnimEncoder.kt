package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.UiAnimCurveType
import world.gregs.voidps.cache.type.data.UiAnimType
import world.gregs.voidps.cache.type.decoder.UiAnimDecoder.Companion.CURVE_ID
import world.gregs.voidps.cache.type.decoder.UiAnimDecoder.Companion.CURVE_POINTER
import world.gregs.voidps.cache.type.decoder.uiAnimArity

class UiAnimCurveEncoder : TypeEncoder<UiAnimCurveType> {

    override fun Writer.encode(definition: UiAnimCurveType) {
        writeByte(definition.knots.size)
        for (knot in definition.knots) {
            writeFloat(knot.x)
            writeFloat(knot.y)
            writeFloat(knot.controlX)
            writeFloat(knot.controlY)
        }
    }
}

class UiAnimEncoder : TypeEncoder<UiAnimType> {

    override fun Writer.encode(definition: UiAnimType) {
        writeByte(definition.curveKind)
        when (definition.curveKind) {
            CURVE_POINTER -> writeInt(definition.curve!!)
            CURVE_ID -> {
                writeInt(definition.curve!!)
                writeByte(definition.reverse!!)
            }
            else -> return
        }
        val property = definition.property!!
        writeByte(property)
        writeByte(definition.valueSpace!!)
        val keys = definition.keys!!
        writeShort(keys.size / uiAnimArity(property))
        for (key in keys) {
            writeInt(key)
        }
    }
}
