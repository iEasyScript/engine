package org.projectx.core.plugin

class Cascade<H> {
    private val buckets = HashMap<Any, MutableList<H>>()

    fun register(key: Any, handler: H) {
        buckets.getOrPut(key) { mutableListOf() }.add(handler)
    }

    fun resolve(ladder: List<Any>): List<H>? {
        for (key in ladder) {
            buckets[key]?.let { return it }
        }
        return null
    }

    fun resolveOne(ladder: List<Any>): H? = resolve(ladder)?.lastOrNull()

    fun <R : Any> query(ladder: List<Any>, map: (H) -> R?): R? {
        for (key in ladder) {
            val bucket = buckets[key] ?: continue
            for (handler in bucket) {
                map(handler)?.let { return it }
            }
        }
        return null
    }

    val size: Int get() = buckets.values.sumOf { it.size }

    fun isEmpty(): Boolean = buckets.isEmpty()
}
