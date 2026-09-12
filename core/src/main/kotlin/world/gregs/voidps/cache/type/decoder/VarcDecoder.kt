package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.cache.Config.VAR_CLIENT
import world.gregs.voidps.cache.type.data.VarcType

class VarcDecoder : VarDomainDecoder<VarcType>(VAR_CLIENT) {
    override fun create(size: Int) = Array(size) { VarcType(it) }
}
