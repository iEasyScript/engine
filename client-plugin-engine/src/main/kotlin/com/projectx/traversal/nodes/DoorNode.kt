package com.projectx.traversal.nodes

import world.gregs.voidps.type.Tile
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.profiling.PlayerProfiles
import com.projectx.script.Script
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.localPlayer
import com.projectx.script.api.walkTo
import com.projectx.traversal.TraversalNode
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

class DoorNode(
    private val doorInfo: DoorInfo,
    private val direction: DoorDirection,
    private val customReached: (() -> Boolean)? = null
) : TraversalNode() {

    private var nextClick: Long = 0
    private val targetTile: Tile = when (direction) {
        DoorDirection.IN -> doorInfo.tileInside
        DoorDirection.OUT -> doorInfo.tileOutside
    }

    private fun findDoor(script: Script): SceneObject? {
        val closedDoor = findClosestObject(10) { obj ->
            obj.id == doorInfo.realIdClosed &&
            (obj.tile == doorInfo.locationClosed || obj.tile == doorInfo.locationOpen)
        }

        if (closedDoor != null) {
            return closedDoor
        }

        return findClosestObject(10) { obj ->
            obj.id == doorInfo.realIdOpen &&
            (obj.tile == doorInfo.locationClosed || obj.tile == doorInfo.locationOpen)
        }
    }

    private fun isDoorOpen(script: Script): Boolean {
        return findClosestObject(10) { obj ->
            obj.id == doorInfo.realIdOpen &&
            (obj.tile == doorInfo.locationClosed || obj.tile == doorInfo.locationOpen)
        } != null
    }

    override suspend fun process(script: Script): Boolean {
        if (System.currentTimeMillis() < nextClick) return true

        if (reached(script)) return false

        val doorObj = findDoor(script)
        if (doorObj == null) {
            script.delay(100, 100)
            return true
        }

        if (isDoorOpen(script)) {
            walkTo(targetTile, false)
            script.delayUntil(15000) { !localPlayer.isMoving }
            nextClick = System.currentTimeMillis() + gaussian(PlayerProfiles.get().walkPathClickTime, PlayerProfiles.get().walkPathClickTime / 2)
            return true
        }

        val openAction = doorInfo.openAction ?: "Open"
        val success = doorObj.interact(openAction)

        if (success) {
            script.delayUntil(5000) { isDoorOpen(script) }
            script.delay(200, 400)

            walkTo(targetTile, false)
            script.delayUntil(15000) { !localPlayer.isMoving }

            nextClick = System.currentTimeMillis() + gaussian(PlayerProfiles.get().walkPathClickTime, PlayerProfiles.get().walkPathClickTime / 2)
            return true
        }

        return false
    }

    override fun reached(script: Script): Boolean {
        return customReached?.invoke() ?: (localPlayer.tile.getDistance(targetTile) == 0)
    }

    override fun copy(): TraversalNode = DoorNode(doorInfo, direction, customReached)

    override fun toString(): String = "[Door: ${doorInfo.realIdClosed}/${doorInfo.realIdOpen} - Direction: $direction]"
} 