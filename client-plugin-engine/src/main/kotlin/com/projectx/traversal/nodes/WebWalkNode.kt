package com.projectx.traversal.nodes

import com.projectx.script.Script
import com.projectx.script.api.localPlayer
import com.projectx.traversal.TraversalNode
import com.projectx.webwalker.WebWalkStatus
import com.projectx.webwalker.WebWalker
import world.gregs.voidps.type.Tile

/** Web-walks to [destination]; hands on to the next node if the walk cannot get there. */
class WebWalkNode(
    private val destination: Tile,
    private val arriveDistance: Int = WebWalker.DEFAULT_ARRIVE_DISTANCE,
    private val customReached: (() -> Boolean)? = null,
) : TraversalNode() {

    override suspend fun process(script: Script): Boolean {
        val result = WebWalker.walk(script, destination, arriveDistance)
        if (result.status != WebWalkStatus.ARRIVED) println("[WebWalk] ${result}")
        return result.status == WebWalkStatus.ARRIVED
    }

    override fun reached(script: Script): Boolean =
        customReached?.invoke() ?: (localPlayer.tile.plane == destination.plane && localPlayer.tile.withinDistance(destination, arriveDistance))

    override fun copy(): TraversalNode = WebWalkNode(destination, arriveDistance, customReached)

    override fun toString(): String = "[WebWalk: $destination]"
}
