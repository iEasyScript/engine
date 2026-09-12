package com.projectx.traversal.nodes

import com.projectx.profiling.PlayerProfiles
import com.projectx.script.Script
import com.projectx.script.api.Lodestone
import com.projectx.script.api.localPlayer
import com.projectx.script.api.useLodestone
import com.projectx.traversal.TraversalNode
import com.projectx.util.gaussian

class LodestoneNode(
    private val lodestone: Lodestone,
    private val customReached: (() -> Boolean)? = null
) : TraversalNode() {

    private var nextClick: Long = 0
    private var teleportAttempted: Boolean = false

    override suspend fun process(script: Script): Boolean {
        if (System.currentTimeMillis() < nextClick) return true

        if (reached(script)) return false

        script.useLodestone(lodestone)
        nextClick = System.currentTimeMillis() + gaussian(PlayerProfiles.get().walkPathClickTime, PlayerProfiles.get().walkPathClickTime / 2)

        script.delay(100, 200)
        return true
    }

    override fun reached(script: Script): Boolean {
        return customReached?.invoke() ?:
            (localPlayer.tile.getDistance(lodestone.tile) <= 10 && !localPlayer.isAnimating)
    }

    override fun copy(): TraversalNode = LodestoneNode(lodestone, customReached)

    override fun toString(): String = "[Lodestone: ${lodestone.name}]"
} 