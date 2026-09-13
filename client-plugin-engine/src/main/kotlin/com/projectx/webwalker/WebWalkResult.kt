package com.projectx.webwalker

enum class WebWalkStatus {
    /** The player is within the arrival distance of the destination. */
    ARRIVED,

    /** A route was found (from [WebWalker.findPath]; walking has not started). */
    PATH_FOUND,

    /** No walkable route exists on this plane: the destination is walled off, or needs stairs, a ladder or a teleport. */
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

/** The outcome of a web walk or path search; [path] is set whenever a route was planned. */
class WebWalkResult internal constructor(
    val status: WebWalkStatus,
    val message: String,
    val path: WebPath? = null,
) {
    val isSuccess: Boolean get() = status == WebWalkStatus.ARRIVED || status == WebWalkStatus.PATH_FOUND

    override fun toString(): String = "$status: $message"
}
