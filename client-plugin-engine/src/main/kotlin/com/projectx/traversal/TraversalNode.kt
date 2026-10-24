package com.projectx.traversal

import com.projectx.script.Script

abstract class TraversalNode {
    var prev: TraversalNode? = null
    var next: TraversalNode? = null

    internal var stopSignal: () -> Boolean = { false }
    internal var repathRequester: () -> Boolean = { false }
    internal var failer: (String) -> Unit = {}

    abstract suspend fun process(script: Script): Boolean
    abstract fun reached(script: Script): Boolean
    abstract fun copy(): TraversalNode

    /** True once the traversal's finished or interrupt condition has fired; long waits in [process] should end on it. */
    protected open fun traversalStopRequested(): Boolean = stopSignal()

    /** Stops the traversal with [TraversalOutcome.FAILED]: this node can never complete. */
    protected open fun failTraversal(reason: String) = failer(reason)

    /**
     * Asks the traversal to replace the rest of its nodes with a fresh route to the same goal, from where the player
     * now is. False when the traversal cannot re-route, which a hand-built chain cannot.
     */
    protected open fun requestTraversalRepath(): Boolean = repathRequester()
}
