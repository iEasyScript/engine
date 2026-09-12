package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.Index.TEXTURE_DEFINITIONS
import world.gregs.voidps.cache.type.data.MaterialType

/**
 * Decodes materials. The whole index is one group whose file ids are the material ids, so the
 * highest file id bounds the material list.
 */
class MaterialDecoder : TypeDecoder<MaterialType>(TEXTURE_DEFINITIONS) {
    override fun create(size: Int) = Array(size) { MaterialType(it) }

    override fun getArchive(id: Int) = MATERIALS

    override fun getFile(id: Int) = id

    override fun size(cache: Cache) = cache.lastFileId(index, MATERIALS)

    override fun readLoop(definition: MaterialType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun MaterialType.read(opcode: Int, buffer: Reader) = Unit

    private fun MaterialType.decode(buffer: Reader) {
        version = buffer.readUnsignedByte()
        if (version == 0) {
            readLayoutA(buffer)
        } else {
            readLayoutB(buffer)
        }
    }

    private fun MaterialType.readLayoutA(buffer: Reader) {
        textureSizeCode = buffer.readUnsignedByte()
        unknown1 = buffer.readUnsignedByte()
        flags = buffer.readInt()
        if (flags and 0x11 != 0) {
            unknown2 = buffer.readInt()
        }
        if (flags and 0xa != 0) {
            unknown3 = buffer.readInt()
        }
        packed = buffer.readUnsignedByte()
        extraFlags = buffer.readInt()
        if (extraFlags and 0x40000 != 0) {
            unknown4 = buffer.readInt()
        }
        if (extraFlags and 0x80000 != 0) {
            readScales(buffer)
        }
        if (extraFlags and 0x10 != 0) {
            unknown10 = buffer.readFloat()
            unknown11 = buffer.readFloat()
        }
        if (flags and 0x2 != 0) {
            unknown12 = buffer.readFloat()
        }
        unknown13 = buffer.readUnsignedByte()
        unknown14 = buffer.readUnsignedByte()
        unknown15 = buffer.readUnsignedByte()
        if (unknown15 == 1) {
            unknown16 = buffer.readUnsignedByte()
        }
        if (extraFlags and 0x800 != 0) {
            readOffsets(buffer)
        }
        unknown20 = buffer.readUnsignedByte()
        if (unknown20 and 0x1 != 0) {
            scrollU = buffer.readUnsignedShort()
        }
        if (unknown20 and 0x2 != 0) {
            scrollV = buffer.readUnsignedShort()
        }
        if (extraFlags and 0x800000 != 0) {
            unknown21 = buffer.readFloat()
        }
        unknown22 = buffer.readUnsignedByte()
        if (unknown22 != 1) {
            return
        }
        unknown23 = buffer.readByte()
        unknown24 = buffer.readByte()
        unknown25 = buffer.readInt()
        unknown26 = buffer.readUnsignedByte()
        unknown27 = buffer.readUnsignedByte()
        unknown28 = buffer.readUnsignedByte()
        unknown29 = buffer.readUnsignedByte()
        unknown30 = buffer.readUnsignedByte()
        unknown31 = buffer.readUnsignedByte()
        unknown32 = buffer.readUnsignedByte()
        colour = buffer.readUnsignedShort()
    }

    private fun MaterialType.readLayoutB(buffer: Reader) {
        flags = buffer.readInt()
        if (flags and 0x20 != 0) {
            unknown33 = buffer.readUnsignedByte()
            unknown34 = buffer.readInt()
        }
        if (flags and 0x40 != 0) {
            unknown35 = buffer.readUnsignedByte()
            unknown36 = buffer.readInt()
        }
        if (flags and 0x80 != 0) {
            unknown37 = buffer.readUnsignedByte()
            unknown38 = buffer.readInt()
        }
        if (flags and 0x40000 != 0) {
            unknown4 = buffer.readInt()
        }
        if (flags and 0x80000 != 0) {
            readScales(buffer)
        }
        if (flags and 0x1000 != 0) {
            unknown10 = buffer.readFloat()
        }
        if (flags and 0x2000 != 0) {
            unknown12 = buffer.readFloat()
        }
        if (flags and 0x4000 != 0) {
            unknown39 = buffer.readFloat()
        }
        if (flags and 0x8000 != 0) {
            unknown40 = buffer.readInt()
        }
        if (flags and 0x40 != 0) {
            unknown41 = buffer.readFloat()
        }
        if (flags and 0x800 != 0) {
            readOffsets(buffer)
        }
        if (flags and 0x10000 != 0) {
            unknown42 = buffer.readFloat()
        }
        if (flags and 0x20000 != 0) {
            unknown43 = buffer.readFloat()
        }
        if (flags and 0x400000 != 0) {
            unknown44 = buffer.readFloat()
        }
        if (flags and 0x100 != 0) {
            scrollU = buffer.readUnsignedShort()
        }
        if (flags and 0x200 != 0) {
            scrollV = buffer.readUnsignedShort()
        }
        packed = buffer.readUnsignedByte()
        unknown14 = buffer.readUnsignedByte()
        unknown45 = buffer.readUnsignedByte()
        unknown15 = buffer.readUnsignedByte()
        if (unknown15 == 1) {
            unknown16 = buffer.readUnsignedByte()
        }
        colour = buffer.readUnsignedShort()
        unknown46 = buffer.readUnsignedByte()
    }

    private fun MaterialType.readScales(buffer: Reader) {
        unknown5 = buffer.readInt()
        unknown6 = buffer.readFloat()
        unknown7 = buffer.readFloat()
        unknown8 = buffer.readFloat()
        unknown9 = buffer.readFloat()
    }

    private fun MaterialType.readOffsets(buffer: Reader) {
        unknown17 = buffer.readFloat()
        unknown18 = buffer.readFloat()
        unknown19 = buffer.readFloat()
    }

    private companion object {
        const val MATERIALS = 0
    }
}
