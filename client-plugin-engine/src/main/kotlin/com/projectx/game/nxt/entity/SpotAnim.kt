package com.projectx.game.nxt.entity
import com.projectx.game.memory.atLeast
import com.projectx.game.nxt.extent

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.nxt.OSpotAnim
import java.lang.foreign.MemorySegment

class SpotAnim(raw: MemorySegment) : Entity(raw) {
    override val size
        get() = 0

    val id
        get() = ptr.readInt(OSpotAnim.ID)
    val createdClientcycle
        get() = ptr.readInt(OSpotAnim.CREATED_CLIENTCYCLE)
    val cyclesAlive
        get() = Bootstrap.client.clientCycle - createdClientcycle
    val timeAliveMillis
        get() = cyclesAlive * 20L
}