package com.projectx.game.nxt.entity
import com.projectx.game.memory.atLeast
import com.projectx.game.nxt.extent

import com.projectx.game.memory.NativeAccess.readByte
import com.projectx.game.nxt.EntityType
import com.projectx.game.nxt.OEntity
import java.lang.foreign.MemorySegment

class EntityTypeContainer(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OEntity.extent)
    val type: EntityType
        get() = EntityType.fromType(ptr.readByte(OEntity.ENTITY_TYPE).toInt())
}