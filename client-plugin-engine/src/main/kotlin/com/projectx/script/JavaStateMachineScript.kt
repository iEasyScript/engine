package com.projectx.script

import com.projectx.script.event.Event

/**
 * The Java form of [StateMachineScript], for scripts with distinct phases. Each phase is a [JavaState], typically an
 * enum constant: every pass asks the current state [JavaState.checkNext] for the state to move to, and otherwise runs
 * its [JavaState.onLoop].
 *
 * ```java
 * public class Miner extends JavaStateMachineScript<Miner> {
 *     @Override public JavaState<Miner> getStartState() { return Phase.MINING; }
 * }
 *
 * enum Phase implements JavaState<Miner> {
 *     MINING {
 *         public JavaState<Miner> checkNext(Miner s) { return getInventory().isFull() ? BANKING : null; }
 *         public Wait onLoop(Miner s) { ... }
 *     },
 *     BANKING { ... }
 * }
 * ```
 */
abstract class JavaStateMachineScript<S : JavaStateMachineScript<S>> : JavaScript() {

    private var state: JavaState<S>? = null

    /** The state the script is in; the start state until the first move. */
    val currentState: JavaState<S>
        get() = state ?: getStartState().also { state = it }

    /** The state a newly started script begins in. */
    abstract fun getStartState(): JavaState<S>

    @Suppress("UNCHECKED_CAST")
    final override fun onLoop(): Wait {
        val script = this as S
        val current = currentState
        current.checkNext(script)?.let {
            state = it
            return Wait.ms(0)
        }
        return current.onLoop(script)
    }

    @Suppress("UNCHECKED_CAST")
    override fun onEvent(event: Event) {
        currentState.onEvent(this as S, event)
    }
}

/** One phase of a [JavaStateMachineScript]. */
interface JavaState<S : JavaStateMachineScript<S>> {
    /** The state to move to, or null to stay in this one. Moving ends the pass without running [onLoop]. */
    fun checkNext(script: S): JavaState<S>?

    /** This state's body: act, then return what to wait for. */
    fun onLoop(script: S): Wait

    /** Events that arrive while this is the current state. */
    fun onEvent(script: S, event: Event) {}
}
