package com.projectx.traversal

import com.projectx.script.State
import com.projectx.script.StateMachineScript

open class Traversal<T : StateMachineScript<T>>(private val next: State<T>, finishedCondition: T.() -> Boolean, nodes: TraversalNodeList) : State<T>() {
    private val processor = TraversalProcessor(finishedCondition, nodes)

    override suspend fun T.checkNext(): State<T>? =
        if (!processor.process(this)) next else null

    override suspend fun T.stateLoop() { }

    companion object {
        fun <T : StateMachineScript<T>> traversal(next: State<T>, finishedCondition: T.() -> Boolean, init: TraversalBuilder<T>.() -> Unit): Traversal<T> {
            val builder = TraversalBuilder<T>()
            builder.init()
            return builder.build(next, finishedCondition)
        }
    }
}