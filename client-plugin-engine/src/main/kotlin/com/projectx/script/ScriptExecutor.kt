package com.projectx.script

import com.projectx.game.input.InputArbiter
import com.projectx.game.nxt.OffsetTable
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.pathfinder.WorldCollision
import com.projectx.profiling.PlayerProfiles
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.util.random
import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Keeps the client from being returned to the lobby for sitting still.
 *
 * Only the quiet stretches need it. A script that is clicking has already told the client someone is
 * there, and a keypress laid on top of that is a signal for nothing - so the last thing the engine did is
 * what decides, rather than a clock running regardless.
 */
private object StayloggedInTask: Script() {
    private var nextCheck = 0L

    override suspend fun loop() {
        val now = System.currentTimeMillis()
        if (now < nextCheck) return
        nextCheck = now + random(PlayerProfiles.get().afkLogoutMinMillis, PlayerProfiles.get().afkLogoutMaxMillis)

        // Anything the engine did counts, so a run that is doing its job never presses a key at all.
        if (InputArbiter.millisSinceActionInput < PlayerProfiles.get().afkLogoutMinMillis) return

        println("Refreshing AFK")
        clickKey(PlayerProfiles.get().afkLogoutRefreshKey)
    }
}

object ScriptExecutor {
    private val _scripts = mutableMapOf<String, ScriptMetadata>()
    val scripts: Map<String, ScriptMetadata> get() = _scripts

    private val _activeScripts = ConcurrentHashMap<String, Script>()
    val activeScripts: Collection<Script> get() = _activeScripts.values
    private val eventBus = ConcurrentLinkedDeque<Event>()

    private val eventObservers = CopyOnWriteArrayList<(Event) -> Unit>()
    fun addEventObserver(observer: (Event) -> Unit) { eventObservers.addIfAbsent(observer) }
    fun removeEventObserver(observer: (Event) -> Unit) { eventObservers.remove(observer) }
    var tick = 0L
    var playerListBuilt = false

    private val scriptStartTimes = ConcurrentHashMap<String, Long>()

    @Volatile private var pausingScript: Script? = null

