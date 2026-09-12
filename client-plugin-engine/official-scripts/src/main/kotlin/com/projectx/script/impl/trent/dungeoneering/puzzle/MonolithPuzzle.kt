package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.script.api.combatTarget
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.util.gaussian

/**
 * Daemonheim monolith puzzle: a monolith is besieged by Mysterious shades. Activate the monolith, then keep
 * the shades OFF it — the wiki notes a shade stops draining the monolith the moment you engage it in combat,
 * so we just attack the nearest live shade each tick. Once every shade is cleared the monolith finishes
 * powering up and its door unlocks; the navigator then crosses. The door only reads "Enter", so the scanner
 * would force it forever — this owns the room.
 */
object MonolithPuzzle {
    private const val SHADE = "Mysterious shade"
    private const val RANGE = 14

    private fun inactiveMonolith(): NPC? =
        npcsInRoom(RANGE) { it.name() == "Monolith" && it.hasOption("Activate") }.firstOrNull()

    private fun shades(): List<NPC> =
        npcsInRoom(RANGE) { it.name() == SHADE && it.currentHealth > 0 }

    fun present(): Boolean = inactiveMonolith() != null || shades().isNotEmpty()

    suspend fun solve(script: Script) {
        inactiveMonolith()?.let { monolith ->
            if (monolith.interact("Activate")) {
                script.waitUntilNotMoving()
                script.delayUntil(gaussian(2200L, 600L)) { inactiveMonolith() == null }
            }
            script.delay(560, 150)
            return
        }

        val target = combatTarget
        if (target != null && target.currentHealth > 0 && target.name() == SHADE) {
            script.delay(600, 160)
            return
        }
        val shade = shades().minByOrNull { it.tile.getDistance(localPlayer.tile) } ?: return
        if (shade.interact("Attack")) script.waitUntilNotMoving()
        script.delay(600, 160)
    }
}
