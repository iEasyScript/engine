package com.projectx.traversal

/** Why a traversal stopped advancing, so a script can tell arriving from being cut short. */
enum class TraversalOutcome {
    /** Still working. */
    RUNNING,

    /** The finished condition fired: the traversal is where it was going. */
    FINISHED,

    /** The interrupt condition fired: the traversal was cut short and did not arrive. */
    INTERRUPTED,

    /** The player did not move for the no-movement timeout while on one node. */
    TIMED_OUT,

    /** The last node was reached. */
    COMPLETED,

    /** The last node could not progress and was never reached. */
    STALLED,

    /** A node asked for the rest of the route to be planned again; handled inside [Traversal]. */
    REPATH_REQUESTED,

    /** A node found it can never complete, such as a locked door. */
    FAILED,
    ;

    val isStopped: Boolean get() = this != RUNNING

    val isArrival: Boolean get() = this == FINISHED || this == COMPLETED
}
