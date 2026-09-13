package com.projectx.script

import com.projectx.script.event.Event
import kotlinx.coroutines.CancellationException

abstract class StateMachineScript<T : StateMachineScript<T>> : Script() {
    protected var currentState: State<T>

    abstract fun getStartState(): State<T>

    init {
        currentState = getStartState()
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun loop() {
        try {
            (currentState.checkNextState(this as T))?.let {
                currentState = it
                return
            }
            currentState.loop(this)
        } catch (cancelled: CancellationException) {
            // shouldInterrupt cancelled the pass: not an error, and swallowing it would let the pass run on.
            throw cancelled
        } catch (exception: Throwable) {
            exception.printStackTrace()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun onEvent(event: Event) {
        currentState.onEvent(this as T, event)
    }
}

abstract class State<T : StateMachineScript<T>>() {
    abstract suspend fun T.checkNext(): State<T>?
    abstract suspend fun T.stateLoop()
    open fun T.onStateEvent(event: Event) { }

    suspend fun checkNextState(script: T): State<T>? = script.checkNext()
    suspend fun loop(script: T) = script.stateLoop()
    fun onEvent(script: T, event: Event) = script.onStateEvent(event)
}