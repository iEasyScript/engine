package com.projectx.script.impl.qb.bosses.barrows

import java.util.*

class Graph {
    private val nodes: MutableMap<String?, Node?> = HashMap<String?, Node?>()

    fun addNode(name: String?) {
        this.nodes[name] = Node(name)
    }

    fun resetGraph() {
        this.nodes.clear()
    }

    fun connectNodes(name1: String?, name2: String?, door: String?) {
        val node1 = this.nodes[name1]
        val node2 = this.nodes[name2]
        if (node1 != null && node2 != null) {
            node1.connect(node2, door)
            node2.connect(node1, door)
        }
    }

    fun findShortestPath(startName: String?, endName: String?): MutableList<String?> {
        if (this.nodes.containsKey(startName) && this.nodes.containsKey(endName)) {
            val startNode = this.nodes[startName]
            val endNode = this.nodes[endName]
            val toVisit: Queue<Node?> = ArrayDeque<Node?>()
            val cameFrom: MutableMap<Node?, Node?> = HashMap<Node?, Node?>()
            val visited: MutableSet<Node?> = HashSet<Node?>()
            toVisit.add(startNode)
            visited.add(startNode)
            cameFrom[startNode] = null as Node?

            while (!toVisit.isEmpty()) {
                val current = toVisit.poll() as Node
                if (current == endNode) {
                    return this.reconstructPath(cameFrom, endNode)
                }

                for (edge in current.edges) {
                    val neighbor: Node? = edge?.node
                    if (!visited.contains(neighbor)) {
                        visited.add(neighbor)
                        cameFrom[neighbor] = current
                        toVisit.add(neighbor)
                    }
                }
            }

            return mutableListOf()
        } else {
            return mutableListOf()
        }
    }

    private fun reconstructPath(cameFrom: MutableMap<Node?, Node?>, endNode: Node?): MutableList<String?> {
        val path: LinkedList<String?> = LinkedList<String?>()

        var current = endNode
        while (current != null) {
            path.addFirst(current.name)
            current = cameFrom[current]
        }

        return path
    }
}
