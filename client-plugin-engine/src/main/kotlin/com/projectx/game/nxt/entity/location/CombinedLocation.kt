package com.projectx.game.nxt.entity.location
import com.projectx.game.memory.atLeast

import com.projectx.game.nxt.extent
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.toShared
import com.projectx.game.nxt.OCombinedLocation
import com.projectx.game.nxt.entity.Entity
import com.projectx.game.nxt.types.Vector
import com.projectx.pathfinder.WorldCollision
import java.lang.foreign.MemorySegment

class CombinedLocation(raw: MemorySegment): Entity(raw) {
    val locationData: Vector
        get() = Vector(ptr.pointerAtOffset(OCombinedLocation.LOCATION_DATA_VECTOR, 0x20L), 0xC0L)

    val combinedLocationSections: List<CombinedLocationSection>
        get() = locationData.mapNotNull { basePtr ->
            val sectionPtr = basePtr.pointerAtOffset(OCombinedLocation.VECTOR_ELEMENT_LOC_SHAREDPTR, 0x20L)
            if (sectionPtr.deref(size = 0x24L).address() == 0L) return@mapNotNull null
            val section = CombinedLocationSection(sectionPtr.toShared().value(OCombinedLocation.extent))
            if (section.tile.x <= 0 || section.tile.y <= 0 || section.tile.plane < 0)
                return@mapNotNull null
            if (section.hidden) {
                WorldCollision.unclip(section)
                return@mapNotNull null
            } else
                WorldCollision.clip(section)
            return@mapNotNull section
        }
}