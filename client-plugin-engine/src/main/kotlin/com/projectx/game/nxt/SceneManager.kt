package com.projectx.game.nxt
import com.projectx.game.memory.atLeast

import world.gregs.voidps.type.Tile
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.getOrNull
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readFloat
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.NativeAccess.toShared
import com.projectx.game.nxt.entity.Entity
import com.projectx.game.nxt.entity.GraphNode
import com.projectx.game.nxt.entity.location.CombinedLocationSection
import com.projectx.game.nxt.entity.location.Location
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.types.Vector
import com.projectx.script.api.Area
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.util.Objects
import kotlin.math.abs

class SceneManager(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OSceneManager.extent)
    val currentWorldIndex: Int
        get() = ptr.readInt(OSceneManager.CURRENT_WORLD_INDEX)
    val currentWorld: World?
        get() = if (ptr.address() == 0L) null else World(
            ptr.deref(
                OSceneManager.WORLD_ARRAY + currentWorldIndex * OSceneManager.WORLD_ARRAY_STRIDE,
                0x10L
            ).toShared().value(0x20000L)
        )

    fun getMapSquare(tile: Tile): MapSquare? {
        val world = currentWorld ?: return null
        return world.getMapSquare(tile.mapSquareX, tile.mapSquareY)
    }

    fun getLocationContainer(tile: Tile): LocationContainer? {
        val mapSquare = getMapSquare(tile) ?: return null
        return mapSquare.getLocationContainer(tile.xInMapSquare, tile.yInMapSquare)
    }

    fun getAllObjectsWithinRange(tile: Tile, range: Int): List<SceneObject> {
        val world = currentWorld ?: return emptyList()
        val minMapSquareX = (tile.x - range) shr 6
        val maxMapSquareX = (tile.x + range) shr 6
        val minMapSquareY = (tile.y - range) shr 6
        val maxMapSquareY = (tile.y + range) shr 6
        val rangeSq = range * range

        val objects = mutableListOf<SceneObject>()
        for (mapSquareX in minMapSquareX..maxMapSquareX) {
            for (mapSquareY in minMapSquareY..maxMapSquareY) {
                val mapSquare = world.getMapSquare(mapSquareX, mapSquareY) ?: continue
                mapSquare.allSceneObjects.filterTo(objects) { obj ->
                    val dx = obj.tile.x - tile.x
                    val dy = obj.tile.y - tile.y
                    dx * dx + dy * dy <= rangeSq
                }
            }
        }
        return objects.distinctBy { it.memPointer.address() }
    }

    fun getAllObjectsInArea(area: Area): List<SceneObject> {
        val world = currentWorld ?: return emptyList()
        val rectangular = area.toRectangular()
        val bottomLeft = rectangular.getBottomLeft()
        val topRight = rectangular.getTopRight()
        val minMapSquareX = bottomLeft.x shr 6
        val maxMapSquareX = topRight.x shr 6
        val minMapSquareY = bottomLeft.y shr 6
        val maxMapSquareY = topRight.y shr 6

        val objects = mutableListOf<SceneObject>()
        for (mapSquareX in minMapSquareX..maxMapSquareX) {
            for (mapSquareY in minMapSquareY..maxMapSquareY) {
                val mapSquare = world.getMapSquare(mapSquareX, mapSquareY) ?: continue
                mapSquare.allSceneObjects.filterTo(objects) { area.contains(it.tile) }
            }
        }
        return objects.distinctBy { it.memPointer.address() }
    }
}

