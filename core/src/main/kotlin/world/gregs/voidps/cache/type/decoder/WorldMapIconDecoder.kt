package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.Index.WORLD_MAP
import world.gregs.voidps.cache.type.data.WorldMapIcon
import world.gregs.voidps.cache.type.data.WorldMapIconType

class WorldMapIconDecoder : TypeDecoder<WorldMapIconType>(WORLD_MAP) {
    private var archive = -1
    private var unknownIconFilter = false

    override fun getArchive(id: Int) = archive

    override fun size(cache: Cache): Int {
        return cache.lastFileId(index, archive)
    }

    override fun create(size: Int) = Array(size) { WorldMapIconType(it) }

    override fun load(definitions: Array<WorldMapIconType>, cache: Cache, id: Int) {
        val archive = getArchive(id)
        var length = cache.fileCount(index, archive)
        var counter = 0
        var index = 0
        if (length > 0) {
            val definition = definitions[id]
            val icons = mutableListOf<WorldMapIcon>()
            while (length > counter) {
                val data = cache.data(this.index, archive, index++) ?: continue
                val buffer = BufferReader(data)
                recordDecode(id, buffer) {
                    val position = buffer.readInt()
                    val iconId = buffer.readShort()
                    val skip = buffer.readUnsignedByte()
                    if (unknownIconFilter && skip == 1) {
                        length--
                    } else {
                        counter++
                        icons.add(WorldMapIcon(iconId, position))
                    }
                }
            }
            definition.icons = icons.toTypedArray()
        }
    }

    override fun WorldMapIconType.read(opcode: Int, buffer: Reader) = Unit
}