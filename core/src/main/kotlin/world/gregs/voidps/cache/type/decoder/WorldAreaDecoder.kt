package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.WorldAreaCoord
import world.gregs.voidps.cache.type.data.WorldAreaPoint
import world.gregs.voidps.cache.type.data.WorldAreaRect
import world.gregs.voidps.cache.type.data.WorldAreaType
import world.gregs.voidps.cache.type.encoder.WorldAreaEncoder

class WorldAreaDecoder : ConfigDecoder<WorldAreaType>(Config.WORLD_AREAS) {

    override fun create(size: Int) = Array(size) { WorldAreaType(it) }

    private val encoder = WorldAreaEncoder()

    override fun canonicalOpcodes(definition: WorldAreaType): IntArray = encoder.opcodes(definition)

    override fun WorldAreaType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            2 -> value = buffer.readUnsignedMedium()
            3 -> {
                val corner1 = WorldAreaCoord.decode(buffer.readInt())
                val corner2 = WorldAreaCoord.decode(buffer.readInt())
                val rects = rects ?: ArrayList<WorldAreaRect>().also { rects = it }
                rects.add(WorldAreaRect(corner1, corner2))
            }
            4 -> {
                val coord = WorldAreaCoord.decode(buffer.readInt())
                val value = buffer.readInt()
                val points = points ?: ArrayList<WorldAreaPoint>().also { points = it }
                points.add(WorldAreaPoint(coord, value))
            }
            else -> unknown(opcode, buffer)
        }
    }
}
