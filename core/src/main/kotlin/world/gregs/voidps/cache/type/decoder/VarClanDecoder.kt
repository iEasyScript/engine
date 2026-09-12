package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.cache.Config.VAR_CLAN
import world.gregs.voidps.cache.type.data.VarClanType

class VarClanDecoder : VarDomainDecoder<VarClanType>(VAR_CLAN) {
    override fun create(size: Int) = Array(size) { VarClanType(it) }
}
