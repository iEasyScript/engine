package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.cache.Config.VAR_CLAN_SETTINGS
import world.gregs.voidps.cache.type.data.VarClanSettingType

class VarClanSettingDecoder : VarDomainDecoder<VarClanSettingType>(VAR_CLAN_SETTINGS) {
    override fun create(size: Int) = Array(size) { VarClanSettingType(it) }
}
