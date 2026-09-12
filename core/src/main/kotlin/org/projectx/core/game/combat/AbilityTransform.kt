package org.projectx.core.game.combat

import org.projectx.core.game.combat.CombatIds as Id

object AbilityTransform {
    fun apply(structId: Int, varps: VarReader): Int = when (structId) {
        Id.CONJURE_SKELETON ->
            if (varps.getVar(Id.CONJURE_SKELETON_ACTIVE) == 1 && varps.getVarBit(Id.COMMAND_SKELETON_UNLOCKED) == 1) Id.COMMAND_SKELETON else structId
        Id.CONJURE_PUTRID ->
            if (varps.getVar(Id.CONJURE_PUTRID_ACTIVE) == 1 && varps.getVarBit(Id.COMMAND_PUTRID_UNLOCKED) == 1) Id.COMMAND_PUTRID else structId
        Id.CONJURE_VENGEFUL ->
            if (varps.getVar(Id.CONJURE_VENGEFUL_ACTIVE) == 1 && varps.getVarBit(Id.COMMAND_VENGEFUL_UNLOCKED) == 1) Id.COMMAND_VENGEFUL else structId
        Id.CONJURE_PHANTOM ->
            if (varps.getVar(Id.CONJURE_PHANTOM_ACTIVE) == 1 && varps.getVarBit(Id.COMMAND_PHANTOM_UNLOCKED) == 1) Id.COMMAND_PHANTOM else structId
        Id.SPECTRAL_SCYTHE, Id.SPECTRAL_SCYTHE_RECAST_1, Id.SPECTRAL_SCYTHE_RECAST_2 -> when {
            varps.getVar(Id.SPECTRAL_SCYTHE_RECAST_1_ACTIVE) == 1 -> Id.SPECTRAL_SCYTHE_RECAST_1
            varps.getVar(Id.SPECTRAL_SCYTHE_RECAST_2_ACTIVE) == 1 -> Id.SPECTRAL_SCYTHE_RECAST_2
            else -> Id.SPECTRAL_SCYTHE
        }
        else -> structId
    }
}
