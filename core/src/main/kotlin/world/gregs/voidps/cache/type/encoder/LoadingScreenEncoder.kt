package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.LoadingScreenElement
import world.gregs.voidps.cache.type.data.LoadingScreenType

class LoadingScreenEncoder : TypeEncoder<LoadingScreenType> {

    override fun Writer.encode(definition: LoadingScreenType) {
        if (definition.id == LoadingScreenType.MASTER) {
            writeByte(definition.format)
            val types = definition.renderTypes ?: IntArray(0)
            writeByte(types.size)
            for (value in types) {
                writeByte(value)
            }
            writeByte(definition.unknown1)
            writeByte(definition.unknown2)
            writeShort(definition.unknown3)
            writeInt(definition.unknown4)
            writeInt(definition.unknown5)
            writeByte(definition.unknown6)
            writeByte(definition.unknown7)
            writeByte(definition.unknown8)
            writeByte(definition.unknown9)
            writeShort(definition.unknown10)
            writeShort(definition.unknown11)
            writeByte(definition.unknown12)
            writeByte(definition.unknown13)
            writeMedium(definition.unknown14)
            writeShort(definition.unknown15)
            return
        }
        val elements = definition.elements ?: emptyArray()
        writeByte(elements.size)
        for (element in elements) {
            writeElement(element, definition.id)
        }
    }

    private fun Writer.writeElement(element: LoadingScreenElement, id: Int) {
        writeByte(element.type)
        when (element.type) {
            5, 6 -> {
                writeShort(element.graphic!!)
                writeCommon(element)
                writeByte(element.alpha!!)
                writeBytes(element)
            }
            7 -> {
                writeString(element.text)
                writeCommon(element)
                writeBytes(element)
            }
            8 -> writeShort(element.graphic!!)
            10 -> {
                writeCommon(element)
                for (value in element.ints!!) {
                    writeInt(value)
                }
            }
            else -> error("Unhandled loading screen element ${element.type} in $id")
        }
    }

    private fun Writer.writeCommon(element: LoadingScreenElement) {
        writeByte(element.unknownA!!)
        writeShort(element.unknownB!!)
        writeByte(element.stage!!)
        writeByte(element.unknownC!!)
    }

    private fun Writer.writeBytes(element: LoadingScreenElement) {
        for (value in element.bytes ?: return) {
            writeByte(value)
        }
    }
}
