package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.cache.Config.VAR_OBJECT
import world.gregs.voidps.cache.type.data.VarObjectType

class VarObjectDecoder : VarDomainDecoder<VarObjectType>(VAR_OBJECT) {
    override fun create(size: Int) = Array(size) { VarObjectType(it) }
}
