package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.script.Script
import com.projectx.script.api.groundItems
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.impl.trent.dungeoneering.auto.DungeonNavigator
import com.projectx.util.gaussian

/**
 * The Daemonheim "wake-guards" / guard-room puzzle: a room of (now-slain) sleeping guards whose exit doors
 * (`rand_wake_guards_door`, id 49306, "Unlock") are locked. The floor is strewn with generic **Guard room
 * keys** (17363). Collect every key first, then Unlock each door — one key is consumed per door, and it does
 * not matter which key opens which. These doors share a name with real colour-matched key doors, so the
 * scanner mis-reads them as NEED_KEY; this owns the room and drives the doors directly.
 */
object GuardRoomPuzzle {
    private const val KEY = 17363    // rand_wake_guards_key "Guard room key"
    private const val DOOR = 49306   // rand_wake_guards_door — option "Unlock"
    private const val RANGE = 28

    private fun doors() = objectsInRoom(RANGE).filter { it.id == DOOR }
    private fun floorKeys() = groundItems.filter { it.id == KEY && it.tile.getDistance(localPlayer.tile) <= RANGE }

    fun present(): Boolean {
        if (doors().isEmpty()) return false
        return floorKeys().isNotEmpty() || inventory.hasItem(KEY)
    }

    suspend fun solve(script: Script) {
        val key = floorKeys().minByOrNull { it.tile.getDistance(localPlayer.tile) }
        if (key != null) {
            if (DungeonNavigator.pickUpGroundItemAt(script, key.tile.x, key.tile.y))
                script.delayUntil(gaussian(2500L, 600L)) { inventory.hasItem(KEY) }
            script.delay(340, 90)
            return
        }
        if (!inventory.hasItem(KEY)) return

        val door = doors().minByOrNull { it.tile.getDistance(localPlayer.tile) } ?: return
        val keysBefore = inventory.count(KEY)
        if (door.interact("Unlock")) {
            script.waitUntilNotMoving()
            script.delayUntil(gaussian(2500L, 600L)) { inventory.count(KEY) < keysBefore }
        }
        script.delay(640, 170)
    }
}