    fun mainLogic() {
        tick++
        if (Bootstrap.client.mainState == MainState.LOGGED_IN) {
            when (tick % 10) {
                0L -> WorldCollision.checkLoad()
                1L -> readNpcIntoStructures()
                2L -> {
                    readSpotanimIntoStructures()
                    readActionBarAbilities()
                }
                3L -> {
                    playerListBuilt = readPlayerIntoStructures(clear = true)
                }
                else -> {
                    if (!playerListBuilt) {
                        playerListBuilt = readPlayerIntoStructures(clear = false)
                    }
                }
            }
            // The local player's slot can be empty for a tick while the client still reports LOGGED_IN; any script
            // reading localPlayer then would dereference a null entity.
            if (!playerListBuilt || !Bootstrap.client.loggedInPlayer.isSelfLoaded) {
                return
            }
        }

        try {
            if (_activeScripts.isNotEmpty()) {
                StayloggedInTask.tick()
            }

            while (eventBus.isNotEmpty()) {
                try {
                    val event = eventBus.poll()
                    _activeScripts.takeIf { it.isNotEmpty() }?.values?.forEach { it._processEvent(event) }
                    eventObservers.forEach { runCatching { it(event) } }
                } catch(e: Throwable) {
                    e.printStackTrace()
                }
            }
            // A pauser that stopped without resuming would otherwise hold the exclusive turn forever, and
            // since a stopped script ticks to a no-op nothing else would ever run again.
            val currentPausing = pausingScript?.takeIf { !it.stopped && _activeScripts.containsValue(it) }
            if (currentPausing == null) pausingScript = null
            if (currentPausing != null)
                currentPausing.tick()
            else
                _activeScripts.values.forEach { it.tick() }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun pushEvent(event: Event) = eventBus.addLast(event)

    fun activate(script: Script) {
        _activeScripts[script.javaClass.name] = script
        scriptStartTimes[script.javaClass.name] = System.currentTimeMillis()
    }

    fun deactivate(script: Script) {
        _activeScripts.remove(script.javaClass.name)
        scriptStartTimes.remove(script.javaClass.name)
        if (pausingScript == script) pausingScript = null
    }

    fun isScriptRunning(scriptClass: Class<*>): Boolean { return _activeScripts.containsKey(scriptClass.name) }

    /** When the running instance of [scriptClass] was started, in epoch millis, or null when it is not running. */
    fun startedAt(scriptClass: Class<*>): Long? = scriptStartTimes[scriptClass.name]

    // Name-based check for scripts the engine can't reference at compile time (e.g. ones living in a
    // hot-reloaded jar under ~/.projectx/scripts). Matches the simple or fully-qualified class name.
    fun isScriptRunningByName(name: String): Boolean =
        _activeScripts.keys.any { it == name || it.substringAfterLast('.') == name }

    fun getScriptInstance(clazz: Class<*>): Script? { return _activeScripts[clazz.name] }

    fun deactivate(scriptClass: Class<*>) {
        val instance = _activeScripts[scriptClass.name]
        if (instance != null)
            deactivate(instance)
    }

    fun stopAll() = _activeScripts.values.toList().forEach { it.stop() }

    /** Stops engine-internal scripts not tracked in [_activeScripts] (e.g. the AFK keepalive). */
    fun stopInternalTasks() {
        StayloggedInTask.stop()
    }

    fun getScriptRuntimeFormatted(scriptClass: Class<*>): String? {
        val start = scriptStartTimes[scriptClass.name] ?: return null
        val elapsedMillis = System.currentTimeMillis() - start
        val totalSeconds = elapsedMillis / 1000
        val hrs = totalSeconds / 3600
        val mins = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return "%02d:%02d:%02d".format(hrs, mins, secs)
    }

    fun pauseOthers(script: Script): Boolean {
        return if (pausingScript == null || pausingScript == script) {
            pausingScript = script
            true
        } else false
    }

    fun resumeOthers(script: Script): Boolean {
        return if (pausingScript == script) {
            pausingScript = null
            true
        } else false
    }

    fun isPaused(): Boolean = pausingScript != null

    private fun clearScripts() {
        _scripts.clear()
    }

    private fun registerScript(scriptClass: Class<out Script>) {
        val annotation = scriptClass.getAnnotation(ScriptDescription::class.java)
        if (annotation != null && annotation.visible) {
            val metadata = ScriptMetadata(
                scriptClass = scriptClass,
                name = annotation.name,
                version = annotation.version,
                author = annotation.author,
                description = annotation.description
            )
            _scripts[metadata.toString()] = metadata
        }
    }

    fun loadScripts() {
        println("🔍 ScriptExecutor.loadScripts() called")
        println("[Engine] ${OffsetTable.summary()}")

        fun isScriptClass(clazz: Class<*>): Class<out Script>? {
            return if (
                Script::class.java.isAssignableFrom(clazz) &&
                clazz.isAnnotationPresent(ScriptDescription::class.java) &&
                !Modifier.isAbstract(clazz.modifiers)
            ) {
                @Suppress("UNCHECKED_CAST")
                clazz as Class<out Script>
            } else
                null
        }

        val scriptsDir = getScriptsDirectoryFile()

        val scriptsDirectoryScanner = ClassScannerFactory.create(
            path = scriptsDir.absolutePath,
            type = ScannerType.BOTH
        )

        val currentProjectScanner = CurrentProjectScriptsScanner()

        val foundClassPaths = scriptsDirectoryScanner.scan() + currentProjectScanner.scan()
        val scripts = foundClassPaths
            .mapNotNull { isScriptClass(it) }
            .sortedBy { it.getAnnotation(ScriptDescription::class.java).name }

        clearScripts()
        scripts.forEach(::registerScript)
    }

    private fun getScriptsDirectoryFile(): File {
        val userHome = System.getProperty("user.home")
        val scriptsDir = File(userHome, ".projectx").resolve("scripts")
        if (!scriptsDir.exists()) {
            scriptsDir.mkdirs()
        }
        return scriptsDir
    }
}

data class ScriptMetadata(
    val scriptClass: Class<out Script>,
    val name: String,
    val version: String,
    val author: String,
    val description: String
) {
    override fun toString(): String = "$name v$version by $author"
}