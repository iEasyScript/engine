package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.Script
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import com.projectx.util.gaussian

/**
 * Daemonheim grapple-bridge puzzle ("jammed bridge" winch variant / "tightrope" rope variant). Build a climbing
 * hook from two crates and grapple the gap:
 *   Meatcorn stash → Meatcorn (19665) --Spin wheel--> Meatcorn rope (19667)
 *   Crate          → Broken hook head (19663) --Smith anvil--> Climbing hook head (19664)
 *   rope + head    → Climbing hook (19668) --Grapple the bridge (from a distance)-->
 *   winch variant  → Operate the winch → the bridge extends and becomes plain walkable ground (solved forever)
 *   rope variant   → cross the now-bridged rope (both directions — recurring traversal, see TODO)
 *
 * Spin/Smith transform the held item directly (no make-menu). The bridge/chasm is intentionally out of walking
 * range, so Grapple is fired straight at the loc (SceneObject.interact fires the op without pathing). Ids span
 * every dungeon theme; the transform + item ids are shared by both variants.
 */
object JammedBridgePuzzle {
    private const val MEATCORN = 19665
    private const val ROPE = 19667
    private const val BROKEN_HEAD = 19663
    private const val HEAD = 19664
    private const val HOOK = 19668
    private const val RANGE = 20

    // "Grapple"-able gap, ungrappled — winch bridges + rope chasm-edges, every theme.
    private val RETRACTED = setOf(39912, 39920, 39929, 39931, 39948, 54237, 54238, 54239, 54240, 37265)
    private val GRAPPLED = setOf(39914, 39921, 39930, 39934, 39949, 54241, 54242, 54243, 54244, 37266)
    private val BRIDGED_ROPE = setOf(54245, 54246, 54247, 54248, 37267)
    private val GRAPPLE_CRATE = setOf(54565, 54567, 54645, 54569, 39956, 54249, 54251)

    private fun near(pred: (SceneObject) -> Boolean): SceneObject? = objectsInRoom(RANGE).firstOrNull(pred)

    fun present(): Boolean = objectsInRoom(RANGE).any { it.id in RETRACTED || it.id in GRAPPLED }

    suspend fun solve(script: Script) {
        // Already grappled → finish the crossing; never rebuild the hook (grappling consumes it).
        if (near { it.id in GRAPPLED } != null) {
            near { it.name() == "Winch" && it.hasOption("Operate") }?.let { winch ->
                approach(script, winch)
                if (winch.interact("Operate")) {
                    script.waitUntilNotMoving()
                    script.delayUntil(gaussian(3200L, 800L)) { objectsInRoom(RANGE).none { it.id in GRAPPLED } }
                }
                script.delay(620, 160)
                return
            }
            // Rope variant: no winch — cross the bridged rope. TODO validate live in a rope room + support
            // crossing both directions for normal traversal after it's solved.
            near { it.id in BRIDGED_ROPE }?.let { rope ->
                if (rope.interact(if (rope.hasOption("Cross")) "Cross" else "Grapple")) script.waitUntilNotMoving()
                script.delay(1850, 520)
            }
            return
        }

        val bridge = near { it.id in RETRACTED && it.hasOption("Grapple") } ?: return
        if (inventory.hasItem(HOOK)) {
            bridge.interact("Grapple")   // out of walking range by design — fire straight at it
            script.delayUntil(gaussian(3200L, 800L)) { !inventory.hasItem(HOOK) || objectsInRoom(RANGE).any { it.id in GRAPPLED } }
            script.delay(560, 150)
            return
        }

        if (!inventory.hasItem(HEAD) && !inventory.hasItem(BROKEN_HEAD)) return takeFrom(script, BROKEN_HEAD) { it.id in GRAPPLE_CRATE }
        if (!inventory.hasItem(ROPE) && !inventory.hasItem(MEATCORN)) return takeFrom(script, MEATCORN) { it.name() == "Meatcorn stash" }
        if (inventory.hasItem(BROKEN_HEAD)) return station(script, "Anvil", "Smith", HEAD)
        if (inventory.hasItem(MEATCORN)) return station(script, "Spinning wheel", "Spin", ROPE)
        if (inventory.hasItem(ROPE) && inventory.hasItem(HEAD)) {
            val rope = inventory.firstOrNull { it.id == ROPE }
            val head = inventory.firstOrNull { it.id == HEAD }
            if (rope != null && head != null) {
                rope.useOn(head)
                script.delayUntil(gaussian(3000L, 700L)) { inventory.hasItem(HOOK) }
            }
        }
    }

    private suspend fun takeFrom(script: Script, expect: Int, pred: (SceneObject) -> Boolean) {
        val crate = near { pred(it) && it.hasOption("Take-from") } ?: return
        approach(script, crate)
        if (crate.interact("Take-from"))
            script.delayUntil(gaussian(6000L, 1500L)) { inventory.hasItem(expect) }
        script.delay(560, 140)
    }

    private suspend fun station(script: Script, name: String, option: String, expect: Int) {
        val obj = near { it.name() == name && it.hasOption(option) } ?: return
        approach(script, obj)
        if (obj.interact(option))
            script.delayUntil(gaussian(4500L, 1200L)) { inventory.hasItem(expect) }
        script.delay(620, 160)
    }

    private suspend fun approach(script: Script, obj: SceneObject) {
        if (obj.tile.getDistance(localPlayer.tile) > 2) {
            walkTo(obj.tile, false)
            script.waitUntilNotMoving()
        }
    }
}
