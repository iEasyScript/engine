package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.cache.Config.VAR_CONTROLLER
import world.gregs.voidps.cache.type.data.VarControllerType

class VarControllerDecoder : VarDomainDecoder<VarControllerType>(VAR_CONTROLLER) {
    override fun create(size: Int) = Array(size) { VarControllerType(it) }
}
