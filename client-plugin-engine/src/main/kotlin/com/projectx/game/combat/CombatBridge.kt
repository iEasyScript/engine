package com.projectx.game.combat

import org.projectx.core.game.skill.Skill
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.script.api.equipment
import com.projectx.script.api.getCurrentLevel
import com.projectx.script.api.getRealLevel
import org.projectx.core.game.combat.ClockSource
import org.projectx.core.game.combat.CombatContext
import org.projectx.core.game.combat.VarReader

object CombatBridge {
    fun bind() {
        CombatContext.bind(
            CombatContext(
                varps = object : VarReader {
                    override fun getVar(id: Int) = Bootstrap.client.playerVarDomain.getVar(id)
                    override fun getVarBit(id: Int) = Bootstrap.client.playerVarDomain.getVarBit(id)
                },
                varcs = object : VarReader {
                    override fun getVar(id: Int) = Bootstrap.client.clientVarDomain.getVar(id)
                    override fun getVarBit(id: Int) = Bootstrap.client.clientVarDomain.getVarBit(id)
                },
                clock = ClockSource { Bootstrap.client.clientCycle },
                statLevel = { skill, real ->
                    val s = Skill.entries[skill]
                    if (real) getRealLevel(s) else getCurrentLevel(s)
                },
                wornParam = { slot, paramId -> (equipment[slot]?.getDef()?.params?.get(paramId) as? Int) ?: -1 },
                wornObjVar = { slot, objVarId -> equipment[slot]?.varDomain?.getVar(objVarId) ?: 0 },
            )
        )
    }
}
