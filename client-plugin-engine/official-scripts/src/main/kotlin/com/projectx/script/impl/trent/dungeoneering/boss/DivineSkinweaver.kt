package com.projectx.script.impl.trent.dungeoneering.boss

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.combatTarget
import com.projectx.script.api.findClosestReachableNPC
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.getAllSpotAnimsWithinRange
import com.projectx.script.api.healthPercent
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import world.gregs.voidps.type.Tile

/**
 * The Daemonheim Divine skinweaver boss. ALL of its fight logic lives here (never in the generic
 * navigator/combat, same isolation as the ice rooms). The skinweaver itself is INVINCIBLE (always full HP) —
 * the fight is: kill the skeletons it summons, and on each safe window (a "rockfall" spotanim on the open
 * tunnels) Block ONE tunnel to choke the spawns. Once every tunnel is sealed and the skeletons are dead, Talk
 * to the skinweaver to complete the room. The healing pad is the tile beside the skinweaver — only retreat to
 * it when actually low, never camp it.
 */
object DivineSkinweaver {
    private const val SKINWEAVER_ID = 10058
    private const val ROCKFALL_SPOTANIM = 60 // plays on every UNBLOCKED tunnel while it's safe to close one
    private const val TUNNEL = "Tunnel"       // open tunnel; a blocked one becomes a "Stone"
    private const val BLOCK = "Block"
    private const val HEAL_HP = 30
    private val TALK_OPTIONS = listOf("Talk to", "Talk-to", "Speak-to", "Talk")

    // One tunnel per shout: once we've closed one this window we wait for the next (the window ends when the
    // rockfall stops), so we don't chain-block in a single opening.
    private var blockedThisWindow = false
    private var completed = false

    fun present(): Boolean {
        val here = boss() != null
        if (!here) completed = false // left the room / a new encounter → re-arm for next time
        return here && !completed
    }

    private fun boss(): NPC? =
        allNpcsWithinRange(30) { it.id == SKINWEAVER_ID }.minByOrNull { it.tile.getDistance(localPlayer.tile) }

    private fun safeToBlock(): Boolean =
        getAllSpotAnimsWithinRange(30) { it.id == ROCKFALL_SPOTANIM }.isNotEmpty()

    private fun openTunnels(): List<SceneObject> =
        getAllObjectsWithinRange(30).filter { it.name() == TUNNEL }

    // The adds to clear: reachable attackable NPCs other than the invincible skinweaver. Reachability drops
    // monsters in adjacent rooms (e.g. an Earth warrior) that aren't part of the fight and mustn't gate it.
    private fun nearestAdd(): NPC? =
        findClosestReachableNPC(30) { it.id != SKINWEAVER_ID && it.currentHealth > 0 && it.hasOption("Attack") }

    suspend fun fight(script: Script) {
        val boss = boss() ?: return

        // 1) Safe window → close ONE tunnel (stops future skeleton spawns). Highest priority.
        if (!safeToBlock()) blockedThisWindow = false
        if (safeToBlock() && !blockedThisWindow) {
            val tunnel = openTunnels().filter { it.hasOption(BLOCK) }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
            if (tunnel != null && tunnel.interact(BLOCK)) {
                blockedThisWindow = true
                script.waitUntilNotMoving()
                return
            }
        }

        val add = nearestAdd()

        // 2) Every tunnel sealed and no reachable adds left → talk to the skinweaver to finish the room.
        if (openTunnels().isEmpty() && add == null) {
            val talk = TALK_OPTIONS.firstOrNull { boss.hasOption(it) }
            if (talk != null && boss.interact(talk)) completed = true
            else println("DUNG: skinweaver talk failed; opts=${boss.getDef().options.toList()}")
            script.waitUntilNotMoving()
            return
        }

        // 3) Actually low → step onto the healing pad (beside the skinweaver) to top up. Never camp it.
        if (healthPercent < HEAL_HP && localPlayer.tile.getDistance(boss.tile) > 1) {
            walkTo(closestAdjacent(boss.tile), false)
            return
        }

        // 4) Otherwise kill the adds — retarget only when we aren't already on a live one.
        val target = combatTarget
        if (target == null || target.currentHealth <= 0) {
            add?.interact("Attack")
        }
    }

    private fun closestAdjacent(t: Tile): Tile =
        listOf(
            Tile.of(t.x + 1, t.y, t.plane), Tile.of(t.x - 1, t.y, t.plane),
            Tile.of(t.x, t.y + 1, t.plane), Tile.of(t.x, t.y - 1, t.plane),
        ).minByOrNull { it.getDistance(localPlayer.tile) }!!
}
