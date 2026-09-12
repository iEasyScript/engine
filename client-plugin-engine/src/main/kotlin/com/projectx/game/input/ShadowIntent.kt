package com.projectx.game.input

import com.projectx.game.nxt.DoActionOpcode

sealed interface ShadowIntent {
    val gameTick: Int
    val timestampNanos: Long
}

data class DoActionShadow(
    val opcode: DoActionOpcode,
    val param1: Int,
    val param2: Int,
    val param3: Int,
    override val gameTick: Int,
    override val timestampNanos: Long,
    /**
     * Screen position resolved at publish time (when the script is still on the
     * game thread, holding the lock, with the entity pointer fresh). Null if
     * resolution failed for this opcode/state. The consumer trusts this value;
     * it does NOT re-dereference entity pointers, because by the time the synth
     * tick reads the intent the NPC may have despawned and its memory freed.
     */
    val resolvedTargetX: Float? = null,
    val resolvedTargetY: Float? = null,
) : ShadowIntent
