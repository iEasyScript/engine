package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.cache.Config.VAR_GROUP
import world.gregs.voidps.cache.type.data.VarGroupType

class VarGroupDecoder : VarDomainDecoder<VarGroupType>(VAR_GROUP) {
    override fun create(size: Int) = Array(size) { VarGroupType(it) }
}
