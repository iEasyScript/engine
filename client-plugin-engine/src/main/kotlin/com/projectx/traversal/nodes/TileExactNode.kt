package com.projectx.traversal.nodes

import world.gregs.voidps.type.Tile
import com.projectx.script.Script
import com.projectx.script.api.localPlayer
import com.projectx.script.api.walkTo
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.nodes.NodeWaits.awaitWalk

class TileExactNode(
    private val tile: Tile,
    private val minimap: Boolean = false,
    private var customReached: () -> Boolean
) : TraversalNode() {
    private var nextClick: Long = 0
    private var clickedAt = 0L

    override suspend fun process(script: Script): Boolean {
        if (System.currentTimeMillis() < nextClick) return true
        // Already walking to it from this node's last click: a second click on the same tile adds nothing.
        if (clickedAt != 0L && localPlayer.isMoving) {
            script.delayUntil(NodeWaits.WALK_LEG_TIMEOUT_MS) { traversalStopRequested() || reached(script) || !localPlayer.isMoving }
            return true
        }
        val success = walkTo(tile, minimap)
        if (success) {
            clickedAt = System.currentTimeMillis()
            script.awaitWalk(NodeWaits.WALK_START_TIMEOUT_MS, NodeWaits.WALK_LEG_TIMEOUT_MS) { traversalStopRequested() || reached(script) }
            nextClick = System.currentTimeMillis() + NodeWaits.clickGap()
            return true
        }
        return false
    }

    override fun reached(script: Script) = customReached.invoke()

    override fun copy(): TraversalNode = TileExactNode(this.tile, this.minimap, this.customReached)

    override fun toString() = "[${this.tile}, ${this.minimap}]"
}