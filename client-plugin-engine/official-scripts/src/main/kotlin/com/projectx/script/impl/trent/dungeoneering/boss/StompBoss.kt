package com.projectx.script.impl.trent.dungeoneering.boss

import com.projectx.game.nxt.entity.GroundItem
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.pathfinder.routeToTile
import com.projectx.script.Script
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.combatTarget
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.groundItems
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.util.gaussian
import world.gregs.voidps.type.Tile

/**
 * The Daemonheim Stomp boss - a SPEED gather-and-charge fight. Stomp stomps the ceiling down, scattering
 * coloured power crystals on the floor AND dropping solid Debris that walls off the pedestals; grab a crystal
 * of the LIVE colour and "Charge" it into a matching-colour lodestone (power node), mining any Debris in the
 * way. Charging the live-colour nodes damages Stomp and rotates the colour; repeat fast until it dies. Lives
 * HERE, same isolation as the ice rooms / other bosses - DungeonCombat stands down while this is present.
 *
 * ⚠️ Colour matters BOTH ways: only the active-colour node exposes "Charge", and it only takes a crystal of
 * THAT colour - a leftover crystal from the last phase is useless, so we read the live node's colour and go
 * grab the matching crystal. Reachability gates every interact (never click through the rubble): crystals are
 * floor items so their tile is tested directly; the pedestals AND the debris are SOLID (they block their own
 * tile, so object-routing fails on them) - reach them via an adjacent walkable tile instead.
 */
object StompBoss {
    private const val BOSS = "Stomp"
    private const val LODESTONE = "lodestone"
    private const val DEBRIS = "Debris"
    private const val CHARGE = "Charge"
    private const val MINE = "Mine"
    private const val RANGE = 20
    private const val REFUSAL_LIMIT = 3
    private const val ABANDON_MS = 30_000L
    private val ADJACENT = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    private val refusals = HashMap<Tile, Int>()
    private val abandonedUntil = HashMap<Tile, Long>()

    fun present(): Boolean {
        val boss = boss() ?: return false
        return boss.currentHealth > 0
    }

    suspend fun fight(script: Script) {
        val boss = boss() ?: return

        // The live colour = whichever lodestone currently exposes "Charge". Between phases there's none -> DPS.
        val activeNode = getAllObjectsWithinRange(RANGE).firstOrNull { it.name().endsWith(LODESTONE) && it.hasOption(CHARGE) }
        if (activeNode == null) {
            attackBoss(script, boss)
            return
        }
        val crystalId = crystalFor(activeNode.name())

        if (crystalId > 0 && inventory.hasItem(crystalId)) {
            // Hold the right crystal -> Charge the nearest active node we can stand beside; else mine a path.
            val node = getAllObjectsWithinRange(RANGE)
                .filter {
                    it.name().endsWith(LODESTONE) && it.hasOption(CHARGE) &&
                        !abandoned(it.tile) && adjacentReachable(it.tile)
                }
                .minByOrNull { it.tile.getDistance(localPlayer.tile) }
            if (node != null) chargeNode(script, node, crystalId)
            else if (!mineTowardDebris(script)) attackBoss(script, boss)
            return
        }

        // Need the matching-colour crystal -> grab the nearest REACHABLE one (never click one walled off). If
        // some exist but none are reachable, mine a path; if none exist yet, DPS while we wait for a stomp.
        val crystal = groundItems
            .filter { it.id == crystalId && !abandoned(it.tile) && reachable(it.tile) }
            .minByOrNull { it.tile.getDistance(localPlayer.tile) }
        if (crystal != null) {
            takeCrystal(script, crystal, crystalId)
            return
        }
        if (groundItems.any { it.id == crystalId } && mineTowardDebris(script)) return
        attackBoss(script, boss)
    }

    // Dynamically-dropped Debris never enters the engine's instance collision, so anything behind it reads as
    // reachable and the server silently drops every Take/Charge aimed at it - the unbounded spam that burned
    // whole fights. Confirm the pack actually changed, and write the tile off for a while once it never does,
    // so the loop falls through to mining a path or DPSing instead.
    private suspend fun takeCrystal(script: Script, crystal: GroundItem, crystalId: Int) {
        // Nothing fired means the option is not on it - the same dead end a timed-out Take proves, so it has to
        // reach the same write-off. Returning early here skipped it and re-picked this crystal every tick.
        if (!crystal.interact("Take")) return refuse(crystal.tile, "crystal", "offer a Take")
        script.waitUntilNotMoving()
        script.delayUntil(gaussian(3000L, 700L)) { inventory.hasItem(crystalId) }
        if (inventory.hasItem(crystalId)) refusals.remove(crystal.tile)
        else refuse(crystal.tile, "crystal", "come up")
    }

