package com.projectx.game.nxt.entity
import com.projectx.game.memory.atLeast
import com.projectx.game.nxt.extent

import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.nxt.OAnimation
import world.gregs.voidps.gameval.Gameval
import java.lang.foreign.MemorySegment

class Animation(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OAnimation.extent)
    val id
        get() = ptr.readInt(OAnimation.ID)
    val currentFrame
        get() = ptr.readInt(OAnimation.CURRENT_FRAME)

    override fun toString() = "[id=${Gameval.seqLabel(id)}, currentFrame=$currentFrame]"
}