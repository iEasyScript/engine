package com.projectx.script.api

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.LayoutScope
import com.projectx.ui.backend.dsl.scopes.progressBar
import com.projectx.ui.backend.dsl.scopes.readout
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.scopes.valueRow
import com.projectx.ui.backend.flags.WindowFlags
import com.projectx.ui.compose.components.Hint
import com.projectx.ui.compose.components.ProgressBar
import com.projectx.ui.compose.components.Readout
import com.projectx.util.format
import com.projectx.util.formatElapsedTime
import com.projectx.util.getLevelForXp
import com.projectx.util.getXpForLevel
import org.projectx.core.game.skill.Skill
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Experience and progress for a skilling script's overlay: XP gained and per hour, levels gained, time to the next
 * level, runtime, and any counts the script reports with [add]. Create one per run. In a `ComposePanel`, read it on
 * the game thread with `live { tracker.snapshot() }` and show that with [SkillTrackerReadout]; from `render()`, call
 * [window], or [draw] inside a window of your own.
 *
 * Timing starts the first time it is drawn or read while logged in, so a tracker made before login still measures
 * from the right experience. With no [skills] given it shows every skill that has gained experience.
 */
class SkillTracker(vararg skills: Skill) {
    private val pinned = skills.toList()
    private val startXp = IntArray(Skill.entries.size)
    private val counts = ConcurrentHashMap<String, Int>()
    private val countOrder = CopyOnWriteArrayList<String>()
    private val tableId = "skill-tracker-${System.identityHashCode(this)}"

    @Volatile
    private var startMillis = 0L

    /** Starts measuring again from the player's current experience, clearing every count. */
    fun reset() {
        Skill.entries.forEach { startXp[it.ordinal] = getXp(it) }
        counts.clear()
        countOrder.clear()
        startMillis = System.currentTimeMillis()
    }

    private fun started(): Boolean {
        if (startMillis == 0L && isLoggedIn() && Skill.entries.any { getXp(it) > 0 }) reset()
        return startMillis != 0L
    }

    val runtimeMillis: Long get() = if (started()) System.currentTimeMillis() - startMillis else 0L

    /** The skills shown: the ones given to the constructor, or else every skill that has gained experience. */
    val skills: List<Skill> get() = pinned.ifEmpty { Skill.entries.filter { xpGained(it) > 0 } }

    fun xpGained(skill: Skill): Int = if (started()) getXp(skill) - startXp[skill.ordinal] else 0

    fun xpPerHour(skill: Skill): Int = perHour(xpGained(skill).toLong())

    fun levelsGained(skill: Skill): Int =
        if (started()) getLevelForXp(getXp(skill)) - getLevelForXp(startXp[skill.ordinal]) else 0

    /** Milliseconds to the next level at the current rate, or -1 with no rate yet or at level 120. */
    fun millisToLevel(skill: Skill): Long {
        val xp = getXp(skill)
        val level = getLevelForXp(xp)
        val rate = xpPerHour(skill)
        if (level >= MAX_LEVEL || rate <= 0) return -1
        return (getXpForLevel(level + 1) - xp) * MILLIS_PER_HOUR / rate
    }

    /** Adds [amount] to the count called [name], shown under the experience with its rate per hour. */
    @JvmOverloads
    fun add(name: String, amount: Int = 1) {
        counts.merge(name, amount, Int::plus)
        countOrder.addIfAbsent(name)
    }

    fun countOf(name: String): Int = counts[name] ?: 0

    fun countPerHour(name: String): Int = perHour(countOf(name).toLong())

    private fun perHour(amount: Long): Int {
        val elapsed = runtimeMillis
        return if (elapsed < 1000) 0 else (amount * MILLIS_PER_HOUR / elapsed).toInt()
    }

    /** Everything the tracker shows, read now. Call it on the game thread, e.g. from `live`, never inside `Panel()`. */
    fun snapshot(): SkillTrackerSnapshot {
        if (!started()) return SkillTrackerSnapshot()
        val shown = skills.map { skill ->
            val xp = getXp(skill)
            val level = getLevelForXp(xp)
            val fraction = if (level >= MAX_LEVEL) -1f else {
                val floor = getXpForLevel(level)
                (xp - floor).toFloat() / (getXpForLevel(level + 1) - floor)
            }
            SkillProgress(skill, skill.label, level, levelsGained(skill), xpGained(skill), xpPerHour(skill), millisToLevel(skill), fraction)
        }
        val reported = countOrder.map { CountProgress(it, countOf(it), countPerHour(it)) }
        return SkillTrackerSnapshot(true, runtimeMillis, shown, reported)
    }

