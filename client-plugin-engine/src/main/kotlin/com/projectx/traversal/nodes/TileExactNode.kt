package com.projectx.traversal.nodes

import world.gregs.voidps.type.Tile
import com.projectx.profiling.PlayerProfiles
import com.projectx.script.Script
import com.projectx.script.api.localPlayer
import com.projectx.script.api.walkTo
import com.projectx.traversal.TraversalNode
import com.projectx.util.gaussian

class TileExactNode(
    private val tile: Tile,
    private val minimap: Boolean = false,
    private var customReached: () -> Boolean
) : TraversalNode() {
    private var nextClick: Long = 0

    override suspend fun process(script: Script): Boolean {
        if (System.currentTimeMillis() < nextClick) return true
        val success = walkTo(tile, minimap)
        if (success) {
            script.delayUntil(15000) { !localPlayer.isMoving }
            nextClick = System.currentTimeMillis() + gaussian(PlayerProfiles.get().walkPathClickTime, PlayerProfiles.get().walkPathClickTime / 2)
            return true
        }
        return false
    }

    override fun reached(script: Script) = customReached.invoke()

    override fun copy(): TraversalNode = TileExactNode(this.tile, this.minimap, this.customReached)

    override fun toString() = "[${this.tile}, ${this.minimap}]"
}