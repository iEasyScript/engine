package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.cache.Config.VAR_PLAYER
import world.gregs.voidps.cache.type.data.VarPlayerType

class VarPlayerDecoder : VarDomainDecoder<VarPlayerType>(VAR_PLAYER) {
    override fun create(size: Int) = Array(size) { VarPlayerType(it) }
}