    private suspend fun chargeNode(script: Script, node: SceneObject, crystalId: Int) {
        if (!node.interact(CHARGE)) return refuse(node.tile, "node", "offer a charge")
        script.waitUntilNotMoving()
        script.delayUntil(gaussian(4000L, 900L)) { !inventory.hasItem(crystalId) }
        if (!inventory.hasItem(crystalId)) refusals.remove(node.tile)
        else refuse(node.tile, "lodestone", "take the charge")
    }

    private fun refuse(tile: Tile, what: String, action: String) {
        val failures = (refusals[tile] ?: 0) + 1
        refusals[tile] = failures
        if (failures < REFUSAL_LIMIT) return
        abandonedUntil[tile] = System.currentTimeMillis() + gaussian(ABANDON_MS, ABANDON_MS / 4)
        println("DUNG-STOMP: $what at (${tile.x},${tile.y}) won't $action after $failures tries - mining a path instead")
    }

    private fun abandoned(tile: Tile): Boolean {
        val until = abandonedUntil[tile] ?: return false
        if (System.currentTimeMillis() < until) return true
        abandonedUntil.remove(tile)
        return false
    }

    private fun crystalFor(lodestoneName: String): Int = when {
        lodestoneName.startsWith("Blue") -> 15750
        lodestoneName.startsWith("Green") -> 15751
        lodestoneName.startsWith("Red") -> 15752
        else -> -1
    }

    // Mine the nearest Debris we can actually stand next to - clears the rubble one tile at a time from our
    // side inward until a pedestal/crystal opens up. Rubble that never breaks is written off the same way a
    // refused crystal is, because "Mine" is exposed whatever the floor rolled for its Mining requirement: an
    // account under that level gets a refusal per swing and would otherwise mine the same rock forever. False
    // once nothing minable is left, so the caller DPSes rather than idling - the phase can still turn.
    private suspend fun mineTowardDebris(script: Script): Boolean {
        val debris = getAllObjectsWithinRange(RANGE)
            .filter { it.name() == DEBRIS && it.hasOption(MINE) && !abandoned(it.tile) && adjacentReachable(it.tile) }
            .minByOrNull { it.tile.getDistance(localPlayer.tile) } ?: return false
        val tile = debris.tile
        val gone = { getAllObjectsWithinRange(RANGE).none { it.name() == DEBRIS && it.tile == tile } }
        if (debris.interact(MINE)) {
            script.waitUntilNotMoving()
            script.delayUntil(gaussian(6000L, 1500L)) { gone() }
        }
        if (gone()) refusals.remove(tile) else refuse(tile, "debris", "break")
        script.delay(340, 100)
        return true
    }

    // Stomp is invulnerable between phases, so the "Attack" never latches and re-firing it every loop is both a
    // metronome and a cancel on whatever gather step is walking. Fire once and wait for the swing to land.
    private suspend fun attackBoss(script: Script, boss: NPC) {
        if (boss.isCombatTarget) return
        val target = combatTarget
        if (target != null && target.currentHealth > 0) return
        if (boss.interact("Attack")) script.delayUntil(gaussian(2400L, 600L)) { boss.isCombatTarget }
        script.delay(440, 130)
    }

    private fun boss(): NPC? =
        allNpcsWithinRange(RANGE) { it.name() == BOSS }.minByOrNull { it.tile.getDistance(localPlayer.tile) }

    private fun reachable(tile: Tile): Boolean =
        routeToTile(localPlayer.tile, tile).let { it.success && !it.alternative }

    // A solid object (pedestal / debris) is "reachable" when we can stand on a walkable tile beside it.
    private fun adjacentReachable(t: Tile): Boolean =
        ADJACENT.any { (dx, dy) -> reachable(Tile.of(t.x + dx, t.y + dy, t.plane)) }
}