    /** Draws the tracker into [scope]. */
    fun draw(scope: LayoutScope) {
        if (!started()) return scope.text("Waiting for login")
        val shown = skills
        scope.readout(tableId) {
            valueRow("Runtime", formatElapsedTime(runtimeMillis, 0))
            for (skill in shown) {
                val level = getLevelForXp(getXp(skill))
                val gainedLevels = levelsGained(skill)
                valueRow(skill.label, if (gainedLevels > 0) "Level $level (+$gainedLevels)" else "Level $level")
                valueRow("XP gained", "${format(xpGained(skill))} (${format(xpPerHour(skill))}/hr)")
                val toLevel = millisToLevel(skill)
                if (toLevel >= 0) valueRow("Level ${level + 1} in", formatElapsedTime(toLevel, 0))
            }
            for (name in countOrder) valueRow(name, "${format(countOf(name))} (${format(countPerHour(name))}/hr)")
        }
        if (shown.isEmpty()) scope.text("Waiting for experience")
        for (skill in shown) {
            val xp = getXp(skill)
            val level = getLevelForXp(xp)
            if (level >= MAX_LEVEL) continue
            val floor = getXpForLevel(level)
            val fraction = (xp - floor).toFloat() / (getXpForLevel(level + 1) - floor)
            scope.progressBar(fraction, overlay = "${skill.label} ${(fraction * 100).toInt()}% to ${level + 1}")
        }
    }

    /** Draws the tracker in its own auto-sized overlay window called [title]. */
    fun window(title: String) {
        ImGuiDsl.window(title, WindowFlags.AlwaysAutoResize) { draw(this) }
    }

    private val Skill.label get() = name.lowercase().replaceFirstChar { it.uppercase() }

    private companion object {
        const val MAX_LEVEL = 120
        const val MILLIS_PER_HOUR = 3_600_000L
    }
}

/** One reading of a [SkillTracker], safe to show from a Compose panel. */
data class SkillTrackerSnapshot(
    val started: Boolean = false,
    val runtimeMillis: Long = 0L,
    val skills: List<SkillProgress> = emptyList(),
    val counts: List<CountProgress> = emptyList(),
)

/** One skill's progress; [fractionToLevel] is how far through the current level, or -1 at level 120. */
data class SkillProgress(
    val skill: Skill,
    val label: String,
    val level: Int,
    val levelsGained: Int,
    val xpGained: Int,
    val xpPerHour: Int,
    val millisToLevel: Long,
    val fractionToLevel: Float,
)

data class CountProgress(val name: String, val count: Int, val perHour: Int)

/** The [SkillTracker] readout for a `ComposePanel`: runtime, levels, XP and its rate, counts, and level progress bars. */
@Composable
fun SkillTrackerReadout(snapshot: SkillTrackerSnapshot) {
    if (!snapshot.started) return Hint("Waiting for login")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val rows = buildList {
            add("Runtime" to formatElapsedTime(snapshot.runtimeMillis, 0))
            for (s in snapshot.skills) {
                add(s.label to if (s.levelsGained > 0) "Level ${s.level} (+${s.levelsGained})" else "Level ${s.level}")
                add("XP gained" to "${format(s.xpGained)} (${format(s.xpPerHour)}/hr)")
                if (s.millisToLevel >= 0) add("Level ${s.level + 1} in" to formatElapsedTime(s.millisToLevel, 0))
            }
            for (c in snapshot.counts) add(c.name to "${format(c.count)} (${format(c.perHour)}/hr)")
        }
        Readout(rows)
        if (snapshot.skills.isEmpty()) Hint("Waiting for experience")
        for (s in snapshot.skills) {
            if (s.fractionToLevel < 0f) continue
            ProgressBar(s.fractionToLevel, "${s.label} ${(s.fractionToLevel * 100).toInt()}% to ${s.level + 1}")
        }
    }
}
