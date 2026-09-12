package com.projectx.puzzle.combolock

/**
 * The generic in-game alphabetical combination lock (interface 79) — four A–Z dials, each with a
 * left arrow ◄ (backward) and right arrow ► (forward), plus an Enter button. Thin facade over
 * [CombinationLockLayout.GENERIC]; other layouts (e.g. [CombinationLockLayout.SEER]) use the class
 * directly.
 */
object CombinationLockInterface {
    private val layout = CombinationLockLayout.GENERIC

    const val INTERFACE_ID = 79
    const val ENTER_COMPONENT = 17
    const val DIALS = 4

    fun isOpen(): Boolean = layout.isOpen()

    fun readDials(): CharArray? = layout.readDials()

    fun arrowComponent(dial: Int, direction: TurnDirection): Int = layout.arrowComponent(dial, direction)
}
