package com.projectx.store

import com.google.gson.JsonObject
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.ScriptExecutor
import com.projectx.script.api.healthCurrent
import com.projectx.script.api.healthMax
import com.projectx.script.api.prayerMax
import com.projectx.script.api.prayerPoints
import com.projectx.script.api.timeLoggedInAt
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.util.EngineBuild
import com.projectx.util.EngineLog
import org.projectx.core.game.skill.Skill
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reports each script run to the site's Run History and feeds runs still going to the Control Panel. Whoever stops a
 * script marks why first; an unmarked stop is the script's own. Game memory is read only on the game thread, in [tick].
 */
internal object RunReports {
    private const val GAME = "rs3"
    private const val SAMPLE_MILLIS = 1_000L
    private const val LOGIN_SETTLE_MILLIS = 15_000L
    private const val CHECKPOINT_SECONDS = 30L
    private const val RESEND_MINUTES = 5L
    private const val DEATH_MESSAGE = "you are dead"

    private class Run(
        val key: String,
        val internalName: String,
        val name: String,
        val version: String?,
        val store: Boolean,
        val startedAt: Long,
    ) {
        @Volatile var baseline: Map<Skill, Int>? = null
        @Volatile var died = false
    }

    private class Mark(val end: RunEnd, val at: Long)

    private val runs = ConcurrentHashMap<String, Run>()
    private val marks = ConcurrentHashMap<String, Mark>()

    @Volatile private var latestXp: Map<Skill, Int> = emptyMap()
    @Volatile private var game = GameSnapshot.LOGGED_OUT
    @Volatile private var closed = false
    @Volatile private var started = false
    private var lastSample = 0L