@Volatile private var projectionMatrixBacking: FloatArray? = null
class World(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OWorld.extent)
    val rootGraphNode: GraphNode?
        get() = ptr.deref(OWorld.ROOT_GRAPH_NODE, OGraphNode.extent).getOrNull?.let { GraphNode(it) }

    val mapsquareXOffset: Int
        get() = ptr.readInt(OWorld.MAPSQUARE_X_OFFSET)
    val mapsquareYOffset: Int
        get() = ptr.readInt(OWorld.MAPSQUARE_Y_OFFSET)

    val viewMatrix: FloatArray
        get() = FloatArray(16) { i -> ptr.readFloat(OWorld.VIEW_MATRIX + (i * 4L)) }
    val projectionMatrix: FloatArray
        get() {
            val matrix = FloatArray(16) { i -> ptr.readFloat(OWorld.PROJECTION_MATRIX + (i * 4L)) }
            val isSkyBoxTransitioning = matrix.any { abs(abs(it) - 1.015748f) <= 1e-6f }
            if (isSkyBoxTransitioning && projectionMatrixBacking != null) {
                return projectionMatrixBacking!!
            }
            projectionMatrixBacking = matrix
            return matrix
        }

    private val mapSquares: Vector
        get() = Vector(ptr.pointerAtOffset(OWorld.MAPSQUARES_VECTOR, 0x20L), 0x18L)

    fun getMapSquare(mapSquareX: Int, mapSquareY: Int): MapSquare? {
        val adjustedX = mapSquareX - mapsquareXOffset
        val adjustedY = mapSquareY - mapsquareYOffset

        if (adjustedX < 0 || adjustedY < 0) return null
        if (adjustedX >= mapSquares.size) return null

        val innerVectorSegment = mapSquares[adjustedX]
        val innerVector = Vector(innerVectorSegment, 0x18L)

        if (adjustedY >= innerVector.size) return null
        val shared = innerVector[adjustedY].toShared().value(OMapSquare.extent).getOrNull ?: return null
        return MapSquare(shared)
    }
}

class MapSquare(raw: MemorySegment) : Entity(raw) {
    /** The client stopped deriving this from the composite-data pointer and now divides by a constant. */
    private companion object {
        const val CHUNK_SIZE = 16
    }

    val chunkSize: Int
        get() = CHUNK_SIZE

    private val locationContainers: Vector
        get() = Vector(ptr.pointerAtOffset(OMapSquare.LOCATION_CONTAINERS, 0x20L), 0x18L)

    fun getLocationContainer(containerX: Int, containerY: Int): LocationContainer? {
        val xVector = locationContainers
        if (xVector.begin.address() == xVector.end.address()) return null

        val cs = chunkSize
        val xIdx = containerX / cs
        if (xIdx < 0 || xIdx >= xVector.size) return null

        val yVector = Vector(xVector[xIdx], OMapSquare.LOCATION_CONTAINER_ENTRY_STRIDE)
        if (yVector.begin.address() == yVector.end.address()) return null
        val yIdx = containerY / cs
        if (yIdx < 0 || yIdx >= yVector.size) return null

        val lc = yVector[yIdx].deref(OMapSquare.LOCATION_CONTAINER_ENTRY_PTR_OFFSET, OLocationContainer.extent).getOrNull ?: return null
        return LocationContainer(lc)
    }

    val allSceneObjects: List<SceneObject>
        get() {
            val xVector = locationContainers
            if (xVector.begin.address() == xVector.end.address()) return emptyList()
            val result = mutableListOf<SceneObject>()
            for (i in 0 until xVector.size.toInt()) {
                val yVector = Vector(xVector[i], OMapSquare.LOCATION_CONTAINER_ENTRY_STRIDE)
                if (yVector.begin.address() == yVector.end.address()) continue
                for (j in 0 until yVector.size.toInt()) {
                    val lc = yVector[j].deref(OMapSquare.LOCATION_CONTAINER_ENTRY_PTR_OFFSET, OLocationContainer.extent).getOrNull ?: continue
                    result += LocationContainer(lc).allSceneObjects
                }
            }
            return result
        }
}

class LocationContainer(raw: MemorySegment) : Entity(raw) {
    val allSceneObjects: List<SceneObject>
        get() = graphNode.sceneObjects

}
