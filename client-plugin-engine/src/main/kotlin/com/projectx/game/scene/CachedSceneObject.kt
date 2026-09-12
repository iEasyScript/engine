package com.projectx.game.scene

import com.projectx.game.nxt.entity.location.SceneObject
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.LocType
import world.gregs.voidps.map.ObjectShape
import world.gregs.voidps.type.Tile
import java.lang.foreign.MemorySegment

class CachedSceneObject(
    override val memPointer: MemorySegment = MemorySegment.NULL,
    override val id: Int,
    override val typeId: Int,
    override val tile: Tile,
    override val shape: ObjectShape,
    override val rotation: Byte
) : SceneObject {
    override val defs: LocType
        get() = Cache.loc(typeId) ?: LocType.EMPTY
    override val exists: Boolean = true
    override val graphNode = null
}