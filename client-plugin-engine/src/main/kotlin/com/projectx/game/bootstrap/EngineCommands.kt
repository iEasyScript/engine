package com.projectx.game.bootstrap

import com.projectx.script.ConfigurableScript
import com.projectx.script.ScriptConfigStore
import com.projectx.script.ScriptExecutor
import com.projectx.script.ScriptMetadata
import com.projectx.script.Script
import com.projectx.script.api.hopToWorld

/**
 * The control-socket commands only the engine can answer, for a launcher driving an unattended
 * client: which scripts this client has, start one, stop it, hop a world.
 *
 * Separate from the MCP tools that do the same things because the MCP server is off unless someone
 * turns it on (see [Bootstrap]), and a scheduler cannot ask a user to turn anything on. The socket
 * is already there, already per-pid, and already how the launcher injects.
 *
 * One line in, one line out. A script is named by its `@ScriptDescription` name, which is what the
 * launcher lists to the user, and matching ignores case so a name typed into a rotation still finds
 * it.
 */
object EngineCommands {

    fun run(line: String): String {
        val verb = line.substringBefore(' ').uppercase()
        val argument = line.substringAfter(' ', "").trim()
        return runCatching {
            when (verb) {
                "SCRIPTS" -> scripts()
                "START" -> start(argument)
                "STOP" -> stop(argument)
                "RUNNING" -> running()
                "WORLD" -> world(argument)
                else -> "ERR unknown command: $verb"
            }
        }.getOrElse { "ERR ${it::class.java.simpleName}: ${it.message}" }
    }

    /** Every script this client has, as `name|running`, so a launcher can offer the real library. */
    private fun scripts(): String {
        val all = ScriptExecutor.scripts.values
        if (all.isEmpty()) return "OK 0"
        val rows = all.joinToString(";") { "${it.name}|${ScriptExecutor.isScriptRunning(it.scriptClass)}" }
        return "OK ${all.size} $rows"
    }

    private fun running(): String {
        val live = ScriptExecutor.scripts.values.filter { ScriptExecutor.isScriptRunning(it.scriptClass) }
        return "OK ${live.size}" + if (live.isEmpty()) "" else " " + live.joinToString(";") { it.name }
    }

    private fun start(name: String): String {
        if (name.isEmpty()) return "ERR START needs a script name"
        val meta = find(name) ?: return "ERR no script named $name"
        if (ScriptExecutor.isScriptRunning(meta.scriptClass)) return "OK already running ${meta.name}"
        val instance = meta.scriptClass.getDeclaredConstructor().newInstance()
        if (instance is ConfigurableScript) ScriptConfigStore.applyTo(instance)
        ScriptExecutor.activate(instance)
        return "OK started ${meta.name}"
    }

    private fun stop(name: String): String {
        if (name.isEmpty()) return "ERR STOP needs a script name"
        val meta = find(name) ?: return "ERR no script named $name"
        val instance = ScriptExecutor.getScriptInstance(meta.scriptClass) ?: return "OK not running ${meta.name}"
        instance.stop()
        return "OK stopped ${meta.name}"
    }

    /**
     * The hop drives the world switcher over several ticks, so it runs as a one-shot script and this
     * answers as soon as it is under way. A caller that needs to know it landed watches the world.
     */
    private fun world(argument: String): String {
        val world = argument.toIntOrNull() ?: return "ERR WORLD needs a world number"
        if (ScriptExecutor.isScriptRunning(WorldHop::class.java)) return "ERR already hopping"
        ScriptExecutor.activate(WorldHop(world))
        return "OK hopping to $world"
    }

    private class WorldHop(private val world: Int) : Script() {
        override suspend fun loop() {
            runCatching { hopToWorld(world) }.onFailure { println("[WORLD HOPPER] $it") }
            stop()
        }
    }

    private fun find(name: String): ScriptMetadata? {
        val all = ScriptExecutor.scripts.values
        return all.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: all.firstOrNull { it.scriptClass.simpleName.equals(name, ignoreCase = true) }
            ?: all.firstOrNull { it.name.contains(name, ignoreCase = true) }
    }
}
