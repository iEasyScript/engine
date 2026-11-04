package com.projectx.traversal.nodes

import world.gregs.voidps.type.Tile
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.pathfinder.LiveWalkMap
import com.projectx.quest.runtime.RecentChat
import com.projectx.script.Script
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.localPlayer
import com.projectx.script.api.walkTo
import com.projectx.traversal.EarlyStart
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.WalkEquivalent
import com.projectx.traversal.nodes.NodeWaits.awaitWalk

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
    private var missingSince = 0L
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

    private fun isDoorOpen(): Boolean = edgeOpen() ?: openById()

    /** With no open id, a closed door gone from its tile is open, but only where the scene shows that tile. */
    private fun openById(): Boolean {
        if (doorInfo.realIdOpen != -1) return find(doorInfo.realIdOpen) != null
        val me = localPlayer.tile
        return me.plane == doorInfo.locationClosed.plane && me.getLongestDelta(doorInfo.locationClosed) <= SCAN_RADIUS &&
            find(doorInfo.realIdClosed) == null
    }

    private suspend fun walkToTarget(script: Script) {
        if (walkTo(targetTile, false)) {
            script.awaitWalk(NodeWaits.WALK_START_TIMEOUT_MS, NodeWaits.WALK_LEG_TIMEOUT_MS) { traversalStopRequested() || crossed() }
        }
        nextClick = System.currentTimeMillis() + NodeWaits.clickGap()
    }

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
            walkToTarget(script)
            return false
        }

        val doorObj = findDoor()
        if (doorObj == null) {
            val now = System.currentTimeMillis()
            if (missingSince == 0L) missingSince = now
            // Just after a teleport the scene is still loading: wait for it rather than walking at a door not yet drawn.
            if (LiveWalkMap.arriving() && now - missingSince < ARRIVAL_WAIT_MS) {
                script.delay(100, 100)
                return true
            }
            println("[DoorNode] $this not found at ${doorInfo.locationClosed}; walking on to $targetTile")
            walkToTarget(script)
            return false
        }
        missingSince = 0L

        val openAction = doorInfo.openAction ?: "Open"
        val picksLock = openAction.contains("pick", ignoreCase = true)
        val clickedAt = System.currentTimeMillis()
        if (!doorObj.interact(openAction)) return false
        var idleSince = clickedAt
        var idleTile = localPlayer.tile
        script.delayUntil(OPEN_WAIT_MS) {
            val now = System.currentTimeMillis()
            val tile = localPlayer.tile
            if (tile != idleTile || localPlayer.isMoving || localPlayer.isAnimating) {
                idleSince = now
                idleTile = tile
            }
            traversalStopRequested() || isDoorOpen() || crossed() || !picksLock && RecentChat.containsSince(LOCKED_MESSAGE, clickedAt) ||
                now - idleSince >= IDLE_RECLICK_MS
        }
        nextClick = System.currentTimeMillis() + NodeWaits.clickGap()
        if (!picksLock && RecentChat.containsSince(LOCKED_MESSAGE, clickedAt)) {
            failTraversal("locked door $this")
            return true
        }
        if (crossed() || traversalStopRequested()) return !crossed()
        if (!isDoorOpen()) {
            println("[DoorNode] nothing happened after '$openAction' on $this")
            return true
        }
        if (edgeOpen() == true && (next as? WalkEquivalent)?.routeTiles()?.isNotEmpty() == true) return false
        walkToTarget(script)
        return false
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
        private const val IDLE_RECLICK_MS = 2_400L
        private const val ARRIVAL_WAIT_MS = 6_000L
    }
}
