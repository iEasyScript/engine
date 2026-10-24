package com.projectx.traversal.nodes

import com.projectx.script.Script
import com.projectx.script.api.localPlayer
import com.projectx.traversal.TraversalNode
import com.projectx.webwalker.WebLink
import com.projectx.webwalker.WebLinkActions
import com.projectx.webwalker.WebLinkKind
import com.projectx.webwalker.WebLinks

/**
 * Dials [code] at the nearest fairy ring, or from the portable fairy ring when [portable], and waits to land at the
 * ring with that code. Rings and their landings come from the web walker's measured link set.
 */
class FairyRingNode @JvmOverloads constructor(
    private val code: String,
    private val portable: Boolean = false,
    private val customReached: (() -> Boolean)? = null,
) : TraversalNode() {
    private var nextClick = 0L
    private var dialled = 0

    private val landing: WebLink? by lazy {
        WebLinks.all.firstOrNull { it.kind == WebLinkKind.FAIRY_RING && it.portable && it.fairyCode == code.lowercase() }
    }

    private fun link(): WebLink? {
        if (portable) return landing
        val me = localPlayer.tile
        return WebLinks.all.asSequence()
            .filter { it.kind == WebLinkKind.FAIRY_RING && !it.portable && it.fairyCode == code.lowercase() && it.from.plane == me.plane }
            .minByOrNull { maxOf(Math.abs(it.from.centreX - me.x), Math.abs(it.from.centreY - me.y)) }
            ?.takeIf { maxOf(Math.abs(it.from.centreX - me.x), Math.abs(it.from.centreY - me.y)) <= it.searchRadius }
    }

    override suspend fun process(script: Script): Boolean {
        if (reached(script)) return false
        if (System.currentTimeMillis() < nextClick) return true
        val link = link()
        if (link == null) {
            failTraversal("no fairy ring to dial ${code.uppercase()} from")
            return true
        }
        if (++dialled > MAX_DIALS) {
            failTraversal("the fairy ring never took the player to ${code.uppercase()}")
            return true
        }
        val from = localPlayer.tile
        if (!WebLinkActions.start(script, link)) {
            nextClick = System.currentTimeMillis() + RETRY_MS
            return true
        }
        script.delayUntil(TELEPORT_TIMEOUT_MS) { traversalStopRequested() || reached(script) || localPlayer.tile.getLongestDelta(from) > LANDED_DISTANCE }
        script.delayUntil(SETTLE_MS) { !localPlayer.isAnimating }
        return true
    }

    override fun reached(script: Script): Boolean {
        customReached?.let { return it() }
        val to = landing?.to ?: return false
        val me = localPlayer.tile
        return me.plane == to.plane && me.x in to.minX - SLACK..to.maxX + SLACK && me.y in to.minY - SLACK..to.maxY + SLACK && !localPlayer.isAnimating
    }

    override fun copy(): TraversalNode = FairyRingNode(code, portable, customReached)

    override fun toString(): String = "[Fairy ring ${code.uppercase()}${if (portable) " (portable)" else ""}]"

    private companion object {
        private const val MAX_DIALS = 3
        private const val RETRY_MS = 1_200L
        private const val TELEPORT_TIMEOUT_MS = 15_000L
        private const val SETTLE_MS = 3_000L
        private const val LANDED_DISTANCE = 20
        private const val SLACK = 4
    }
}
