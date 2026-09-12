package com.projectx.game.nxt.interfaces

/**
 * Hand-off for inspect mode, which spans two threads: the overlay publishes what the cursor is over on the
 * render thread, and the mouse hook latches it on the game thread.
 *
 * The click is swallowed rather than forwarded. Interface components are buttons, tabs and close crosses —
 * letting the pick click through would fire whatever the user was trying to inspect, often closing it.
 */
object InterfacePick {
    @Volatile
    var active: Boolean = false

    /** What the cursor is currently over, republished every frame by the overlay while [active]. */
    @Volatile
    var hover: InspectedComponent? = null

    @Volatile
    private var latched: InspectedComponent? = null

    /**
     * Called from the mouse hook. Returns true when the click belongs to inspect mode and must not reach the
     * game — including a click on empty space, which cancels rather than leaking through.
     */
    fun consumeClick(): Boolean {
        if (!active) return false
        latched = hover
        active = false
        return true
    }

    /** Picks up a completed selection on the render thread. */
    fun drain(): InspectedComponent? {
        val result = latched
        latched = null
        return result
    }

    fun cancel() {
        active = false
        hover = null
        latched = null
    }
}
