package com.projectx.traversal

import com.projectx.script.State
import com.projectx.script.StateMachineScript

open class Traversal<T : StateMachineScript<T>>(private val next: State<T>, private val finishedCondition: T.() -> Boolean, nodes: TraversalNodeList) : State<T>() {
    private var processor = TraversalProcessor(finishedCondition, nodes)
    private var interruptCondition: (T.() -> Boolean)? = null
    private var interruptedNext: State<T>? = null
    private var noMovementTimeoutMillis = 0L
    private var repathSupplier: (() -> TraversalNodeList?)? = null
    private var repaths = 0

    /** A traversal that also ends, as [TraversalOutcome.TIMED_OUT], when the player stands still that long on one node. */
    constructor(next: State<T>, finishedCondition: T.() -> Boolean, nodes: TraversalNodeList, noMovementTimeoutMillis: Long) :
        this(next, finishedCondition, nodes) {
        this.noMovementTimeoutMillis = noMovementTimeoutMillis
        processor = TraversalProcessor(finishedCondition, null, nodes, noMovementTimeoutMillis)
    }

    /** Why the traversal stopped, or [TraversalOutcome.RUNNING] while it goes on. */
    val outcome: TraversalOutcome get() = processor.outcome

    val wasInterrupted: Boolean get() = processor.wasInterrupted

    val currentNode: TraversalNode get() = processor.currentNode

    /** Stops the traversal early, without arriving, once [condition] holds; it then moves on to [onInterrupt] if given. */
    @JvmOverloads
    fun interruptWhen(condition: T.() -> Boolean, onInterrupt: State<T>? = null): Traversal<T> = apply {
        interruptCondition = condition
        if (onInterrupt != null) interruptedNext = onInterrupt
        processor.setInterruptCondition(condition)
    }

    /**
     * Lets nodes ask for a fresh route to the same goal when the rest of theirs no longer holds, such as after a
     * locked shortcut. [supplier] builds the replacement from where the player stands; null keeps the current one.
     */
    fun repathWith(supplier: () -> TraversalNodeList?): Traversal<T> = apply {
        repathSupplier = supplier
        processor.repathSupported = true
    }

    override suspend fun T.checkNext(): State<T>? {
        if (processor.process(this)) return null
        if (processor.outcome == TraversalOutcome.REPATH_REQUESTED) {
            repath()
            return null
        }
        if (processor.wasInterrupted) interruptedNext?.let { return it }
        return next
    }

    private fun repath() {
        val retired = processor
        val supplier = repathSupplier
        if (retired.stopLatched() || supplier == null || ++repaths > MAX_REPATHS) {
            if (supplier == null || repaths > MAX_REPATHS) println("[Traversal] repath refused; continuing on the existing path")
            retired.cancelRepathRequest()
            if (repaths > MAX_REPATHS) retired.repathSupported = false
            return
        }
        val fresh = runCatching { supplier() }.onFailure { println("[Traversal] repath failed: $it") }.getOrNull()
        if (fresh == null || retired.stopLatched()) {
            retired.cancelRepathRequest()
            return
        }
        println("[Traversal] repath $repaths/$MAX_REPATHS")
        processor = TraversalProcessor(finishedCondition, interruptCondition, fresh, noMovementTimeoutMillis).also { it.repathSupported = true }
    }

    override suspend fun T.stateLoop() { }

    companion object {
        private const val MAX_REPATHS = 3

        fun <T : StateMachineScript<T>> traversal(next: State<T>, finishedCondition: T.() -> Boolean, init: TraversalBuilder<T>.() -> Unit): Traversal<T> {
            val builder = TraversalBuilder<T>()
            builder.init()
            return builder.build(next, finishedCondition)
        }
    }
}
