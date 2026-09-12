package com.projectx.game.nxt.entity
import com.projectx.game.memory.atLeast

import com.projectx.game.nxt.extent
import com.projectx.game.nxt.OHitmarksAndHeadbars
import com.projectx.game.nxt.ORouteWaypointManager
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.getOrNull
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.NativeAccess.readLong
import com.projectx.game.memory.eastl.EastlString
import com.projectx.game.nxt.OPathingEntity
import java.lang.foreign.MemorySegment

abstract class PathingEntity(raw: MemorySegment) : Entity(raw) {
    val name: String
        get() = EastlString(ptr.pointerAtOffset(OPathingEntity.NAME, 0x24)).toString()

    val serverIndex
        get() = ptr.readInt(OPathingEntity.SERVER_INDEX)

    val routeWaypointManager
        get() = ptr.deref(OPathingEntity.ROUTE_WAYPOINT_MANAGER, ORouteWaypointManager.extent)

    val isMoving: Boolean
        get() = routeWaypointManager.readLong(OPathingEntity.WAYPOINT_COUNT) > 0

    val isInteracting
        get() = interactionSid != -1

    val isAniMoving: Boolean
        get() = isAnimating || isMoving

    val interactionSid
        get() = ptr.readInt(OPathingEntity.INTERACTING_NPC_SID)

    val hitmarksAndHeadbars
        get() = ptr.deref(OPathingEntity.HITMARKS_AND_HEADBARS, OHitmarksAndHeadbars.extent).getOrNull?.let { HitmarksAndHeadbars(it) }

    val headbars
        get() = hitmarksAndHeadbars?.headbars ?: emptyList()

    val hits
        get() = hitmarksAndHeadbars?.hits?.filter { it.typeId > 0 && it.timeLeftMillis > 0 } ?: emptyList()

    fun interactingWith(target: PathingEntity) = target.serverIndex == interactionSid
}