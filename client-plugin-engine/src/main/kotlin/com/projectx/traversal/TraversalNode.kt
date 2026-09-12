package com.projectx.traversal

import com.projectx.script.Script

abstract class TraversalNode {
    var prev: TraversalNode? = null
    var next: TraversalNode? = null

    abstract suspend fun process(script: Script): Boolean
    abstract fun reached(script: Script): Boolean
    abstract fun copy(): TraversalNode
}