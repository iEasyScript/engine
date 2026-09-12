package org.projectx.core.game.invention

object Invention {
    const val MAX_ITEM_LEVEL = 20

    val BIS_WEAPON = listOf<GizmoSpec>(
        Perk.PRECISE.rank(6) + Perk.ERUPTIVE.rank(1),
        Perk.AFTERSHOCK.rank(4) + Perk.EQUILIBRIUM.rank(2),
    )
    val BIS_MAINHAND = listOf<GizmoSpec>(Perk.PRECISE.rank(6))
    val BIS_OFFHAND = listOf<GizmoSpec>(Perk.AFTERSHOCK.rank(4) + Perk.EQUILIBRIUM.rank(2))
    val BIS_BODY = listOf<GizmoSpec>(
        Perk.BITING.rank(4) + Perk.MOBILE.rank(1),
        Perk.CRACKLING.rank(4) + Perk.RELENTLESS.rank(1),
    )
    val BIS_LEGS = listOf<GizmoSpec>(
        Perk.IMPATIENT.rank(4) + Perk.DEVOTED.rank(4),
        Perk.ABSORBATIVE.rank(4) + Perk.VENOMBLOOD.rank(1),
    )

    private val ITEM_XP_TO_REACH = intArrayOf(
        0, 0, 1_160, 2_607, 5_176, 8_285, 11_760, 15_835, 21_152, 28_761, 40_120,
        57_095, 81_960, 117_397, 166_496, 232_755, 320_080, 432_785, 575_592, 753_631, 972_440,
    )

    fun xpForLevel(level: Int): Int = ITEM_XP_TO_REACH[level.coerceIn(1, MAX_ITEM_LEVEL)]

    fun levelForXp(xp: Int): Int {
        var level = 1
        for (l in 1..MAX_ITEM_LEVEL) if (xp >= ITEM_XP_TO_REACH[l]) level = l else break
        return level
    }

    val maxItemXp: Int get() = xpForLevel(MAX_ITEM_LEVEL)
}
