package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.cache.Config.VAR_WORLD
import world.gregs.voidps.cache.type.data.VarWorldType

class VarWorldDecoder : VarDomainDecoder<VarWorldType>(VAR_WORLD) {
    override fun create(size: Int) = Array(size) { VarWorldType(it) }
}
