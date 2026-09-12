package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.Script
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.impl.trent.dungeoneering.DungeonSession

/**
 * Daemonheim "synchronised switch" room: several wall switches must all be in the same state at once for the
 * door to open, and a switch drops back on its own after a few seconds — so they have to be pulled in quick
 * succession rather than at a comfortable pace.
 */
object SynchSwitchPuzzle {

    // Matched on the cache name plus the option it exposes rather than on ids: the room ships under five
    // themes with different ids, and nothing guarantees a new one reuses those numbers.
    private const val SWITCH_NAME = "Switch"
    private const val RANGE = 24
    private const val PULL = "Pull"
    private const val ATTEMPT_LIMIT = 6

    private var attemptCell: Pair<Int, Int>? = null
    private var attempts = 0

    private fun switches(): List<SceneObject> =
        objectsInRoom(RANGE).filter { it.name() == SWITCH_NAME && it.hasOption(PULL) }

    /**
     * Claim on the switches being in the room at all — pulled/unpulled state is not readable from the ids (a
     * live room had all five switches on the SAME id), so completion is detected by leaving the room instead:
     * once the door opens the navigator crosses and the switches drop out of range on their own.
     */
    fun present(session: DungeonSession): Boolean {
        if (session.currentCell != null && session.currentCell in session.bannedCells) return false
        return switches().size >= 2
    }

    suspend fun solve(session: DungeonSession, script: Script) {
        if (session.currentCell != attemptCell) {
            attemptCell = session.currentCell
            attempts = 0
        }
        val targets = switches().sortedBy { it.tile.getDistance(localPlayer.tile) }
        if (targets.isEmpty()) return

        // Pull the whole run back-to-back. A switch drops on its own after a few seconds, so confirming each
        // one before moving on guarantees the first has reset before the last is thrown - the timing IS the
        // puzzle, and a comfortable pace can never solve it.
        for (sw in targets) {
            sw.interact(PULL)
            script.waitUntilNotMoving()
            script.delay(200, 60)
        }
        attempts++
        println("DUNG: synch-switch pulled ${targets.size} switches (attempt $attempts)")
        script.delay(1200, 300)

        if (attempts >= ATTEMPT_LIMIT) {
            val cell = session.currentCell
            session.ban(cell, "synch-switch unsolved after $ATTEMPT_LIMIT rounds")
        }
    }
}
