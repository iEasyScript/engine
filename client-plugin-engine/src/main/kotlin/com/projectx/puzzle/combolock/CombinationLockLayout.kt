package com.projectx.puzzle.combolock

import com.projectx.script.api.interfaces

/**
 * Component layout + live readout for an in-game alphabetical combination lock: N A–Z dials, each with a
 * backward (◄) and forward (►) arrow, plus a confirm button. The generic lock ([GENERIC], interface 79)
 * and Peer the Seer's door lock in The Fremennik Trials ([SEER], interface 298 `seer_combolock`) share
 * the same mechanic but expose different component ids, so the layout is data. Holds no puzzle-specific
 * logic — deriving the target word is the caller's job.
 */
class CombinationLockLayout(
    val interfaceId: Int,
    private val letterComponents: IntArray,
    private val backwardArrows: IntArray,
    private val forwardArrows: IntArray,
    val enterComponent: Int,
) {
    val dials: Int get() = letterComponents.size

    fun isOpen(): Boolean = runCatching { interfaces.isOpen(interfaceId) }.getOrDefault(false)

    /** Current letter on each dial (A–Z), or null if the interface can't be read cleanly this tick. */
    fun readDials(): CharArray? {
        val out = CharArray(dials)
        for (i in 0 until dials) {
            val text = runCatching { interfaces.getComponent(interfaceId, letterComponents[i])?.text }.getOrNull() ?: return null
            val c = text.trim().firstOrNull()?.uppercaseChar() ?: return null
            if (c !in 'A'..'Z') return null
            out[i] = c
        }
        return out
    }

    fun arrowComponent(dial: Int, direction: TurnDirection): Int =
        if (direction == TurnDirection.FORWARD) forwardArrows[dial] else backwardArrows[dial]

    companion object {
        val GENERIC = CombinationLockLayout(
            interfaceId = 79,
            letterComponents = intArrayOf(5, 6, 7, 8),
            backwardArrows = intArrayOf(9, 10, 11, 12),
            forwardArrows = intArrayOf(13, 14, 15, 16),
            enterComponent = 17,
        )
        val SEER = CombinationLockLayout(
            interfaceId = 298,
            letterComponents = intArrayOf(9, 10, 11, 12),
            backwardArrows = intArrayOf(13, 14, 15, 16),
            forwardArrows = intArrayOf(17, 18, 19, 20),
            enterComponent = 21,
        )
    }
}
