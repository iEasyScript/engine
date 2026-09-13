package com.projectx.game.nxt.entity.location
import com.projectx.game.memory.atLeast

import com.projectx.game.nxt.extent
import com.projectx.game.nxt.OLocationType
import world.gregs.voidps.type.Tile
import world.gregs.voidps.map.ObjectShape
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readByte
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.NativeAccess.toShared
import com.projectx.game.nxt.OCombinedLocationSection
import com.projectx.game.nxt.entity.Entity
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.LocType
import java.lang.foreign.MemorySegment

class CombinedLocationSection(raw: MemorySegment) : Entity(raw), SceneObject {
    override val memPointer = ptr
    override val typeId
        get() = id

    override val shape = ObjectShape.forId(ptr.readByte(OCombinedLocationSection.SHAPE).toInt())
    override val rotation
        get() = ptr.readByte(OCombinedLocationSection.ROTATION)

    override val tile: Tile
        get() = Tile.of(ptr.readInt(OCombinedLocationSection.POS_X), ptr.readInt(OCombinedLocationSection.POS_Y), plane)

    override val tileX: Int
        get() = tile.x
    override val tileY: Int
        get() = tile.y
    // The entity's own plane: SceneObject's default reads it back from tile, which is built from this.
    override val plane: Int
        get() = super<Entity>.plane
    override val centerX: Double
        get() = super<SceneObject>.centerX
    override val centerY: Double
        get() = super<SceneObject>.centerY

    override fun distanceTo(x: Double, y: Double) = super<SceneObject>.distanceTo(x, y)

    override val id: Int
        get() = realType?.id ?: 0

    override val exists: Boolean
        get() = Bootstrap.client.sceneManager.getLocationContainer(tile)?.allSceneObjects?.any { it.tile == tile && it.id == id } == true

    override val defs: LocType
        get() = Cache.loc(id) ?: LocType.EMPTY

    val hidden: Boolean
        get() = ptr.readByte(OCombinedLocationSection.HIDDEN) != 0.toByte()

    private val realType: LocationType?
        get() {
            val addr = ptr.pointerAtOffset(OCombinedLocationSection.TYPE_SHAREDPTR, 0x24L)
            return if (addr.deref(size = 0x24L).address() == 0L) null else LocationType(addr.toShared().value(OLocationType.extent))
        }
}