package com.projectx.mcp.tools

import com.projectx.script.ConfigurableScript
import com.projectx.script.ScriptConfigStore
import com.projectx.script.ScriptExecutor
import com.projectx.script.ScriptMetadata
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * GENERIC (keep). Lifecycle control for discovered scripts: list them with their running/runtime state,
 * and start / stop / restart one by name. Lets an agent automate a script and recover it from a stuck
 * state (restart re-instantiates it, reapplying saved config and resetting its state machine) without a
 * client restart. Mutations run on the game tick (via safeJsonCall) so they never race the tick loop.
 */
object ScriptTools {

    fun register(server: Server): Int {
        registerListScripts(server)
        registerReloadScripts(server)
        registerControl(server, "start_script", "Instantiate a discovered script by name, apply its saved config, and activate it. No-op if already running.") { start(it) }
        registerControl(server, "stop_script", "Stop a running script by name (also stops its parallel/child scripts).") { stop(it) }
        registerControl(server, "restart_script", "Stop then immediately re-start a script by name - the way to recover a stuck script and pick up fresh config without reinjecting.") { restart(it) }
        return 5
    }

    fun findMeta(query: String): ScriptMetadata? {
        val q = query.trim()
        val all = ScriptExecutor.scripts.values
        return all.firstOrNull { it.name.equals(q, ignoreCase = true) }
            ?: all.firstOrNull { it.scriptClass.simpleName.equals(q, ignoreCase = true) }
            ?: all.firstOrNull { it.name.contains(q, ignoreCase = true) }
    }

    private fun start(meta: ScriptMetadata): String {
        if (ScriptExecutor.isScriptRunning(meta.scriptClass)) return "already_running"
        val instance = meta.scriptClass.getDeclaredConstructor().newInstance()
        if (instance is ConfigurableScript) ScriptConfigStore.applyTo(instance)
        ScriptExecutor.activate(instance)
        return "started"
    }

    private fun stop(meta: ScriptMetadata): String {
        val instance = ScriptExecutor.getScriptInstance(meta.scriptClass) ?: return "not_running"
        instance.stop()
        return "stopped"
    }

    private fun restart(meta: ScriptMetadata): String {
        ScriptExecutor.getScriptInstance(meta.scriptClass)?.stop()
        val instance = meta.scriptClass.getDeclaredConstructor().newInstance()
        if (instance is ConfigurableScript) ScriptConfigStore.applyTo(instance)
        ScriptExecutor.activate(instance)
        return "restarted"
    }

    private fun registerListScripts(server: Server) {
        server.addTool(
            name = "list_scripts",
            description = "Purpose: Enumerate every discovered @ScriptDescription script with its running state and runtime. || Returns: envelope + items[] {name, class, version, author, running, runtime}. || Inputs: none. || Use cases: 'Is the Dungeoneering Bot running?', 'What scripts can I start?'. || Related: start_script/stop_script/restart_script, script_status.",
            inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
        ) {
            safeJsonCall("list_scripts") { _ ->
                putJsonArray("items") {
                    for (meta in ScriptExecutor.scripts.values.sortedBy { it.name }) {
                        val running = ScriptExecutor.isScriptRunning(meta.scriptClass)
                        addJsonObject {
                            put("name", meta.name)
                            put("class", meta.scriptClass.name)
                            put("version", meta.version)
                            put("author", meta.author)
                            put("running", running)
                            put("runtime", ScriptExecutor.getScriptRuntimeFormatted(meta.scriptClass) ?: "")
                        }
                    }
                }
            }
        }
    }

    private fun registerReloadScripts(server: Server) {
        server.addTool(
            name = "reload_scripts",
            description = "Purpose: Re-scan ~/.projectx/scripts/*.jar (+ the project classpath) for @ScriptDescription scripts, picking up a freshly-built script jar WITHOUT reinjecting the engine - the MCP equivalent of the ScriptsTab 'Reload scripts' button. A running script keeps its OLD code until restarted, so the hot-reload cycle is stop_script -> reload_scripts -> start_script. || Returns: envelope + {count, items[] {name, running}}. || Inputs: none. || Related: stop_script, start_script, restart_script, list_scripts.",
            inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
        ) {
            safeJsonCall("reload_scripts") { _ ->
                ScriptExecutor.loadScripts()
                put("count", ScriptExecutor.scripts.size)
                putJsonArray("items") {
                    for (meta in ScriptExecutor.scripts.values.sortedBy { it.name }) {
                        addJsonObject {
                            put("name", meta.name)
                            put("running", ScriptExecutor.isScriptRunning(meta.scriptClass))
                        }
                    }
                }
            }
        }
    }

    private fun registerControl(server: Server, tool: String, purpose: String, action: (ScriptMetadata) -> String) {
        server.addTool(
            name = tool,
            description = "Purpose: $purpose || Returns: envelope + {name, class, running, runtime, result}. || Inputs: `name` (required) - the script's display name, simple class name, or a substring. || Related: list_scripts.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Script display name, simple class name, or a name substring.")
                    }
                },
                required = listOf("name"),
            ),
        ) { request ->
            safeJsonCall(tool) { _ ->
                val query = request.arguments?.get("name")?.jsonPrimitive?.content
                    ?: throw BadRequest("missing 'name'")
                val meta = findMeta(query) ?: throw BadRequest("no script matches '$query'")
                val result = action(meta)
                put("name", meta.name)
                put("class", meta.scriptClass.name)
                put("result", result)
                put("running", ScriptExecutor.isScriptRunning(meta.scriptClass))
                put("runtime", ScriptExecutor.getScriptRuntimeFormatted(meta.scriptClass) ?: "")
            }
        }
    }
}
