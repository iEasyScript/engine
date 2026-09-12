package com.projectx.mcp

import kotlinx.serialization.json.JsonObjectBuilder
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridge that lets a hot-reloaded script (living in a jar the engine can't reference at compile
 * time) publish debug snapshots the engine's MCP tools relay verbatim. A provider fills a
 * [JsonObjectBuilder] and runs on the game tick inside the relaying tool's `safeJsonCall`, so it
 * may read live game state. Registering by name overwrites, so a reload just replaces the provider.
 */
object ScriptDebug {
    private val providers = ConcurrentHashMap<String, JsonObjectBuilder.() -> Unit>()

    fun register(name: String, provider: JsonObjectBuilder.() -> Unit) { providers[name] = provider }
    fun unregister(name: String) { providers.remove(name) }
    fun provider(name: String): (JsonObjectBuilder.() -> Unit)? = providers[name]
    fun names(): Set<String> = providers.keys
}
