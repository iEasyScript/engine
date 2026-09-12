package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.UiAnimCurveType
import world.gregs.voidps.cache.type.data.UiAnimKnot
import world.gregs.voidps.cache.type.data.UiAnimType

/** How many 32-bit words one keyframe of [property] occupies; the client derives it, the file never carries it. */
fun uiAnimArity(property: Int): Int = when (property) {
    6 -> 3
    0, 3 -> 2
    else -> 1
}

class UiAnimCurveDecoder : TypeDecoder<UiAnimCurveType>(UiAnimDecoder.INDEX) {

    override fun getArchive(id: Int) = UiAnimDecoder.CURVE_ARCHIVE

    override fun create(size: Int) = Array(size) { UiAnimCurveType(it) }

    override fun readLoop(definition: UiAnimCurveType, buffer: Reader) {
        recordDecode(definition.id, buffer) {
            definition.knots = List(buffer.readUnsignedByte()) {
                UiAnimKnot(buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat())
            }
        }
    }

    override fun UiAnimCurveType.read(opcode: Int, buffer: Reader) = Unit
}

class UiAnimDecoder : TypeDecoder<UiAnimType>(INDEX) {

    override fun getArchive(id: Int) = ANIM_ARCHIVE

    override fun create(size: Int) = Array(size) { UiAnimType(it) }

    override fun readLoop(definition: UiAnimType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun UiAnimType.read(opcode: Int, buffer: Reader) = Unit

    private fun UiAnimType.decode(buffer: Reader) {
        curveKind = buffer.readUnsignedByte()
        when (curveKind) {
            CURVE_POINTER -> curve = buffer.readInt()
            CURVE_ID -> {
                curve = buffer.readInt()
                reverse = buffer.readUnsignedByte()
            }
            else -> return
        }
        val property = buffer.readUnsignedByte()
        this.property = property
        valueSpace = buffer.readUnsignedByte()
        keys = List(buffer.readUnsignedShort() * uiAnimArity(property)) { buffer.readInt() }
    }

    companion object {
        const val INDEX = 65

        const val CURVE_ARCHIVE = 0

        const val ANIM_ARCHIVE = 1

        const val CURVE_POINTER = 1

        const val CURVE_ID = 2
    }
}
