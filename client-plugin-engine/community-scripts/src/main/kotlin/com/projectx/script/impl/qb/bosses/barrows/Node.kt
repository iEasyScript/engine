package com.projectx.script.impl.qb.bosses.barrows

class Node(var name: String?) {
    var edges: MutableList<Edge?> = ArrayList()

    fun connect(node: Node?, doorIdentifier: String?) {
        this.edges.add(Edge(node, doorIdentifier))
    }
}
