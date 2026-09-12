package com.projectx.traversal

class TraversalNodeList(start: TraversalNode) {
    val head: TraversalNode = start
    var curr: TraversalNode = start

    fun add(node: TraversalNode) = apply {
        curr.next = node
        node.prev = curr
        curr = node
    }

    fun copy(): TraversalNodeList {
        return TraversalNodeList(head.copy()).also { newList ->
            var originalNode = head
            var currentNode = newList.head
            while (originalNode.next != null) {
                val nextCopy = originalNode.next?.copy()
                currentNode.next = nextCopy
                nextCopy?.prev = currentNode

                originalNode = originalNode.next!!
                currentNode = currentNode.next!!
            }
            var newCurrent = newList.head
            var origCurrent = head
            while (origCurrent !== curr && newCurrent.next != null) {
                newCurrent = newCurrent.next!!
                origCurrent = origCurrent.next!!
            }
            newList.curr = newCurrent
        }
    }
}