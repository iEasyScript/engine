package com.projectx.traversal

import com.projectx.pathfinder.LiveWalkMap
import com.projectx.script.StateMachineScript
import com.projectx.script.api.localPlayer
import world.gregs.voidps.type.Tile

/**
 * Drives a node list one [process] call at a time. The finished condition stops it having arrived, the interrupt
 * condition stops it cut short, and a node may fail it or ask for a fresh route; [outcome] says which. While a node
 * blocks in a long wait, the conditions are still polled every few hundred milliseconds through [TraversalNode]'s stop signal, so
 * a stop is seen when it happens rather than when the wait ends.
 */
class TraversalProcessor<T : StateMachineScript<T>>(private val finishedCondition: T.() -> Boolean, nodes: TraversalNodeList) {
    private val nodes: TraversalNodeList = nodes.copy()
    private var curr: TraversalNode = this.nodes.head
    private var interruptCondition: (T.() -> Boolean)? = null
    private var noMovementTimeoutMillis = 0L

    constructor(
        finishedCondition: T.() -> Boolean,
        interruptCondition: (T.() -> Boolean)?,
        nodes: TraversalNodeList,
        noMovementTimeoutMillis: Long,
    ) : this(finishedCondition, nodes) {
        this.interruptCondition = interruptCondition
        this.noMovementTimeoutMillis = noMovementTimeoutMillis
    }

    /** Why the last [process] returned false, or [TraversalOutcome.RUNNING] while it returns true. */
    var outcome: TraversalOutcome = TraversalOutcome.RUNNING
        private set

    val wasInterrupted: Boolean get() = outcome == TraversalOutcome.INTERRUPTED

    val currentNode: TraversalNode get() = curr

    @Volatile
    private var latchedStop: TraversalOutcome? = null

    @Volatile
    private var lastScript: T? = null
    private var polledAt = 0L

    internal var playerTile: () -> Tile = { localPlayer.tile }

    internal var repathSupported = false
    private var repathRequested = false
    private var failReason: String? = null

    private var movementNode: TraversalNode? = null
    private var lastTile: Tile? = null
    private var lastMovedAt = 0L

    init {
        var node: TraversalNode? = this.nodes.head
        while (node != null) {
            node.stopSignal = ::stopRequested
            node.repathRequester = ::requestRepath
            node.failer = ::requestFail
            node = node.next
        }
    }

    fun setInterruptCondition(interruptCondition: (T.() -> Boolean)?) {
        this.interruptCondition = interruptCondition
    }

    /** True once the finished or interrupt condition has fired; checked every few hundred milliseconds while a node waits. */
    fun stopRequested(): Boolean {
        latchedStop?.let { return true }
        val script = lastScript ?: return false
        if (System.currentTimeMillis() - polledAt < CONDITION_POLL_MS) return false
        return pollConditions(script) != null
    }

    private fun pollConditions(script: T): TraversalOutcome? {
        latchedStop?.let { return it }
        polledAt = System.currentTimeMillis()
        latchedStop = when {
            script.finishedCondition() -> TraversalOutcome.FINISHED
            interruptCondition?.invoke(script) == true -> TraversalOutcome.INTERRUPTED
            else -> null
        }
        latchedStop?.let { println("[Traversal] $it on $curr") }
        return latchedStop
    }

    internal fun requestRepath(): Boolean {
        if (!repathSupported || latchedStop != null) return false
        if (!repathRequested) println("[Traversal] repath requested on $curr")
        repathRequested = true
        return true
    }

    internal fun requestFail(reason: String) {
        if (failReason == null) {
            failReason = reason
            println("[Traversal] failed on $curr: $reason")
        }
    }

    internal fun cancelRepathRequest() {
        repathRequested = false
        if (outcome == TraversalOutcome.REPATH_REQUESTED) outcome = TraversalOutcome.RUNNING
    }

    internal fun stopLatched(): Boolean = latchedStop != null

    suspend fun process(script: T): Boolean {
        lastScript = script
        pollConditions(script)?.let { return stop(it) }
        if (repathRequested) return stop(TraversalOutcome.REPATH_REQUESTED)
        if (failReason != null) return stop(TraversalOutcome.FAILED)
        val player = playerTile()
        if (timedOut(player)) {
            println("[Traversal] no movement for ${noMovementTimeoutMillis}ms on $curr")
            return stop(TraversalOutcome.TIMED_OUT)
        }
        LiveWalkMap.observe(player)
        advanceByProgress(player)

        var hops = 0
        while (curr.reached(script)) {
            val next = curr.next ?: return stop(TraversalOutcome.COMPLETED)
            curr = next
            outcome = TraversalOutcome.RUNNING
            if (++hops >= MAX_ADVANCE_PER_PASS) return true
        }

        val working = curr.process(script)
        if (repathRequested) return stop(TraversalOutcome.REPATH_REQUESTED)
        if (failReason != null) return stop(TraversalOutcome.FAILED)
        if (working) {
            outcome = TraversalOutcome.RUNNING
            return true
        }
        curr.next?.let {
            curr = it
            outcome = TraversalOutcome.RUNNING
            return true
        }
        return stop(if (curr.reached(script)) TraversalOutcome.COMPLETED else TraversalOutcome.STALLED)
    }

    private fun stop(reason: TraversalOutcome): Boolean {
        outcome = reason
        return false
    }

    private fun timedOut(player: Tile): Boolean {
        if (noMovementTimeoutMillis <= 0L) return false
        val now = System.currentTimeMillis()
        if (movementNode !== curr || lastTile != player) {
            movementNode = curr
            lastTile = player
            lastMovedAt = now
            return false
        }
        return now - lastMovedAt >= noMovementTimeoutMillis
    }

    /**
     * When the player already stands on a later node's tiles and every node up to it can be walked past now, jumps to
     * it: otherwise a walk whose end the player ran past - through an open door, after a long hop - clicks back to it.
     */
    private fun advanceByProgress(player: Tile) {
        val here = curr as? WalkEquivalent ?: return
        if (!here.passableNow()) return
        var target: TraversalNode? = null
        var node = curr.next
        var looked = 0
        while (node != null && looked++ < PROGRESS_LOOKAHEAD) {
            val walk = node as? WalkEquivalent ?: break
            if (onTiles(player, walk.routeTiles())) target = node
            if (!walk.passableNow()) break
            node = node.next
        }
        target?.let {
            println("[Traversal] $player is already on $it, skipping from $curr")
            curr = it
            outcome = TraversalOutcome.RUNNING
        }
    }

    private fun onTiles(player: Tile, tiles: List<Tile>): Boolean {
        val trusted = LiveWalkMap.trusted(player)
        return tiles.any { t ->
            t.plane == player.plane && if (trusted) {
                player.getLongestDelta(t) <= 2 && LiveWalkMap.distance(player, t, 2) >= 0
            } else {
                player.x == t.x && player.y == t.y
            }
        }
    }

    private companion object {
        private const val MAX_ADVANCE_PER_PASS = 8
        private const val PROGRESS_LOOKAHEAD = 8
        private const val CONDITION_POLL_MS = 300L
    }
}
