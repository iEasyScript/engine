package com.projectx.traversal.nodes

import world.gregs.voidps.type.Tile
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.pathfinder.LiveWalkMap
import com.projectx.profiling.PlayerProfiles
import com.projectx.quest.runtime.RecentChat
import com.projectx.script.Script
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.localPlayer
import com.projectx.script.api.walkTo
import com.projectx.traversal.EarlyStart
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.WalkEquivalent
import com.projectx.util.gaussian

enum class DoorDirection {
    IN,
    OUT
}

data class DoorInfo(
    val realIdOpen: Int,
    val realIdClosed: Int,
    val locationOpen: Tile,
    val locationClosed: Tile,
    val tileInside: Tile,
    val tileOutside: Tile,
    val openAction: String? = "Open"
)

/**
 * Opens a door if it is shut, then crosses to the requested side. Open or shut is read off the live collision edge
 * where it can be - the two side tiles joining by a short walk - which is right for doors whose leaf shows open while
 * the edge stays solid; the object ids decide elsewhere. An open door is just two tiles of walking, so the walk before
 * it passes straight through; a shut one is opened from a few steps away, and a locked one fails the traversal.
 */
class DoorNode(
    private val doorInfo: DoorInfo,
    private val direction: DoorDirection,
    private val customReached: (() -> Boolean)? = null
) : TraversalNode(), WalkEquivalent, EarlyStart {

    private var nextClick: Long = 0
    private val targetTile: Tile = when (direction) {
        DoorDirection.IN -> doorInfo.tileInside
        DoorDirection.OUT -> doorInfo.tileOutside
    }
    private val nearTile: Tile = when (direction) {
        DoorDirection.IN -> doorInfo.tileOutside
        DoorDirection.OUT -> doorInfo.tileInside
    }

    private fun find(id: Int): SceneObject? = findClosestObject(SCAN_RADIUS) { obj ->
        (obj.id == id || obj.visibleTypeId == id) && (obj.tile == doorInfo.locationClosed || obj.tile == doorInfo.locationOpen)
    }

    private fun findDoor(): SceneObject? = find(doorInfo.realIdClosed) ?: find(doorInfo.realIdOpen)

    /** The live map's verdict, or null when it cannot say; a door opened on its own tile is no wall edge. */
    private fun edgeOpen(): Boolean? =
        if (doorInfo.tileInside == doorInfo.tileOutside) null else LiveWalkMap.passage(doorInfo.tileOutside, doorInfo.tileInside, PASSAGE_STEPS)

    private fun isDoorOpen(): Boolean = edgeOpen() ?: (find(doorInfo.realIdOpen) != null)

    /** On the far side: the target tile, or beside it and nearer it than the near side, as a walk-through door leaves you. */
    private fun crossed(): Boolean {
        val tile = localPlayer.tile
        if (tile == targetTile) return true
        if (doorInfo.tileInside == doorInfo.tileOutside || tile.plane != targetTile.plane) return false
        val toFar = tile.getLongestDelta(targetTile)
        return toFar <= 1 && toFar < tile.getLongestDelta(nearTile)
    }

    override suspend fun process(script: Script): Boolean {
        if (crossed()) return false
        if (System.currentTimeMillis() < nextClick) return true

        if (isDoorOpen()) {
            if (edgeOpen() == true && (next as? WalkEquivalent)?.routeTiles()?.isNotEmpty() == true) return false
            walkTo(targetTile, false)
            script.delayUntil(15000) { traversalStopRequested() || !localPlayer.isMoving || crossed() }
            nextClick = System.currentTimeMillis() + gaussian(PlayerProfiles.get().walkPathClickTime, PlayerProfiles.get().walkPathClickTime / 2)
            return true
        }

        val doorObj = findDoor()
        if (doorObj == null) {
            script.delay(100, 100)
            return true
        }

        val openAction = doorInfo.openAction ?: "Open"
        val picksLock = openAction.contains("pick", ignoreCase = true)
        val clickedAt = System.currentTimeMillis()
        if (!doorObj.interact(openAction)) return false
        nextClick = System.currentTimeMillis() + gaussian(PlayerProfiles.get().walkPathClickTime, PlayerProfiles.get().walkPathClickTime / 2)
        script.delayUntil(OPEN_WAIT_MS) {
            traversalStopRequested() || isDoorOpen() || crossed() || !picksLock && RecentChat.containsSince(LOCKED_MESSAGE, clickedAt)
        }
        if (!picksLock && RecentChat.containsSince(LOCKED_MESSAGE, clickedAt)) {
            failTraversal("locked door $this")
            return true
        }
        if (crossed() || traversalStopRequested()) return !crossed()
        if (!isDoorOpen()) return true
        if (edgeOpen() == true && (next as? WalkEquivalent)?.routeTiles()?.isNotEmpty() == true) return false
        script.delay(200, 400)
        walkTo(targetTile, false)
        script.delayUntil(15000) { traversalStopRequested() || !localPlayer.isMoving || crossed() }
        return true
    }

    override fun reached(script: Script): Boolean = customReached?.invoke() ?: crossed()

    override fun routeTiles(): List<Tile> = listOf(nearTile, targetTile)

    override fun passableNow(): Boolean = edgeOpen() == true

    override fun readyFrom(x: Int, y: Int, plane: Int): Boolean {
        if (plane != nearTile.plane || edgeOpen() != false) return false
        return LiveWalkMap.distance(Tile.of(x, y, plane), nearTile, EarlyStart.MAX_STEPS) >= 0 && findDoor() != null
    }

    override fun copy(): TraversalNode = DoorNode(doorInfo, direction, customReached)

    override fun toString(): String = "[Door: ${doorInfo.realIdClosed}/${doorInfo.realIdOpen} - Direction: $direction]"

    private companion object {
        private const val SCAN_RADIUS = 16
        private const val PASSAGE_STEPS = 3
        private const val OPEN_WAIT_MS = 8_000L
        private const val LOCKED_MESSAGE = "locked"
    }
}
