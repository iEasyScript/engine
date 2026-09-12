package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.MAP_SCENES
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.MapSceneType
import world.gregs.voidps.cache.type.encoder.MapSceneEncoder

class MapSceneDecoder : ConfigDecoder<MapSceneType>(MAP_SCENES) {

    override fun create(size: Int) = Array(size) { MapSceneType(it) }

    private val encoder = MapSceneEncoder()

    override fun canonicalOpcodes(definition: MapSceneType): IntArray = encoder.opcodes(definition)

    override fun MapSceneType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> graphic = buffer.readBigSmart()
            2 -> colour = buffer.readUnsignedMedium()
            3 -> unknown3 = true
            4 -> graphic = -1
            5 -> unknown5 = true
            else -> unknown(opcode, buffer)
        }
    }
}