package com.projectx.script.api

import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.LayoutScope
import com.projectx.ui.backend.dsl.scopes.progressBar
import com.projectx.ui.backend.dsl.scopes.readout
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.scopes.valueRow
import com.projectx.ui.backend.flags.WindowFlags
import com.projectx.util.format
import com.projectx.util.formatElapsedTime
import com.projectx.util.getLevelForXp
import com.projectx.util.getXpForLevel
import org.projectx.core.game.skill.Skill
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Experience and progress for a skilling script's overlay: XP gained and per hour, levels gained, time to the next
 * level, runtime, and any counts the script reports with [add]. Create one per run and call [window] from
 * `render()`, or [draw] inside a window of your own.
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
