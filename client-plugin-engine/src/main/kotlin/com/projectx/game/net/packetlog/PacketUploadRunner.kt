package com.projectx.game.net.packetlog

import com.projectx.util.Configuration
import java.io.File
import org.projectx.packetlog.agent.AgentStatus
import org.projectx.packetlog.agent.PacketAgent

/**
 * Starts the capture agent, which uploads on this machine's behalf.
 *
 * ⛔ The engine deliberately does NOT upload. It used to, on a background thread inside the client,
 * and that thread died with the client - taking with it the only code that could tell the server a
 * session had ended. Sessions stayed open on the server forever, and a capture was only ever
 * finished by the *next* injection.
 *
 * So this starts a separate process instead. It outlives the client however the client dies, sees
 * the death through the kernel releasing the session's file lock, and finishes the upload from
 * there. A hot reload must not disturb it either, which is why engine teardown does not stop it.
 */
object PacketUploadRunner {

    private const val LOG_NAME = "packetlog-agent.log"

    /** The agent's own lock makes a spawn idempotent; this only stops a per-frame caller wasting one. */
    private const val SPAWN_INTERVAL_MS = 30_000L

    private const val STATUS_TTL_MS = 500L

    @Volatile private var wanted = false
    @Volatile private var lastSpawnMs = 0L

    /**
     * What the running agent was spawned with. The agent takes these as argv, so a setting the user
     * changes mid-session cannot reach it - the old process has to finish and a new one take its
     * place, or the toggle silently does nothing until the next injection.
     */
    @Volatile private var spawnedWith: AgentSettings? = null
    @Volatile private var awaitingRestart = false
    @Volatile private var lastExitCheckMs = 0L

    private const val EXIT_CHECK_INTERVAL_MS = 500L

    private data class AgentSettings(val endpoint: String, val keepLocal: Boolean)

    private var cachedStatus: AgentStatus? = null
    private var cachedAtMs = 0L

    /** Whether this engine load wants captures shared - not a probe of the agent process. */
    val isRunning: Boolean get() = wanted

    /** A settings change is cycling the agent, so callers must keep driving [start] until it lands. */
    val isRestarting: Boolean get() = awaitingRestart

    val status: String
        get() {
            val reported = readStatus()
            return when {
                reported != null -> reported.summary()
                wanted -> "starting"
                else -> "stopped"
            }
        }

    /**
     * Ensures an agent is running. Safe to call from anywhere and as often as is convenient: the
     * agent holds a single-instance lock, so a redundant spawn exits immediately.
     */
    fun start() {
        wanted = true
        val desired = AgentSettings(
            Configuration.config.packetLogEndpoint,
            Configuration.config.packetLogKeepLocalAfterUpload,
        )
        if (awaitingRestart) {
            if (!agentExited()) return
            awaitingRestart = false
            lastSpawnMs = 0
        } else if (spawnedWith != null && spawnedWith != desired) {
            // Graceful, not a kill: a half-sent session is worth more finished than stopped promptly.
            runCatching { PacketAgent.stopFile().writeText("") }
            awaitingRestart = true
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastSpawnMs < SPAWN_INTERVAL_MS) return
        lastSpawnMs = now
        runCatching { PacketAgent.stopFile().delete() }
        spawn(desired)
        spawnedWith = desired
    }

    /**
     * The agent releases its single-instance lock on exit, so a claim that succeeds means it is gone.
     * Rate-limited because the probe opens and locks a file, and the caller drives this per frame for
     * the few seconds a handover takes.
     */
    private fun agentExited(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastExitCheckMs < EXIT_CHECK_INTERVAL_MS) return false
        lastExitCheckMs = now
        return runCatching { PacketAgent.claim()?.use { } != null }.getOrDefault(false)
    }

    /**
     * Asks the agent to finish what it holds and exit. It is not killed: a half-sent session is
     * worth more finished than stopped promptly.
     */
    fun stop() {
        wanted = false
        spawnedWith = null
        awaitingRestart = false
        runCatching { PacketAgent.stopFile().writeText("") }
    }

    private fun spawn(settings: AgentSettings) {
        val classpath = agentClasspath()
        if (classpath == null) {
            println("[packetlog] no engine jar to run the capture agent from; not sharing")
            return
        }
        val java = javaBinary()
        if (java == null) {
            println("[packetlog] no JDK found to run the capture agent; not sharing")
            return
        }
        val endpoint = settings.endpoint
        val command = buildList {
            detachPrefix()?.let { add(it) }
            add(java.absolutePath)
            add("-cp")
            add(classpath)
            add("org.projectx.packetlog.agent.PacketAgentKt")
            add("--endpoint")
            add(endpoint)
            if (settings.keepLocal) add("--keep-local")
        }
        runCatching {
            val log = File(File(System.getProperty("user.home"), ".projectx/logs"), LOG_NAME)
            log.parentFile.mkdirs()
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .redirectErrorStream(true)
                .start()
            println("[packetlog] capture agent started; streaming to $endpoint (log: ${log.absolutePath})")
        }.onFailure { println("[packetlog] could not start the capture agent: ${it.message}") }
    }

    /** Read on a timer rather than per frame: the UI asks for this every time it draws a row. */
    private fun readStatus(): AgentStatus? {
        val now = System.currentTimeMillis()
        if (now - cachedAtMs < STATUS_TTL_MS) return cachedStatus
        cachedAtMs = now
        cachedStatus = AgentStatus.read(PacketAgent.statusFile())
        return cachedStatus
    }

    /**
     * A new session leader, so a signal aimed at the client's process group cannot reach the agent.
     * Without it the agent dies alongside the very process it exists to outlive.
     */
    private fun detachPrefix(): String? =
        listOf("/usr/bin/setsid", "/bin/setsid").firstOrNull { File(it).canExecute() }

    private fun javaBinary(): File? =
        listOfNotNull(System.getenv("JAVA_HOME"), System.getProperty("java.home"))
            .asSequence()
            .flatMap { sequenceOf(File(it, "bin/java"), File(it, "bin/java.exe")) }
            .firstOrNull { it.canExecute() }

    /** The engine jar this class was loaded from - it already carries packetlog and its drivers. */
    private fun agentClasspath(): String? =
        runCatching {
            File(javaClass.protectionDomain.codeSource.location.toURI()).takeIf { it.isFile }?.absolutePath
        }.getOrNull()
}
