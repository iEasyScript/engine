package com.projectx.game.nxt.entity

import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.nxt.OProjectile
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.game.nxt.entity.player.Player
import java.lang.foreign.MemorySegment

private const val TARGET_NPC = 1
private const val TARGET_PLAYER = 2

class Projectile(raw: MemorySegment) : Entity(raw) {
    override val size
        get() = 0

    val id
        get() = ptr.readInt(OProjectile.ID)

    private val lockOnTarget
        get() = ptr.readInt(OProjectile.LOCKON_SERVER_INDEX)

    val lockedToKind
        get() = (lockOnTarget ushr 16) and 0xff

    val lockedToServerIndex: Int
        get() {
            val target = lockOnTarget
            val kind = (target ushr 16) and 0xff
            return if (kind == TARGET_NPC || kind == TARGET_PLAYER) target and 0xffff else -1
        }

    fun lockedOnto(pathingEntity: PathingEntity): Boolean {
        val kind = when (pathingEntity) {
            is NPC -> TARGET_NPC
            is Player -> TARGET_PLAYER
            else -> return false
        }
        val target = lockOnTarget
        return (target ushr 16) and 0xff == kind && target and 0xffff == pathingEntity.serverIndex
    }
}
