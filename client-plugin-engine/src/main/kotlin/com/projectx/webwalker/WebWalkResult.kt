package com.projectx.webwalker

import com.projectx.script.api.Lodestone

enum class WebWalkStatus {
    /** The player is within the arrival distance of the destination. */
    ARRIVED,

    /** A route was found (from [WebWalker.findPath]; walking has not started). */
    PATH_FOUND,

    /** No route exists: the destination is walled off, or needs a teleport the walker does not know. */
    NO_PATH,

    /** The search gave up before reaching the destination; it is further than one walk can plan, or unreachable. */
    TOO_FAR,

    /** The player or the destination is inside an instance, where cache collision does not apply. */
    NOT_IN_WORLD,

    /** The destination is on a different plane from the player. */
    OTHER_FLOOR,

    /** The player stopped making progress along the route, even after it was planned again. */
    STUCK,

    /** The script stopped while walking. */
    STOPPED,
}

/**
 * The outcome of a web walk or path search; [path] is set whenever a route was planned, and [lodestone] names the
 * lodestone the walk teleported to on the way, if any.
 */
class WebWalkResult internal constructor(
    val status: WebWalkStatus,
    val message: String,
    val path: WebPath? = null,
    val lodestone: Lodestone? = null,
) {
    val isSuccess: Boolean get() = status == WebWalkStatus.ARRIVED || status == WebWalkStatus.PATH_FOUND

    internal fun via(lodestone: Lodestone?): WebWalkResult =
        if (lodestone == null || this.lodestone != null) this else WebWalkResult(status, message, path, lodestone)

    override fun toString(): String = if (lodestone == null) "$status: $message" else "$status: $message (via the ${lodestone.name} lodestone)"
}
