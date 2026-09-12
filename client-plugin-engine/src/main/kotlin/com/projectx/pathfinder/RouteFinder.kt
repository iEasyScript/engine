package com.projectx.pathfinder

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.api.DangerZone
import com.projectx.script.api.TileArea
import world.gregs.voidps.collision.CollisionFlag
import world.gregs.voidps.collision.CollisionStrategies
import world.gregs.voidps.collision.CollisionStrategy
import world.gregs.voidps.collision.DirectionFlag
import world.gregs.voidps.path.LineValidator
import world.gregs.voidps.path.PathFinder
import world.gregs.voidps.path.Route
import world.gregs.voidps.path.toTiles
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max

private const val DEFAULT_MAX_TURNS = 25
private const val SEARCH_MAP_SIZE = 128

@Suppress("NOTHING_TO_INLINE")
internal inline fun getZoneIndex(x: Int, y: Int, z: Int): Int {
    return (y shr 3) or ((x shr 3) shl 11) or (z shl 22)
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun getIndexInZone(x: Int, y: Int): Int {
    return (x and 0x7) or ((y and 0x7) shl 3)
}

fun routeToObject(tile: Tile, obj: SceneObject, maxTurns: Int = DEFAULT_MAX_TURNS, collision: CollisionStrategy = CollisionStrategies.NORMAL): Route {
    val unrotated = obj.rotation == 0.toByte() || obj.rotation == 2.toByte()
    return PathFinder(flags = WorldCollision.allFlags, useRouteBlockerFlags = true, moveNear = false)
        .findPath(
            tile.x, tile.y,
            obj.tile.x, obj.tile.y,
            tile.plane,
            collision = collision,
            srcSize = 1,
            destWidth = if (unrotated) obj.defs.sizeX else obj.defs.sizeY,
            destHeight = if (unrotated) obj.defs.sizeY else obj.defs.sizeX,
            objRot = obj.rotation.toInt(),
            objShape = obj.shape.id,
            accessBitMask = if (obj.rotation != 0.toByte()) ((obj.defs.blockFlag shl obj.rotation.toInt()) and 0xF) + (obj.defs.blockFlag shr (4 - obj.rotation)) else obj.defs.blockFlag,
            maxTurns = maxTurns
        )
}

fun routedDestination(tile: Tile, obj: SceneObject, maxTurns: Int = DEFAULT_MAX_TURNS, collision: CollisionStrategy = CollisionStrategies.NORMAL) =
    routeToObject(tile, obj, maxTurns, collision).toTiles(tile.plane).lastOrNull()

fun routeToTile(
    tile: Tile,
    destination: Tile,
    srcSize: Int = 1,
    maxTurns: Int = DEFAULT_MAX_TURNS,
    moveNear: Boolean = false
): Route {
    return PathFinder(flags = WorldCollision.allFlags, useRouteBlockerFlags = true, moveNear = moveNear)
        .findPath(
            tile.x, tile.y,
            destination.x, destination.y,
            tile.plane,
            collision = CollisionStrategies.NORMAL,
            srcSize = srcSize,
            destWidth = 1,
            destHeight = 1,
            maxTurns = maxTurns
        )
}

fun routeToNPC(tile: Tile, npc: NPC, maxTurns: Int = DEFAULT_MAX_TURNS): Route {
    return PathFinder(flags = WorldCollision.allFlags, useRouteBlockerFlags = true, moveNear = false)
        .findPath(
            tile.x, tile.y,
            npc.tile.x, npc.tile.y,
            tile.plane,
            collision = CollisionStrategies.NORMAL,
            srcSize = 1,
            destWidth = npc.getDef().size,
            destHeight = npc.getDef().size,
            maxTurns = maxTurns
        )
}

fun hasLineOfSight(src: Tile, srcSize: Int, dst: Tile, dstSize: Int): Boolean {
    if (src.plane != dst.plane) return false
    return LineValidator(SEARCH_MAP_SIZE, WorldCollision.allFlags)
        .hasLineOfSight(src.x, src.y, src.plane, dst.x, dst.y, srcSize, dstSize, dstSize)
}

fun findClosestSafeTile(
    srcX: Int,
    srcY: Int,
    z: Int,
    dangerZones: List<DangerZone>,
    targetArea: TileArea,
    maxRadius: Int = 10,
    srcSize: Int = 1,
    collision: CollisionStrategy = CollisionStrategies.NORMAL
): Tile? = SafeTileSearch(WorldCollision.allFlags).find(srcX, srcY, z, dangerZones, targetArea, maxRadius, srcSize, collision)

private class SafeTileSearch(private val flags: Array<IntArray?>) {
    private val searchMapSize = SEARCH_MAP_SIZE
    private val ringBufferSize = 4096
    private val graphInfo = IntArray(searchMapSize * searchMapSize)
    private val validLocalCoords = IntArray(ringBufferSize)
    private var bufReaderIndex = 0
    private var bufWriterIndex = 0
    private var currLocalX = 0
    private var currLocalY = 0

    fun find(
        srcX: Int,
        srcY: Int,
        z: Int,
        dangerZones: List<DangerZone>,
        targetArea: TileArea,
        maxRadius: Int,
        srcSize: Int,
        collision: CollisionStrategy
    ): Tile? {
        reset()

        val baseX = srcX - (searchMapSize / 2)
        val baseY = srcY - (searchMapSize / 2)
        val localSrcX = srcX - baseX
        val localSrcY = srcY - baseY

        val localTargetX = targetArea.tile.x - baseX
        val localTargetY = targetArea.tile.y - baseY
        val targetEndX = localTargetX + targetArea.sizeX - 1
        val targetEndY = localTargetY + targetArea.sizeY - 1

        val dangerMap = precomputeDangerZones(dangerZones, baseX, baseY, searchMapSize)

        setNextValidLocalCoords(localSrcX, localSrcY, SRC_DIRECTION_VALUE, 0)

        var closestSafeTile: Tile? = null
        var bestScore = Double.MAX_VALUE
        val relativeSearchSize = searchMapSize - srcSize

        while (bufWriterIndex != bufReaderIndex) {
            val coord = validLocalCoords[bufReaderIndex]
            currLocalX = coord ushr 16
            currLocalY = coord and 0xFFFF
            bufReaderIndex = (bufReaderIndex + 1) and (ringBufferSize - 1)

            val currentDistance = getDistance(currLocalX, currLocalY)

            val distanceFromSrc = max(abs(currLocalX - localSrcX), abs(currLocalY - localSrcY))
            if (distanceFromSrc > maxRadius) continue

            if (!dangerMap[currLocalY * searchMapSize + currLocalX]) {
                val targetDistance = calculateDistanceToTarget(
                    currLocalX, currLocalY,
                    localTargetX, localTargetY,
                    targetEndX, targetEndY
                )
                val score = currentDistance.toDouble() + (targetDistance * 0.1)

                if (score < bestScore) {
                    bestScore = score
                    closestSafeTile = Tile(currLocalX + baseX, currLocalY + baseY, z)
                    if (currentDistance <= 2 && targetDistance <= 4) return closestSafeTile
                }
            }

            val nextDistance = currentDistance + 1

            expandSearchWithDangerAwareness(
                baseX, baseY, z, collision,
                currLocalX, currLocalY, nextDistance, relativeSearchSize
            )
        }

        return closestSafeTile
    }

    private fun precomputeDangerZones(dangerZones: List<DangerZone>, baseX: Int, baseY: Int, mapSize: Int): BooleanArray {
        val dangerMap = BooleanArray(mapSize * mapSize)
        for (zone in dangerZones) {
            val centerX = zone.center.x - baseX
            val centerY = zone.center.y - baseY
            val radiusSquared = zone.radius * zone.radius

            val minX = maxOf(0, centerX - zone.radius)
            val maxX = minOf(mapSize - 1, centerX + zone.radius)
            val minY = maxOf(0, centerY - zone.radius)
            val maxY = minOf(mapSize - 1, centerY + zone.radius)

            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val dx = x - centerX
                    val dy = y - centerY
                    if (dx * dx + dy * dy <= radiusSquared) {
                        dangerMap[y * mapSize + x] = true
                    }
                }
            }
        }
        return dangerMap
    }

    private fun calculateDistanceToTarget(x: Int, y: Int, targetX: Int, targetY: Int, targetEndX: Int, targetEndY: Int): Int {
        val closestX = x.coerceIn(targetX, targetEndX)
        val closestY = y.coerceIn(targetY, targetEndY)
        val dx = x - closestX
        val dy = y - closestY
        return dx * dx + dy * dy
    }

    private fun expandSearchWithDangerAwareness(
        baseX: Int, baseY: Int, z: Int,
        collision: CollisionStrategy,
        currLocalX: Int, currLocalY: Int, nextDistance: Int, relativeSearchSize: Int
    ) {
        val directions = arrayOf(
            intArrayOf(-1, 0, DirectionFlag.EAST, CollisionFlag.BLOCK_WEST),
            intArrayOf(1, 0, DirectionFlag.WEST, CollisionFlag.BLOCK_EAST),
            intArrayOf(0, -1, DirectionFlag.NORTH, CollisionFlag.BLOCK_SOUTH),
            intArrayOf(0, 1, DirectionFlag.SOUTH, CollisionFlag.BLOCK_NORTH),
            intArrayOf(-1, -1, DirectionFlag.NORTH_EAST, -1),
            intArrayOf(1, -1, DirectionFlag.NORTH_WEST, -1),
            intArrayOf(-1, 1, DirectionFlag.SOUTH_EAST, -1),
            intArrayOf(1, 1, DirectionFlag.SOUTH_WEST, -1)
        )

        for (dir in directions) {
            val x = currLocalX + dir[0]
            val y = currLocalY + dir[1]
            val dirFlag = dir[2]
            val clipFlag = dir[3]

            if (x < 0 || y < 0 || x > relativeSearchSize || y > relativeSearchSize) continue
            if (getDirection(x, y) != 0) continue

            val canMove = if (clipFlag == -1)
                checkDiagonalMovement(baseX, baseY, x, y, z, currLocalX, currLocalY, collision, dir[0], dir[1])
            else
                collision.canMove(flags[baseX, baseY, x, y, z], clipFlag)

            if (canMove) {
                setNextValidLocalCoords(x, y, dirFlag, nextDistance)
            }
        }
    }

    private fun checkDiagonalMovement(
        baseX: Int, baseY: Int, x: Int, y: Int, z: Int,
        currLocalX: Int, currLocalY: Int, collision: CollisionStrategy,
        dx: Int, dy: Int
    ): Boolean {
        val horizontalClear = when {
            dx < 0 -> collision.canMove(flags[baseX, baseY, x, currLocalY, z], CollisionFlag.BLOCK_WEST)
            dx > 0 -> collision.canMove(flags[baseX, baseY, x, currLocalY, z], CollisionFlag.BLOCK_EAST)
            else -> true
        }

        val verticalClear = when {
            dy < 0 -> collision.canMove(flags[baseX, baseY, currLocalX, y, z], CollisionFlag.BLOCK_SOUTH)
            dy > 0 -> collision.canMove(flags[baseX, baseY, currLocalX, y, z], CollisionFlag.BLOCK_NORTH)
            else -> true
        }

        val diagonalFlags = when {
            dx < 0 && dy < 0 -> CollisionFlag.BLOCK_SOUTH_WEST
            dx > 0 && dy < 0 -> CollisionFlag.BLOCK_SOUTH_EAST
            dx < 0 && dy > 0 -> CollisionFlag.BLOCK_NORTH_WEST
            dx > 0 && dy > 0 -> CollisionFlag.BLOCK_NORTH_EAST
            else -> 0
        }

        val diagonalClear = if (diagonalFlags != 0) {
            collision.canMove(flags[baseX, baseY, x, y, z], diagonalFlags)
        } else {
            true
        }

        return horizontalClear && verticalClear && diagonalClear
    }

    private fun reset() {
        graphInfo.fill(DISTANCE_VALUE shl 7)
        bufReaderIndex = 0
        bufWriterIndex = 0
    }

    private fun setNextValidLocalCoords(localX: Int, localY: Int, direction: Int, distance: Int) {
        val pathIndex = (localY * searchMapSize) + localX
        graphInfo[pathIndex] = direction or (distance shl 7)
        validLocalCoords[bufWriterIndex] = (localX shl 16) or localY
        bufWriterIndex = (bufWriterIndex + 1) and (ringBufferSize - 1)
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun getDistance(localX: Int, localY: Int): Int = graphInfo[(localY * searchMapSize) + localX] ushr 7

    @Suppress("NOTHING_TO_INLINE")
    private inline fun getDirection(localX: Int, localY: Int): Int = graphInfo[(localY * searchMapSize) + localX] and 0x7F

    @Suppress("NOTHING_TO_INLINE")
    private inline operator fun Array<IntArray?>.get(baseX: Int, baseY: Int, localX: Int, localY: Int, z: Int): Int {
        val x = baseX + localX
        val y = baseY + localY
        val zone = this[getZoneIndex(x, y, z)] ?: return -1
        return zone[getIndexInZone(x, y)]
    }

    private companion object {
        private const val SRC_DIRECTION_VALUE = 99
        private const val DISTANCE_VALUE = 999
    }
}
