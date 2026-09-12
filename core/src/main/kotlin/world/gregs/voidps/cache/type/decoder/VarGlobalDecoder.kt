package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.cache.Config.VAR_GLOBAL
import world.gregs.voidps.cache.type.data.VarGlobalType

class VarGlobalDecoder : VarDomainDecoder<VarGlobalType>(VAR_GLOBAL) {
    override fun create(size: Int) = Array(size) { VarGlobalType(it) }
}
