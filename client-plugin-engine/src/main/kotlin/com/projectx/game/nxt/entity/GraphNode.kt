package com.projectx.game.nxt.entity
import com.projectx.game.memory.atLeast

import com.projectx.game.nxt.extent
import com.projectx.game.math.Vector3f
import com.projectx.game.math.Vector3i
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.getOrNull
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readByte
import com.projectx.game.memory.NativeAccess.readFloat
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.nxt.EntityType
import com.projectx.game.nxt.MapSquare
import com.projectx.game.nxt.OEntity
import com.projectx.game.nxt.OGraphNode
import com.projectx.game.nxt.entity.location.CombinedLocation
import com.projectx.game.nxt.entity.location.CombinedLocationSection
import com.projectx.game.nxt.entity.location.Location
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.game.nxt.entity.player.Player
import com.projectx.game.nxt.types.Vector
import java.lang.foreign.MemorySegment

class GraphNode(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OGraphNode.extent)

    val direction: Vector3f
        get() = Vector3f(ptr.readFloat(OGraphNode.DIRECTION_X), ptr.readFloat(OGraphNode.DIRECTION_Y), ptr.readFloat(OGraphNode.DIRECTION_Z))

    val scene: Vector3f
        get() = Vector3f(ptr.readFloat(OGraphNode.SCENE_X), ptr.readFloat(OGraphNode.SCENE_Y), ptr.readFloat(OGraphNode.SCENE_Z))

    val tile: Vector3i
        get() = Vector3i((ptr.readFloat(OGraphNode.SCENE_X) / 512f).toInt(), (ptr.readFloat(OGraphNode.SCENE_Y) / 512f).toInt(), (ptr.readFloat(OGraphNode.SCENE_Z) / 1024f).toInt())

    val tileFine: Vector3f
        get() = Vector3f(ptr.readFloat(OGraphNode.SCENE_X), ptr.readFloat(OGraphNode.SCENE_Y), ptr.readFloat(OGraphNode.SCENE_Z))

    /** Current transformed AABB minimum corner. */
    val boundsMin: Vector3f
        get() = Vector3f(ptr.readFloat(OGraphNode.BOUNDS_MIN_X), ptr.readFloat(OGraphNode.BOUNDS_MIN_Y), ptr.readFloat(OGraphNode.BOUNDS_MIN_Z))

    /** Current transformed AABB maximum corner. */
    val boundsMax: Vector3f
        get() = Vector3f(ptr.readFloat(OGraphNode.BOUNDS_MAX_X), ptr.readFloat(OGraphNode.BOUNDS_MAX_Y), ptr.readFloat(OGraphNode.BOUNDS_MAX_Z))

    /** Source/untransformed AABB minimum corner. */
    val srcBoundsMin: Vector3f
        get() = Vector3f(ptr.readFloat(OGraphNode.SRC_BOUNDS_MIN_X), ptr.readFloat(OGraphNode.SRC_BOUNDS_MIN_Y), ptr.readFloat(OGraphNode.SRC_BOUNDS_MIN_Z))

    /** Source/untransformed AABB maximum corner. */
    val srcBoundsMax: Vector3f
        get() = Vector3f(ptr.readFloat(OGraphNode.SRC_BOUNDS_MAX_X), ptr.readFloat(OGraphNode.SRC_BOUNDS_MAX_Y), ptr.readFloat(OGraphNode.SRC_BOUNDS_MAX_Z))

    /** GraphNode flags. Bit 0x08 = has bounding box. */
    val flags: Int
        get() = ptr.readInt(OGraphNode.FLAGS)

    val hasBounds: Boolean
        get() = (flags and 0x08) != 0

    val frameCount
        get() = ptr.readInt(OGraphNode.FRAME_COUNT)

    val entity: Entity?
        get() {
            val entity = ptr.deref(OGraphNode.ENTITY, EntityFactory.extent).getOrNull ?: return null
            return EntityFactory.wrap(entity)
        }

    val childEntities: List<Entity>
        get() = children.mapNotNull {
            if (it.address() == 0L) return@mapNotNull null
            val derefed = it.deref(size = OGraphNode.extent).getOrNull ?: return@mapNotNull null
            return@mapNotNull GraphNode(derefed).entity
        }

    val sceneObjects: List<SceneObject>
        get() = childEntities.flatMap { entity ->
            when (entity) {
                // combinedLocationSections (NOT the raw graph-node recursion) drops hidden/replaced
                // sections at the source — e.g. a chopped tree's hidden section under its stump.
                is CombinedLocation -> entity.combinedLocationSections.filterNot { it.isTransformHidden }
                is CombinedLocationSection ->
                    if (entity.hidden || entity.isTransformHidden) emptyList() else listOf(entity)
                is Location ->
                    if (entity.isHidden || entity.isDeleted || entity.isTransformHidden) emptyList() else listOf(entity)
                else -> emptyList()
            }
        }

    val itemStacks: List<ItemStack>
        get() = childEntities.filter { it is ItemStack }.map { it as ItemStack }

    val spotAnims: List<SpotAnim>
        get() = childEntities.filter { it is SpotAnim }.map { it as SpotAnim }

    val children : Vector
        get() = Vector(ptr.pointerAtOffset(OGraphNode.CHILDREN, 0x20L))
}