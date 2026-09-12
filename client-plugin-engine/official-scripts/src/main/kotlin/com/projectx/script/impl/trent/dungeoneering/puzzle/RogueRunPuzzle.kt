package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.script.Script
import com.projectx.script.api.findClosestReachableObject
import com.projectx.script.api.waitUntilNotMoving

/**
 * The Daemonheim "Suspicious grooves" (rogue-run) trap corridor: a grid of trap tiles seals the way on. Each
 * groove blocks (clipType 1) until INVESTIGATED — which harmlessly reveals it: the safe one disarms to a
 * walkable "Grooves" tile, a trap springs to a still-blocked "triggered" tile (no damage either way). So the
 * solve is simply to Investigate the reachable un-revealed grooves; the safe ones open a walkable path that
 * the collision picks up (via the loc-change clip hook) and the navigator then crosses. Investigating the
 * nearest REACHABLE groove each loop naturally walks us through the disarmed tiles into reach of the next
 * column, so no hand-rolled pathing or trap-stepping is needed.
 */
object RogueRunPuzzle {
    private const val NAME = "Suspicious grooves"
    private const val INVESTIGATE = "Investigate"
    private const val RANGE = 14

    // Only un-revealed grooves keep the "Investigate" option — disarmed ("Grooves") and triggered ones drop
    // it, so this both detects the puzzle and drives it to completion.
    fun present(): Boolean =
        findClosestReachableObject(RANGE) { it.name() == NAME && it.hasOption(INVESTIGATE) } != null

    suspend fun solve(script: Script) {
        val groove = findClosestReachableObject(RANGE) { it.name() == NAME && it.hasOption(INVESTIGATE) } ?: return
        if (groove.interact(INVESTIGATE)) script.waitUntilNotMoving()
        script.delay(520, 140)
    }
}
