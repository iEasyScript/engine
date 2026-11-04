package com.projectx.traversal.nodes

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.Script
import com.projectx.script.api.interfaces
import com.projectx.script.api.localPlayer
import com.projectx.traversal.TraversalNode
import com.projectx.util.Area

class IFSlotNode(
    private val ifSlot: IFSlot,
    private val optionNum: Int = 1,
    private var destination: Area? = null,
    private var customReached: (() -> Boolean)? = null
) : TraversalNode() {
    private var nextClick: Long = 0

    override suspend fun process(script: Script): Boolean {
        if (System.currentTimeMillis() < nextClick) return true
        val success = if (optionNum != 0) ifSlot.click(optionNum) else ifSlot.dialogueContinue()
        if (!success) {
            // The menu may open a tick or two after the click before it; with nothing open after that, move on.
            script.delayUntil(MENU_OPEN_GRACE_MS) { traversalStopRequested() || interfaces.isOpen(ifSlot.interfaceId) }
            nextClick = System.currentTimeMillis() + NodeWaits.clickGap(MENU_RETRY_MS)
            return interfaces.isOpen(ifSlot.interfaceId)
        }
        // A paged dialogue keeps its interface open; the bound covers the server's reply.
        script.delayUntil(MENU_REPLY_MS) { traversalStopRequested() || reached(script) || !interfaces.isOpen(ifSlot.interfaceId) }
        nextClick = System.currentTimeMillis() + NodeWaits.clickGap()
        // The click is the step: move on, unless this is the last step and it still has somewhere to arrive.
        return next == null && (destination != null || customReached != null)
    }

    override fun reached(script: Script) = (customReached?.invoke() ?: destination?.inside(localPlayer.tile)) == true

    override fun copy(): TraversalNode = IFSlotNode(this.ifSlot, this.optionNum, this.destination, this.customReached)

    override fun toString() = "[${this.ifSlot}]"

    private companion object {
        private const val MENU_OPEN_GRACE_MS = 1_800L
        private const val MENU_REPLY_MS = 1_200L
        private const val MENU_RETRY_MS = 300L
    }
}