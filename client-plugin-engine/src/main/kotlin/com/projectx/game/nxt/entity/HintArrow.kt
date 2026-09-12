package com.projectx.game.nxt.entity
import com.projectx.game.memory.atLeast
import com.projectx.game.nxt.extent

import world.gregs.voidps.type.Tile
import com.projectx.game.memory.NativeAccess.readByte
import com.projectx.game.memory.NativeAccess.readFloat
import com.projectx.game.memory.NativeAccess.toShared
import com.projectx.game.nxt.EntityType
import com.projectx.game.nxt.OEntity
import com.projectx.game.nxt.OHintArrow
import com.projectx.script.api.localPlayer
import java.lang.foreign.MemorySegment
import kotlin.math.roundToInt

class HintArrow(raw: MemorySegment) : Entity(raw) {
    private fun targetTileFromTargetPos(): Tile? {
        val x = runCatching { ptr.readFloat(OHintArrow.TARGET_POS_VEC3 + 0x0L) }.getOrNull() ?: return null
        val y = runCatching { ptr.readFloat(OHintArrow.TARGET_POS_VEC3 + 0x4L) }.getOrNull() ?: return null

        val tileX = ((x - 256f) / 512f).roundToInt()
        val tileY = ((y - 256f) / 512f).roundToInt()

        if (tileX == 0 || tileY == 0) return null

        val z = runCatching { plane }.getOrDefault(0)
        return Tile.of(tileX, tileY, z)
    }

    val targetEntityRaw: MemorySegment?
        get() = runCatching {
            ptr.asSlice(OHintArrow.TARGET_ENTITY_SHARED_PTR, 0x10L).toShared().valueOrNull(EntityFactory.extent)
        }.getOrNull()

    val targetType: EntityType?
        get() = runCatching {
            targetEntityRaw?.let { runCatching { EntityType.fromType(it.readByte(OEntity.ENTITY_TYPE).toInt()) }.getOrNull() }
        }.getOrNull()

    val targetEntity: Entity?
        get() = runCatching { targetEntityRaw?.let { EntityFactory.wrap(it) } }.getOrNull()

    /**
     * Null when this arrow has no readable target. It used to fall back to the local player's tile and
     * finally to (0,0), which turned a misread entity into a confident wrong answer instead of a miss.
     */
    val targetTile: Tile?
        get() = runCatching { targetTileFromTargetPos() }.getOrNull()
            ?: runCatching { targetEntity?.tile }.getOrNull()

    fun isValid(): Boolean {
        return runCatching {
            val type = EntityType.fromType(ptr.readByte(OEntity.ENTITY_TYPE).toInt())
            type == EntityType.HINT_ARROW || type == EntityType.HINT_ARROW_POINTER
        }.getOrDefault(false)
    }


    override val size get() = 0
}
