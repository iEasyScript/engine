package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index.SPOTANIMS
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.WorldMapInfoType

class WorldMapInfoDecoder : TypeDecoder<WorldMapInfoType>(SPOTANIMS) {

    override fun create(size: Int) = Array(size) { WorldMapInfoType(it) }

    override fun getArchive(id: Int) = MAP_ELEMENTS_ARCHIVE

    override fun getFile(id: Int) = id

    override fun size(cache: Cache) = cache.lastFileId(index, MAP_ELEMENTS_ARCHIVE)

    override fun WorldMapInfoType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> graphicId = buffer.readBigSmart()
            2 -> highlightGraphicId = buffer.readBigSmart()
            4 -> scaleX = buffer.readUnsignedShort()
            5 -> scaleY = buffer.readUnsignedShort()
            6 -> rotation = buffer.readUnsignedShort()
            7 -> textOffset = buffer.readByte() + 0x40
            8 -> buffer.skip(1)
            9 -> {
                conditionType = 3
                conditionValue = 0x2020
            }
            10 -> alwaysShow = true
            15 -> {
                conditionType = 3
                conditionValue = buffer.readUnsignedShort()
            }
            16 -> {
                conditionType = 3
                conditionValue = buffer.readInt()
            }
            40, 41 -> {
                val count = buffer.readUnsignedByte()
                repeat(count) {
                    buffer.readUnsignedShort()
                    buffer.readUnsignedShort()
                }
            }
            44, 45 -> buffer.skip(2)
            46 -> { }
            else -> unknown(opcode, buffer)
        }
    }

    private companion object {
        const val MAP_ELEMENTS_ARCHIVE = 13
    }
}
