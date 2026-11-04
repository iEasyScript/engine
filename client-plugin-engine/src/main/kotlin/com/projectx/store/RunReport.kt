package com.projectx.store

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.projectx.core.game.skill.Skill
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Why a run ended, in the site's `SessionEnd` names. */
internal enum class RunEnd { MANUAL, QUEUE, SELF_STOP, ACCESS_ENDED, CLIENT_CLOSED }

/** One finished script run as the site's Run History records it. Never holds the in-game account name. */
internal data class RunReport(
    val internalName: String,
    val name: String,
    val version: String?,
    val store: Boolean,
    val startedAt: Long,
    val endedAt: Long,
    val end: RunEnd,
    val stopMessage: String?,
    val xpGained: Map<String, Int>,
    val died: Boolean,
) {
    val durationSec: Long get() = ((endedAt - startedAt).coerceAtLeast(0) + 500) / 1000

    val xpTotal: Long get() = xpGained.values.sumOf { it.toLong() }

    fun toJson(clientVersion: String): JsonObject = JsonObject().apply {
        addProperty("source", source(store))
        addProperty("clientVersion", clientVersion.take(40))
        addProperty("scriptInternalName", internalName)
        addProperty("scriptName", name)
        addProperty("scriptVersion", version)
        addProperty("startedAt", startedAt)
        addProperty("endedAt", endedAt)
        addProperty("durationSec", durationSec)
        addProperty("endReason", end.name)
        stopMessage?.let { addProperty("stopMessage", it) }
        add("xpGained", JsonObject().also { xp -> xpGained.forEach { (skill, gained) -> xp.addProperty(skill, gained) } })
        addProperty("xpTotal", xpTotal)
        addProperty("died", died)
    }

    companion object {
        const val MARK_WINDOW_MILLIS = 10_000L
        private const val STOP_MESSAGE_WINDOW_SECONDS = 10L
        private const val STOP_MESSAGE_MAX = 300

        private val STAMP = Regex("""^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})] ?""")
        private val STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        private val ENGINE_LINE = Regex("""^(FIRED SYNTHETIC|Refreshing AFK|\[(DoAction|Scripts|instances|store|Engine|pathfinder|Cache)]|\s)""")
        private val TAG = Regex("""^\[[^\]]{1,40}]:?\s*""")

        fun source(store: Boolean) = if (store) "store" else "local"

        /** Jagex's spelling for the site, which files OSRS's DEFENCE the same way. */
        fun siteSkillName(skill: Skill): String = if (skill == Skill.DEFENSE) "DEFENCE" else skill.name

        fun xpGained(start: Map<Skill, Int>, now: Map<Skill, Int>): Map<String, Int> =
            start.entries.mapNotNull { (skill, from) ->
                val gained = (now[skill] ?: return@mapNotNull null) - from
                if (gained > 0) siteSkillName(skill) to gained else null
            }.toMap()

        fun endFor(mark: RunEnd?, markedAt: Long, now: Long): RunEnd =
            if (mark != null && now - markedAt in 0 until MARK_WINDOW_MILLIS) mark else RunEnd.SELF_STOP

        /** Scripts print their reason and then stop, so the last non-engine line just before the stop explains it. */
        fun stopMessage(logLines: List<String>, stoppedAt: LocalDateTime, simpleName: String): String? {
            val since = stoppedAt.minusSeconds(STOP_MESSAGE_WINDOW_SECONDS)
            for (line in logLines.asReversed()) {
                val stamp = STAMP.find(line) ?: continue
                val at = runCatching { LocalDateTime.parse(stamp.groupValues[1], STAMP_FORMAT) }.getOrNull() ?: continue
                if (at.isBefore(since)) return null
                if (at.isAfter(stoppedAt.plusSeconds(1))) continue
                val body = line.substring(stamp.range.last + 1)
                if (body.isBlank() || ENGINE_LINE.containsMatchIn(body)) continue
                if (body == "$simpleName started." || body == "$simpleName stopped.") continue
                return body.replace(TAG, "").trim().take(STOP_MESSAGE_MAX).ifBlank { null }
            }
            return null
        }
    }
}

/** What the Control Panel shows of the game beside the scripts: no name, no position. */
internal data class GameSnapshot(val loggedIn: Boolean, val totalLevel: Int?, val hp: Int?, val prayer: Int?) {
    companion object {
        val LOGGED_OUT = GameSnapshot(false, null, null, null)
    }
}

/** A run still going, for the Control Panel's live view. [xpGained] is per site skill name since it started. */
internal data class LiveRun(
    val name: String,
    val internalName: String,
    val version: String?,
    val startedAt: Long,
    val xpGained: Map<String, Int>,
)

/** The `state` an instance heartbeat carries, in the shape the site's `parseState` reads. */
internal object LiveState {
    const val MAX_SCRIPTS = 10
    private val CONTROL = Regex("[\\p{Cntrl}]")

    fun build(clientVersion: String, game: GameSnapshot, runs: List<LiveRun>, now: Long): JsonObject = JsonObject().apply {
        addProperty("v", 1)
        addProperty("clientVersion", clientVersion.take(40))
        addProperty("loggedIn", game.loggedIn)
        if (game.loggedIn) {
            game.totalLevel?.let { addProperty("totalLevel", it) }
            game.hp?.let { addProperty("hp", it) }
            game.prayer?.let { addProperty("prayer", it) }
        }
        add("scripts", JsonArray().also { list -> runs.take(MAX_SCRIPTS).forEach { list.add(script(it, now)) } })
        add("break", JsonObject().apply {
            addProperty("active", false)
            add("until", JsonNull.INSTANCE)
        })
    }

    private fun script(run: LiveRun, now: Long) = JsonObject().apply {
        addProperty("name", clean(run.name, 120))
        addProperty("internalName", run.internalName.take(80))
        addProperty("version", run.version?.let { clean(it, 40) })
        addProperty("startedAt", run.startedAt)
        val total = run.xpGained.values.sumOf { it.coerceAtLeast(0).toLong() }
        addProperty("xp", total)
        addProperty("xpPerHour", perHour(total, now - run.startedAt))
        run.xpGained.filterValues { it > 0 }.maxByOrNull { it.value }?.let { addProperty("skill", it.key) }
    }

    /** Nothing for the first minute, when the rate is mostly noise. */
    fun perHour(xp: Long, elapsedMillis: Long): Long =
        if (elapsedMillis < Duration.ofMinutes(1).toMillis() || xp <= 0) 0 else Math.round(xp * 3_600_000.0 / elapsedMillis)

    private fun clean(text: String, max: Int) = text.replace(CONTROL, " ").trim().take(max)
}
