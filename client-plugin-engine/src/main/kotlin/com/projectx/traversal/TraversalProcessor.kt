package com.projectx.traversal

import com.projectx.script.StateMachineScript

class TraversalProcessor<T : StateMachineScript<T>>(private val finishedCondition: T.() -> Boolean, nodes: TraversalNodeList) {
    private val nodes: TraversalNodeList = nodes.copy()
    private var curr: TraversalNode = this.nodes.head

    suspend fun process(script: T): Boolean = when {
        script.finishedCondition() -> false
        curr.reached(script) -> {
            if (curr.next != null) {
                curr = curr.next!!
                true
            } else
                false
        }
        curr.process(script) -> true
        curr.next != null -> {
            curr = curr.next!!
            true
        }
        else -> false
    }
}