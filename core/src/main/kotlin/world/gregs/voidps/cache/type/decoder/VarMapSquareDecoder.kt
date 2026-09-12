package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.cache.Config.VAR_MAP_SQUARE
import world.gregs.voidps.cache.type.data.VarMapSquareType

class VarMapSquareDecoder : VarDomainDecoder<VarMapSquareType>(VAR_MAP_SQUARE) {
    override fun create(size: Int) = Array(size) { VarMapSquareType(it) }
}
