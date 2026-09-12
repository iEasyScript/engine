package world.gregs.voidps.cache.cs2

/**
 * Groups of scripts that call one another in a cycle.
 *
 * A cycle is what the return-signature fixpoint cannot bootstrap: every member
 * needs an answer that only another member can give.
 */
fun stronglyConnected(nodes: Collection<Int>, calls: (Int) -> Set<Int>): List<Set<Int>> {
    val order = ArrayList<Int>(nodes.size)
    val seen = HashSet<Int>()
    val callers = HashMap<Int, MutableSet<Int>>()
    for (node in nodes) for (target in calls(node)) callers.getOrPut(target) { HashSet() }.add(node)

    val pending = ArrayDeque<Pair<Int, Iterator<Int>>>()
    for (start in nodes) {
        if (!seen.add(start)) continue
        pending.addLast(start to calls(start).iterator())
        while (pending.isNotEmpty()) {
            val (node, children) = pending.last()
            if (children.hasNext()) {
                val child = children.next()
                if (seen.add(child)) pending.addLast(child to calls(child).iterator())
            } else {
                pending.removeLast()
                order.add(node)
            }
        }
    }

    val assigned = HashSet<Int>()
    val components = ArrayList<Set<Int>>()
    for (node in order.asReversed()) {
        if (node in assigned) continue
        val component = HashSet<Int>()
        val queue = ArrayDeque(listOf(node))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!assigned.add(next)) continue
            component.add(next)
            callers[next].orEmpty().filterNot { it in assigned }.forEach { queue.addLast(it) }
        }
        components.add(component)
    }
    return components
}
