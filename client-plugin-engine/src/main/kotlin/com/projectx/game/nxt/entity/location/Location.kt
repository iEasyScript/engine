package com.projectx.game.nxt.entity.location
import com.projectx.game.memory.atLeast
import com.projectx.game.nxt.extent

import world.gregs.voidps.type.Tile
import world.gregs.voidps.map.ObjectShape
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.getOrNull
import com.projectx.game.memory.NativeAccess.readByte
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.nxt.OLocation
import com.projectx.game.nxt.OLocationType
import com.projectx.game.nxt.entity.Entity
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.LocType
import world.gregs.voidps.gameval.Gameval
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS

class Location(raw: MemorySegment) : Entity(raw), SceneObject {
    override val memPointer = ptr

    val isDeleted: Boolean
        get() = ptr.readByte(OLocation.IS_DELETED) != 0.toByte()

    val isHidden: Boolean
        get() = ptr.readByte(OLocation.IS_HIDDEN) != 0.toByte()

    override val shape = ObjectShape.forId(ptr.readByte(OLocation.SHAPE).toInt())
    override val rotation
        get() = ptr.readByte(OLocation.ROTATION)

    override val tile: Tile
        get() = Tile.of(ptr.readInt(OLocation.POS_X), ptr.readInt(OLocation.POS_Y), plane)

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
        get() = realType?.id ?: typeId

    override val typeId: Int
        get() = ptr.readInt(OLocation.TYPE_ID)

    override val defs: LocType
        get() = Cache.loc(id) ?: LocType.EMPTY

    private val realType
        get() = ptr.deref(OLocation.ORIGINAL_TYPE, OLocationType.extent).getOrNull?.let { LocationType(it) }

    val renderNodeAddr: Long
        get() = ptr.get(ADDRESS, OLocation.RENDER_NODE).address()

    override val exists: Boolean
        get() = Bootstrap.client.sceneManager.getLocationContainer(tile)?.allSceneObjects?.any { it.tile == tile && it.id == id } == true

    override fun toString(): String {
        return "[${Gameval.locLabel(id)} (${getName()}), $type, $rotation, ${tile}, clipType: ${getDef().clipType}]"
    }
}

class LocationType(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OLocationType.extent)
    val id: Int
        get() = ptr.readInt(OLocationType.ID)
    val sizeX: Int
        get() = ptr.readInt(OLocationType.SIZE_X)
    val sizeY: Int
        get() = ptr.readInt(OLocationType.SIZE_Y)
}