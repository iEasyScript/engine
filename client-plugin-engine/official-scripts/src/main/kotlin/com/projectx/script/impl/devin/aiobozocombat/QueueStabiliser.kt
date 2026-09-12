package com.projectx.script.impl.devin.aiobozocombat

/**
 * Keeps the highlighted next press readable while the rotation underneath it churns.
 *
 * The policy re-runs every poll against live state, so an ability going off cooldown or a point of
 * adrenaline landing can flip the top suggestion several times inside one game tick. Rendering that
 * raw makes the panel strobe and unreadable exactly when it matters most.
 *
 * A candidate must therefore hold for [HOLD_POLLS] consecutive polls before it takes the slot. The
 * exception is an [Urgency.INTERRUPT] step, which takes over immediately — a mechanic demanding a
 * stun cannot wait for the display to settle, and the abruptness there is the point.
 */
class QueueStabiliser {
    private var current: RotationStep? = null
    private var candidate: String? = null
    private var candidateHolds = 0

    fun stabilise(proposed: RotationStep?): RotationStep? {
        if (proposed == null) {
            current = null
            candidate = null
            candidateHolds = 0
            return null
        }

        if (proposed.urgency == Urgency.INTERRUPT) {
            if (current?.key != proposed.key) candidateHolds = 0
            current = proposed
            candidate = proposed.key
            return proposed
        }

        val shown = current
        if (shown == null || shown.key == proposed.key) {
            current = proposed
            candidate = proposed.key
            candidateHolds = 0
            return proposed
        }

        // A different ability wants the slot: make it prove it is not a one-poll flicker.
        if (candidate != proposed.key) {
            candidate = proposed.key
            candidateHolds = 1
            return shown
        }
        candidateHolds++
        if (candidateHolds < HOLD_POLLS) return shown

        current = proposed
        candidateHolds = 0
        return proposed
    }

    private companion object {
        /** Polls run at ~40ms, so this settles well inside a 600ms tick while killing sub-tick churn. */
        const val HOLD_POLLS = 4
    }
}
