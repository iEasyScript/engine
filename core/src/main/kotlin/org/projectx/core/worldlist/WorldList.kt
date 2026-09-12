package org.projectx.core.worldlist

/** Manages the world list with revision tracking for delta updates. */
class WorldList(val maxWorlds: Int = 300) {
    private val worlds = sortedMapOf<Int, World>()
    private var _revision = 10

    val revision: Int get() = _revision

    @Synchronized
    fun get(number: Int): World? = worlds[number]

    @Synchronized
    fun put(world: World) {
        worlds[world.number] = world
        _revision++
        updateIndices()
    }

    @Synchronized
    fun remove(number: Int): World? {
        val w = worlds.remove(number)
        if (w != null) {
            _revision++
            updateIndices()
        }
        return w
    }

    @Synchronized
    fun getWorldArray(): Array<World> = worlds.values.toTypedArray()

    @Synchronized
    fun getDefault(): World? = worlds.values.firstOrNull()

    @Synchronized
    fun size(): Int = worlds.size

    private fun updateIndices() {
        worlds.values.forEachIndexed { i, w -> w.index = i }
    }
}
