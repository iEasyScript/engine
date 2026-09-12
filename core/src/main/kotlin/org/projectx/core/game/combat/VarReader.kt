package org.projectx.core.game.combat

interface VarReader {
    fun getVar(id: Int): Int
    fun getVarBit(id: Int): Int

    /** Full width of a var, for the 64-bit ones [getVar] can only return the low word of. */
    fun getVarLong(id: Int): Long = getVar(id).toLong()
}

object EmptyVarReader : VarReader {
    override fun getVar(id: Int) = 0
    override fun getVarBit(id: Int) = 0
}

fun interface ClockSource {
    fun now(): Int
}

class CombatContext(
    val varps: VarReader = EmptyVarReader,
    val varcs: VarReader = EmptyVarReader,
    val clock: ClockSource = ClockSource { 0 },
    val statLevel: (skill: Int, real: Boolean) -> Int = { _, _ -> 0 },
    val wornParam: (slot: Int, paramId: Int) -> Int = { _, _ -> -1 },
    val wornObjVar: (slot: Int, objVarId: Int) -> Int = { _, _ -> 0 },
) {
    companion object {
        @Volatile
        var current: CombatContext = CombatContext()
            private set

        fun bind(ctx: CombatContext) {
            current = ctx
        }
    }
}
