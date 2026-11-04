package com.projectx.traversal.nodes

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.localPlayer
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.nodes.NodeWaits.awaitInteraction
import com.projectx.util.Area

class NpcNode private constructor(private val npcIdentifier: NpcIdentifier) : TraversalNode() {
    private var action: String? = null
    private var customReached: (() -> Boolean)? = null
    private var destination: Area? = null
    private var nextClick: Long = 0
    private var npcSearchRadius: Int = 20

    constructor(npc: NPC, action: String? = null, destination: Area? = null, npcSearchRadius: Int = 20, customReached: (() -> Boolean)? = null) : this(
        NpcIdentifier.NpcRef(npc)
    ) {
        this.action = action
        this.destination = destination
        this.customReached = customReached
        this.npcSearchRadius = npcSearchRadius
    }

    constructor(npcName: String, action: String? = null, destination: Area? = null, npcSearchRadius: Int = 20, customReached: (() -> Boolean)? = null) : this(
        NpcIdentifier.NameRef(npcName)
    ) {
        this.action = action
        this.destination = destination
        this.customReached = customReached
        this.npcSearchRadius = npcSearchRadius
    }

    constructor(npcId: Int, action: String? = null, destination: Area? = null, npcSearchRadius: Int = 20, customReached: (() -> Boolean)? = null) : this(
        NpcIdentifier.IdRef(npcId)
    ) {
        this.action = action
        this.destination = destination
        this.customReached = customReached
        this.npcSearchRadius = npcSearchRadius
    }

    private fun matches(npc: NPC): Boolean {
        val wanted = when (npcIdentifier) {
            is NpcIdentifier.NpcRef -> return npc == npcIdentifier.npc
            is NpcIdentifier.NameRef -> npc.name().contains(npcIdentifier.name, ignoreCase = true)
            is NpcIdentifier.IdRef -> npcIdentifier.id == -1 || npc.id == npcIdentifier.id || npc.typeId == npcIdentifier.id
        }
        return wanted && (action == null || npc.hasOption(action!!))
    }

    private fun getTarget(): NPC? = when (npcIdentifier) {
        is NpcIdentifier.NpcRef -> npcIdentifier.npc
        else -> LocalTargets.npcNear(allNpcsWithinRange(npcSearchRadius).filter(::matches), npcSearchRadius)
    }

    override suspend fun process(script: Script): Boolean {
        if (System.currentTimeMillis() < nextClick) return true
        val target = getTarget()
        if (target == null) {
            script.delay(100, 100)
            return true
        }
        val success = if (action != null) target.interact(action!!) else target.interact(0)
        if (success) {
            script.awaitInteraction { reached(script) || traversalStopRequested() }
            nextClick = System.currentTimeMillis() + NodeWaits.clickGap()
            return true
        }
        return false
    }

    override fun reached(script: Script): Boolean =
        (customReached?.invoke() ?: destination?.inside(localPlayer.tile)) == true

    override fun copy(): TraversalNode = NpcNode(this.npcIdentifier).also {
        it.action = this.action
        it.customReached = this.customReached
        it.destination = this.destination
        it.npcSearchRadius = this.npcSearchRadius
    }

    override fun toString() = "[${this.npcIdentifier} - $action]"
}

sealed class NpcIdentifier {
    data class NpcRef(val npc: NPC) : NpcIdentifier()
    data class NameRef(val name: String) : NpcIdentifier()
    data class IdRef(val id: Int) : NpcIdentifier()
}
