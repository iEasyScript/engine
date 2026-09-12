package com.projectx.puzzle.combolock

/** Which arrow to press on a dial. FORWARD = right arrow (A→B→C), BACKWARD = left arrow (A→Z→Y). */
enum class TurnDirection { FORWARD, BACKWARD }

/** The fewer-clicks way to turn one dial from its current letter to its target. */
data class DialMove(val direction: TurnDirection, val clicks: Int) {
    val solved: Boolean get() = clicks == 0
}

/** Pure A–Z combination-dial math, independent of any interface. */
object CombinationLock {
    private const val ALPHABET = 26

    /** Shortest turn from [current] to [target]; ties resolve to FORWARD. */
    fun move(current: Char, target: Char): DialMove {
        val cur = current.uppercaseChar() - 'A'
        val tgt = target.uppercaseChar() - 'A'
        val forward = ((tgt - cur) % ALPHABET + ALPHABET) % ALPHABET
        if (forward == 0) return DialMove(TurnDirection.FORWARD, 0)
        val backward = ALPHABET - forward
        return if (forward <= backward) DialMove(TurnDirection.FORWARD, forward)
        else DialMove(TurnDirection.BACKWARD, backward)
    }
}
