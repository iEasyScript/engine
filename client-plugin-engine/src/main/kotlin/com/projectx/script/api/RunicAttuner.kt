package com.projectx.script.api

import org.projectx.core.game.combat.Effect
import com.projectx.script.api.varps

val runicAttunerStacks get() = Effect.RUNIC_ATTUNER.stacks
val attunedAltar get() = Altar.fromId(varps.getVarBit(55991))
val attunerTeleportAvailable get() = varps.getVarBit(55993) == 1
val soulAltarEss get() = varps.getVarBit(36003)
val soulAltarCharges get() = varps.getVarBit(36004)

enum class Altar(val id: Int, val displayName: String) {
    AIR(0, "Air"),
    MIND(1, "Mind"),
    SPIRIT(2, "Spirit"),
    WATER(3, "Water"),
    EARTH(4, "Earth"),
    FIRE(5, "Fire"),
    BODY(6, "Body"),
    BONE(7, "Bone"),
    COSMIC(8, "Cosmic"),
    CHAOS(9, "Chaos"),
    ASTRAL(10, "Astral"),
    FLESH(11, "Flesh"),
    NATURE(12, "Nature"),
    LAW(13, "Law"),
    MIASMA(14, "Miasma"),
    DEATH(15, "Death"),
    BLOOD(16, "Blood"),
    SOUL(17, "Soul"),
    TIME(18, "Time"),
    ALL_ALTARS(19, "All"),
    ;

    companion object {
        private val map = entries.associateBy(Altar::id)

        @JvmStatic
        fun fromId(id: Int): Altar? = map[id]
    }
}