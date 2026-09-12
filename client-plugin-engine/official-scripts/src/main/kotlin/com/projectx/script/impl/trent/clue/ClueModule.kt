package com.projectx.script.impl.trent.clue

import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope

/**
 * One Treasure Trails puzzle or step type. Sensing and solving happen in [update] on the script loop;
 * [render] only replays what the last update published, so the two never disagree within a frame.
 *
 * A module must work as a pure advisory: [nextAction] is consulted only when the operator has turned
 * auto-solve on, and returning null there is always a valid implementation.
 */
interface ClueModule {

    val name: String

    /** True when this module's step is on screen right now and worth sensing. */
    fun active(): Boolean

    fun update()

    /** Drop any published state; called as soon as the step goes away. */
    fun reset()

    fun render(scope: BackgroundDrawListScope)

    fun nextAction(): ClueAction? = null
}

/** A single click the module would make next. [fire] returns false when the click could not be issued. */
class ClueAction(val description: String, val fire: () -> Boolean)
