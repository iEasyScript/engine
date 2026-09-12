package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.MaterialType

class MaterialEncoder : TypeEncoder<MaterialType> {

    override fun Writer.encode(definition: MaterialType) {
        writeByte(definition.version)
        if (definition.version == 0) {
            writeLayoutA(definition)
        } else {
            writeLayoutB(definition)
        }
    }

    private fun Writer.writeLayoutA(definition: MaterialType) {
        writeByte(definition.textureSizeCode)
        writeByte(definition.unknown1)
        writeInt(definition.flags)
        if (definition.flags and 0x11 != 0) {
            writeInt(definition.unknown2)
        }
        if (definition.flags and 0xa != 0) {
            writeInt(definition.unknown3)
        }
        writeByte(definition.packed)
        writeInt(definition.extraFlags)
        if (definition.extraFlags and 0x40000 != 0) {
            writeInt(definition.unknown4)
        }
        if (definition.extraFlags and 0x80000 != 0) {
            writeScales(definition)
        }
        if (definition.extraFlags and 0x10 != 0) {
            writeFloat(definition.unknown10)
            writeFloat(definition.unknown11)
        }
        if (definition.flags and 0x2 != 0) {
            writeFloat(definition.unknown12)
        }
        writeByte(definition.unknown13)
        writeByte(definition.unknown14)
        writeByte(definition.unknown15)
        if (definition.unknown15 == 1) {
            writeByte(definition.unknown16)
        }
        if (definition.extraFlags and 0x800 != 0) {
            writeOffsets(definition)
        }
        writeByte(definition.unknown20)
        if (definition.unknown20 and 0x1 != 0) {
            writeShort(definition.scrollU)
        }
        if (definition.unknown20 and 0x2 != 0) {
            writeShort(definition.scrollV)
        }
        if (definition.extraFlags and 0x800000 != 0) {
            writeFloat(definition.unknown21)
        }
        writeByte(definition.unknown22)
        if (definition.unknown22 != 1) {
            return
        }
        writeByte(definition.unknown23)
        writeByte(definition.unknown24)
        writeInt(definition.unknown25)
        writeByte(definition.unknown26)
        writeByte(definition.unknown27)
        writeByte(definition.unknown28)
        writeByte(definition.unknown29)
        writeByte(definition.unknown30)
        writeByte(definition.unknown31)
        writeByte(definition.unknown32)
        writeShort(definition.colour)
    }

    private fun Writer.writeLayoutB(definition: MaterialType) {
        writeInt(definition.flags)
        if (definition.flags and 0x20 != 0) {
            writeByte(definition.unknown33)
            writeInt(definition.unknown34)
        }
        if (definition.flags and 0x40 != 0) {
            writeByte(definition.unknown35)
            writeInt(definition.unknown36)
        }
        if (definition.flags and 0x80 != 0) {
            writeByte(definition.unknown37)
            writeInt(definition.unknown38)
        }
        if (definition.flags and 0x40000 != 0) {
            writeInt(definition.unknown4)
        }
        if (definition.flags and 0x80000 != 0) {
            writeScales(definition)
        }
        if (definition.flags and 0x1000 != 0) {
            writeFloat(definition.unknown10)
        }
        if (definition.flags and 0x2000 != 0) {
            writeFloat(definition.unknown12)
        }
        if (definition.flags and 0x4000 != 0) {
            writeFloat(definition.unknown39)
        }
        if (definition.flags and 0x8000 != 0) {
            writeInt(definition.unknown40)
        }
        if (definition.flags and 0x40 != 0) {
            writeFloat(definition.unknown41)
        }
        if (definition.flags and 0x800 != 0) {
            writeOffsets(definition)
        }
        if (definition.flags and 0x10000 != 0) {
            writeFloat(definition.unknown42)
        }
        if (definition.flags and 0x20000 != 0) {
            writeFloat(definition.unknown43)
        }
        if (definition.flags and 0x400000 != 0) {
            writeFloat(definition.unknown44)
        }
        if (definition.flags and 0x100 != 0) {
            writeShort(definition.scrollU)
        }
        if (definition.flags and 0x200 != 0) {
            writeShort(definition.scrollV)
        }
        writeByte(definition.packed)
        writeByte(definition.unknown14)
        writeByte(definition.unknown45)
        writeByte(definition.unknown15)
        if (definition.unknown15 == 1) {
            writeByte(definition.unknown16)
        }
        writeShort(definition.colour)
        writeByte(definition.unknown46)
    }

    private fun Writer.writeScales(definition: MaterialType) {
        writeInt(definition.unknown5)
        writeFloat(definition.unknown6)
        writeFloat(definition.unknown7)
        writeFloat(definition.unknown8)
        writeFloat(definition.unknown9)
    }

    private fun Writer.writeOffsets(definition: MaterialType) {
        writeFloat(definition.unknown17)
        writeFloat(definition.unknown18)
        writeFloat(definition.unknown19)
    }
}
