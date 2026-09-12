package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.cache.Config.VAR_NPC
import world.gregs.voidps.cache.type.data.VarNpcType

class VarNpcDecoder : VarDomainDecoder<VarNpcType>(VAR_NPC) {
    override fun create(size: Int) = Array(size) { VarNpcType(it) }
}
