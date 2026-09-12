package com.projectx.script.impl.trent.dungeoneering.boss

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import com.projectx.script.impl.trent.dungeoneering.DungeonSession
import com.projectx.util.gaussian
import world.gregs.voidps.type.Tile

/**
 * The Daemonheim Night-gazer Khighorahk. The boss is immune while the room is dark — swinging at it just
 * answers "you can't reach that" forever — so the fight is: light every Pillar of light, THEN attack.
 *
 * A pillar burns down on its own (its loc runs unlit → lit → dead, and only the unlit one carries an option),
 * so the run has to be back-to-back: a pillar lit at a comfortable pace has gone out before the last one
 * catches. A burnt-out pillar is indistinguishable from a lit one — same name, no options, same model — so
 * the room being up is never inferred from the pillars, only from a swing actually landing on the boss.
 *
 * DungeonCombat stands down while this is present, exactly as it does for the other bosses: a parallel attack
 * loop would cancel the walk to each pillar and leave the room dark forever.
 */
object NightGazerBoss {

    private const val BOSS = "Night-gazer Khighorahk"
    private const val PILLAR = "Pillar of light"
    private const val LIGHT = "Light"
    private const val RANGE = 20
    private const val ROOM_TILES = 16
    private const val DARK_ROUNDS = 3

    private var attemptCell: Pair<Int, Int>? = null
    private var darkRounds = 0

    fun present(): Boolean = boss() != null

    private fun boss(): NPC? {
        val room = roomOf(localPlayer.tile)
        return allNpcsWithinRange(RANGE) { it.name() == BOSS && roomOf(it.tile) == room }
            .minByOrNull { it.tile.getDistance(localPlayer.tile) }
    }

    private fun pillars(): List<SceneObject> {
        val room = roomOf(localPlayer.tile)
        return getAllObjectsWithinRange(RANGE).filter { it.name() == PILLAR && roomOf(it.tile) == room }
    }

    suspend fun fight(session: DungeonSession, script: Script) {
        if (session.currentCell != attemptCell) {
            attemptCell = session.currentCell
            darkRounds = 0
        }
        val boss = boss() ?: return
        val pillars = pillars()

        val unlit = pillars.filter { it.hasOption(LIGHT) }.sortedBy { it.tile.getDistance(localPlayer.tile) }
        if (unlit.isNotEmpty()) {
            // Only a run that actually lit something is progress. Resetting on a run where every pillar refused
            // disarmed the give-up below permanently, leaving a silent loop with no action and no output.
            if (lightRun(script, unlit)) darkRounds = 0
            return
        }

        // Nothing left to light: the room is either up, or the pillars burned out before the run finished. A
        // burnt-out pillar looks exactly like a lit one — same name, no options, same model — so the swing
        // landing is the only honest read on whether the room is actually up.
        if (attackBoss(script, boss)) {
            darkRounds = 0
            return
        }
        if (++darkRounds >= DARK_ROUNDS) {
            session.ban(session.currentCell, "night-gazer: ${pillars.size} pillars answered but the boss took nothing in $darkRounds rounds")
        }
        script.delay(900, 260)
    }

    /**
     * Light the whole run back-to-back. Each fire is gated on THAT pillar's option going away rather than on a
     * settle delay, so a pillar that refuses is noticed immediately instead of costing the round.
     */
    private suspend fun lightRun(script: Script, unlit: List<SceneObject>): Boolean {
        var fired = false
        for (pillar in unlit) {
            val tile = pillar.tile
            approach(script, pillar)
            if (!pillar.interact(LIGHT)) continue
            fired = true
            script.waitUntilNotMoving()
            script.delayUntil(gaussian(3000L, 700L)) {
                getAllObjectsWithinRange(RANGE).none { it.tile == tile && it.hasOption(LIGHT) }
            }
            script.delay(240, 80)
        }
        return fired
    }

    /** True when the swing actually landed — the room being lit is only observable through the boss. */
    private suspend fun attackBoss(script: Script, boss: NPC): Boolean {
        if (boss.isCombatTarget) return true
        val health = boss.currentHealth
        val landed = { boss.isCombatTarget || boss.currentHealth < health }
        if (boss.interact("Attack")) script.delayUntil(gaussian(2600L, 650L)) { landed() }
        script.delay(460, 140)
        return landed()
    }

    private suspend fun approach(script: Script, obj: SceneObject) {
        if (obj.tile.getDistance(localPlayer.tile) <= 2) return
        walkTo(obj.tile, false)
        script.waitUntilNotMoving()
    }

    private fun roomOf(tile: Tile): Pair<Int, Int> = tile.x / ROOM_TILES to tile.y / ROOM_TILES
}
