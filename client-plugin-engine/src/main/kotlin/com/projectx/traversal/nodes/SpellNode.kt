package com.projectx.traversal.nodes

import com.projectx.game.magic.CastResult
import com.projectx.game.magic.SpellTeleports
import com.projectx.script.Script
import com.projectx.script.api.TeleportSpell
import com.projectx.script.api.localPlayer
import com.projectx.traversal.TraversalNode
import com.projectx.util.Area

/**
 * Casts a teleport spell, a Spellbook Swap first when it is on another book, then waits to land in [destination] and
 * for the arrival animation. A spell that can no longer be cast, or that fails [MAX_ATTEMPTS] times, asks the
 * traversal to re-route.
 */
class SpellNode(
    private val spell: TeleportSpell,
    private val destination: Area? = null,
    private val customReached: (() -> Boolean)? = null,
) : TraversalNode() {
    private var attempts = 0
    private var nextTry = 0L

    override suspend fun process(script: Script): Boolean {
        if (reached(script)) return false
        if (System.currentTimeMillis() < nextTry) return true
        if (inDestination()) {
            script.delayUntil(ARRIVAL_ANIMATION_MS) { traversalStopRequested() || !localPlayer.isAnimating }
            return true
        }
        val result = SpellTeleports.cast(script, spell) { traversalStopRequested() }
        when (result) {
            CastResult.STOPPED -> return false
            CastResult.NOT_USABLE -> {
                giveUp("$spell can no longer be cast: ${SpellTeleports.check(spell).reason}")
                return false
            }
            CastResult.STARTED -> {
                script.delayUntil(LANDING_MS) { traversalStopRequested() || inDestination() }
                if (inDestination()) {
                    script.delayUntil(ARRIVAL_ANIMATION_MS) { traversalStopRequested() || !localPlayer.isAnimating }
                    return true
                }
                if (destination == null) return true
            }
            else -> {}
        }
        if (++attempts >= MAX_ATTEMPTS) {
            giveUp("$spell failed $attempts times (last: $result)")
            return false
        }
        println("[Spell] $spell: $result, retrying ($attempts/$MAX_ATTEMPTS)")
        nextTry = System.currentTimeMillis() + NodeWaits.clickGap(RETRY_PACING_MS)
        return true
    }

    private fun giveUp(why: String) {
        println("[Spell] $why; re-routing")
        if (!requestTraversalRepath()) failTraversal(why)
    }

    private fun inDestination(): Boolean = destination?.inside(localPlayer.tile) == true

    override fun reached(script: Script): Boolean {
        if (destination != null) return inDestination() && !localPlayer.isAnimating
        return customReached?.invoke() == true
    }

    override fun copy(): TraversalNode = SpellNode(spell, destination, customReached)

    override fun toString(): String = "[Spell: $spell]"

    private companion object {
        private const val MAX_ATTEMPTS = 3
        private const val LANDING_MS = 8_000L
        private const val ARRIVAL_ANIMATION_MS = 5_000L
        private const val RETRY_PACING_MS = 1_200L
    }
}
