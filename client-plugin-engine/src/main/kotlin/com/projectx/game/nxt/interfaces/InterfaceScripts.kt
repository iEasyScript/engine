package com.projectx.game.nxt.interfaces

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.ComponentHookSlot
import world.gregs.voidps.cache.type.decoder.InterfaceDecoder

/** One CS2 hook: the event that fires it, the script it runs, and the arguments baked into the interface. */
data class ScriptHook(
    val componentId: Int,
    val trigger: String,
    val scriptId: Int,
    val args: List<Any>,
)

/**
 * The CS2 scripts an interface is wired to, read from the cache rather than observed at runtime.
 *
 * Every component carries its hooks as data, keyed by the slot the client reads them from, so the wiring
 * can be reported without waiting for anything to fire. Decoding is per interface and cached, since only
 * the selected one is ever needed.
 */
object InterfaceScripts {
    private val decoder = InterfaceDecoder()
    private val byInterface = HashMap<Int, List<ScriptHook>>()

    fun forInterface(interfaceId: Int): List<ScriptHook> {
        if (interfaceId < 0) return emptyList()
        byInterface[interfaceId]?.let { return it }
        val hooks = runCatching { decode(interfaceId) }.getOrDefault(emptyList())
        byInterface[interfaceId] = hooks
        return hooks
    }

    fun forComponent(interfaceId: Int, componentId: Int): List<ScriptHook> =
        forInterface(interfaceId).filter { it.componentId == componentId }

    private fun decode(interfaceId: Int): List<ScriptHook> {
        val definitions = decoder.create(interfaceId + 1)
        decoder.load(definitions, Cache.get(), interfaceId)
        val components = definitions[interfaceId].components ?: return emptyList()

        val hooks = mutableListOf<ScriptHook>()
        components.forEachIndexed { componentId, component ->
            for ((slot, hook) in component.hooks ?: return@forEachIndexed) {
                hooks += ScriptHook(componentId, ComponentHookSlot.name(slot), hook.scriptId, hook.arguments)
            }
        }
        return hooks
    }
}