    private val pid = ProcessHandle.current().pid()
    private val store = PendingRunReports(Path.of(System.getProperty("user.home"), ".projectx", "run-reports"))
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
    private val sender by lazy { RunReportSender(store, ::post, Entitlements::token) }
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "projectx-run-reports").apply { isDaemon = true } }
    private val observer: (Event) -> Unit = ::onEvent

    @Synchronized
    fun start() {
        if (started) return
        started = true
        ScriptExecutor.addEventObserver(observer)
        worker.execute {
            runCatching {
                val now = System.currentTimeMillis()
                store.recoverStaleClaims(now)
                store.recoverOpenRuns(pid, now) { other -> ProcessHandle.of(other).map { it.isAlive }.orElse(false) }
            }
        }
        worker.scheduleWithFixedDelay({ runCatching { sender.sendPending() } }, 0, RESEND_MINUTES, TimeUnit.MINUTES)
        worker.scheduleWithFixedDelay({ runCatching { checkpoint() } }, CHECKPOINT_SECONDS, CHECKPOINT_SECONDS, TimeUnit.SECONDS)
    }

    fun started(script: Script) {
        if (closed) return
        val type = script.javaClass
        val description = type.getAnnotation(ScriptDescription::class.java) ?: return
        val internalName = Entitlements.nameOf(script)
        val run = Run(type.name, internalName, description.name, description.version, Entitlements.isPaid(internalName), System.currentTimeMillis())
        if (runs.putIfAbsent(type.name, run) == null) marks.remove(type.name)
    }

    fun ended(script: Script) {
        val run = runs.remove(script.javaClass.name) ?: return
        val now = System.currentTimeMillis()
        val mark = marks.remove(run.key)
        val end = RunReport.endFor(mark?.end, mark?.at ?: 0L, now)
        val message = if (end == RunEnd.SELF_STOP) RunReport.stopMessage(EngineLog.lines.toList(), localTime(now), script.javaClass.simpleName) else null
        val body = report(run, now, end, message).toJson(clientVersion())
        worker.execute { runCatching { sender.report(body) } }
    }

    fun markStop(scriptClass: Class<*>, end: RunEnd) {
        marks[scriptClass.name] = Mark(end, System.currentTimeMillis())
    }

    fun markAll(end: RunEnd) {
        val now = System.currentTimeMillis()
        runs.keys.forEach { marks[it] = Mark(end, now) }
    }

    /** The engine is unloading: every run still going ends with the client, kept on disk for the next load to send. */
    fun close() {
        if (closed) return
        closed = true
        ScriptExecutor.removeEventObserver(observer)
        val now = System.currentTimeMillis()
        val version = clientVersion()
        runs.values.forEach { run -> runCatching { store.add(report(run, now, RunEnd.CLIENT_CLOSED, null).toJson(version)) } }
        runs.clear()
        runCatching { store.checkpoint(pid, emptyList()) }
        worker.shutdownNow()
    }

    fun tick() {
        if (runs.isEmpty() && Instances.state != Instances.State.HELD) return
        val now = System.currentTimeMillis()
        if (now - lastSample < SAMPLE_MILLIS) return
        lastSample = now
        sample(now)
    }

    fun liveState(): JsonObject {
        val now = System.currentTimeMillis()
        val xp = latestXp
        val live = runs.values.sortedBy { it.startedAt }.map { run ->
            LiveRun(run.name, run.internalName, run.version, run.startedAt, run.baseline?.let { RunReport.xpGained(it, xp) }.orEmpty())
        }
        return LiveState.build(clientVersion(), game, live, now)
    }

    private fun sample(now: Long) {
        val client = Bootstrap.client
        if (client.mainState != MainState.LOGGED_IN) {
            game = GameSnapshot.LOGGED_OUT
            return
        }
        if (now - timeLoggedInAt < LOGIN_SETTLE_MILLIS) return
        val xp = EnumMap<Skill, Int>(Skill::class.java)
        var totalLevel = 0
        client.skills.forEachIndexed { index, stat ->
            xp[Skill.entries[index]] = stat.xp
            totalLevel += stat.realLevel
        }
        if (xp.values.sumOf { it.toLong() } <= 0) return
        latestXp = xp
        game = GameSnapshot(true, totalLevel.takeIf { it > 0 }, percent(healthCurrent.toDouble(), healthMax.toDouble()), percent(prayerPoints.toDouble(), prayerMax))
        runs.values.filter { it.baseline == null }.forEach { it.baseline = xp }
    }

    private fun percent(current: Double, max: Double): Int? =
        if (max > 0) (current * 100 / max).toInt().coerceIn(0, 100) else null

    private fun onEvent(event: Event) {
        if (event !is Chat || !event.cleanSenderName.isNullOrBlank()) return
        if (!event.message.contains(DEATH_MESSAGE, ignoreCase = true)) return
        for (run in runs.values) {
            if (run.died) continue
            run.died = true
            val body = JsonObject().apply {
                addProperty("kind", "death")
                addProperty("source", RunReport.source(run.store))
                addProperty("scriptInternalName", run.internalName)
                addProperty("scriptName", run.name)
            }
            worker.execute { runCatching { sender.death(body) } }
        }
    }

    private fun checkpoint() {
        if (closed) return
        val now = System.currentTimeMillis()
        val version = clientVersion()
        store.checkpoint(pid, runs.values.map { report(it, now, RunEnd.CLIENT_CLOSED, null).toJson(version) })
    }

    private fun report(run: Run, endedAt: Long, end: RunEnd, stopMessage: String?) = RunReport(
        internalName = run.internalName,
        name = run.name,
        version = run.version,
        store = run.store,
        startedAt = run.startedAt,
        endedAt = endedAt,
        end = end,
        stopMessage = stopMessage,
        xpGained = run.baseline?.let { RunReport.xpGained(it, latestXp) }.orEmpty(),
        died = run.died,
    )

    private fun clientVersion() = "RS3 engine ${EngineBuild.commit.take(12)}"

    private fun localTime(millis: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())

    private fun post(body: String, token: String): Int? = runCatching {
        val request = HttpRequest.newBuilder(URI.create("${Entitlements.storeBase()}/api/v1/sessions?game=$GAME"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }.getOrNull()
}
