package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.script.api.interfaces
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import com.projectx.script.impl.trent.dungeoneering.CriticalityAnalyzer
import com.projectx.script.impl.trent.dungeoneering.DungeonSession

/**
 * Daemonheim "Follow the Leader" statue room: stand on the pressure pad, watch the lead statue perform an emote,
 * and pick the matching option from the offered menu. Three correct and the statue lets you pass.
 *
 * The emote animation is brief — it plays for a second, then the statue idles while the menu waits — so we poll
 * it tightly and cache the last emote seen, then answer the moment the menu (the standard select-option chatbox
 * 1188) is up. A correct answer advances the statue to the next emote. Completion is unambiguous: the lead
 * statue swaps from its ACTIVE id to its DORMANT twin, so `present()` naturally falls quiet and the navigator
 * crosses the now-open door; the room is also recorded solved per floor as a backup.
 */
object FollowLeaderPuzzle {
    private const val PAD = "Pressure pad"
    private const val MENU = 1188
    private const val RANGE = 14

    private val ACTIVE = setOf(10966, 10967, 10968, 12114, 12960)
    private val DORMANT = setOf(10969, 10970, 10971, 12115, 12961)

    private val EMOTE = mapOf(
        863 to "Wave",
        855 to "Nod head",
        856 to "Shake head",
        861 to "Laugh",
        860 to "Cry",
    )

    // Menu option-text component -> its resume-row component (the engine's interface-1188 dialogue layout).
    private val ROWS = listOf(6 to 8, 33 to 13, 35 to 18, 37 to 23, 39 to 28)
    private val NEIGHBOURS = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)
    private val solved = HashSet<Long>()

    private fun activeStatue(): NPC? = npcsInRoom(RANGE) { it.id in ACTIVE }.firstOrNull()
    private fun dormantPresent(): Boolean = npcsInRoom(RANGE) { it.id in DORMANT }.isNotEmpty()

    private fun activePad(statue: NPC): SceneObject? =
        objectsInRoom(RANGE).filter { it.name() == PAD }.minByOrNull { it.tile.getDistance(statue.tile) }

    private fun menuOpen(): Boolean = ROWS.any { interfaces.getComponent(MENU, it.first) != null }

    private fun key(seed: Int, statue: NPC): Long =
        (seed.toLong() shl 32) xor (statue.tile.x.toLong() shl 16) xor statue.tile.y.toLong()

    private fun answer(emote: String): Boolean {
        val row = ROWS.firstOrNull {
            interfaces.getComponent(MENU, it.first)?.text?.contains(emote, ignoreCase = true) == true
        }?.second ?: return false
        return IFSlot(MENU, row, -1).dialogueContinue()
    }

    fun present(session: DungeonSession): Boolean {
        val statue = activeStatue() ?: return false
        if (key(session.seed, statue) in solved) return false
        return objectsInRoom(RANGE).any { it.name() == PAD }
    }

    suspend fun solve(session: DungeonSession, script: Script) {
        val first = activeStatue() ?: return
        val roomKey = key(session.seed, first)
        val pad = activePad(first) ?: return

        var lastEmote: String? = null
        var stallMs = 0
        while (true) {
            val statue = activeStatue()
            if (statue == null) {
                if (dormantPresent()) markSolved(session, roomKey)
                return
            }

            if (!menuOpen() && (localPlayer.tile.x != pad.tile.x || localPlayer.tile.y != pad.tile.y)) {
                walkTo(pad.tile, false)
                script.waitUntilNotMoving()
            }

            EMOTE[statue.animationId]?.let { lastEmote = it; stallMs = 0 }

            val emote = lastEmote
            if (emote != null && menuOpen() && answer(emote)) {
                lastEmote = null
                stallMs = 0
                script.delay(1180, 320)
                continue
            }

            script.delay(160, 40)
            stallMs += 160
            if (stallMs >= 15000) return
        }
    }

    private fun markSolved(session: DungeonSession, roomKey: Long) {
        solved += roomKey
        session.currentCell?.let { c ->
            for ((dx, dy) in NEIGHBOURS) session.blockedEdges.remove(CriticalityAnalyzer.edgeKey(c, c.first + dx to c.second + dy))
        }
    }
}
