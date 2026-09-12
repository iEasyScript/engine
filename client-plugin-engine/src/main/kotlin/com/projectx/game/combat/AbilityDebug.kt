package com.projectx.game.combat

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.script.event.impl.Varc
import com.projectx.util.format
import org.projectx.core.game.combat.AbilityCooldownVarcs
import org.projectx.core.game.combat.AbilityRegistry

object AbilityDebug {
    private val UTILITY_CS2_VARCS = setOf(2092, 3736, 3733, 4098, 1)

    fun debugCooldownVarc(varc: Varc) {
        val cycle = Bootstrap.client.clientCycle
        if (varc.newValue <= cycle + 10) return
        if (varc.newValue >= cycle + 16000) return
        if (varc.id in UTILITY_CS2_VARCS) return
        val structId = AbilityCooldownVarcs.structByEndVarc[varc.id]
            ?: return println("Possible unidentified cooldown varc: ${varc.id}")
        val ability = AbilityRegistry[structId] ?: return
        val ticks = ability.cooldownTicks()
        println("[AbilityCooldown]: ${ability.name} -> ${format(ticks)} ticks (${format(ticks * 600.0 / 1000.0)} secs)")
    }
}
