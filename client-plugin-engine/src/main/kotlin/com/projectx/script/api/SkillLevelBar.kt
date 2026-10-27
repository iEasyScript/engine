package com.projectx.script.api

import androidx.compose.runtime.Composable
import com.projectx.ui.compose.components.Hint
import com.projectx.ui.compose.components.ProgressBar
import com.projectx.util.getLevelForXp
import com.projectx.util.getXpForLevel
import org.projectx.core.game.skill.Skill

/** A skill's level and how far through it, read on the game thread (e.g. in `live`) for [SkillLevelBar] to draw. */
data class SkillLevelProgress(val level: Int = 0, val fraction: Float = 0f) {
    companion object {
        private const val MAX_LEVEL = 120

        @JvmStatic
        fun of(skill: Skill): SkillLevelProgress {
            val xp = getXp(skill)
            val level = getLevelForXp(xp)
            if (level >= MAX_LEVEL) return SkillLevelProgress(level, 1f)
            val floor = getXpForLevel(level)
            return SkillLevelProgress(level, (xp - floor).toFloat() / (getXpForLevel(level + 1) - floor))
        }
    }
}

/** "Level N" and a bar to the next level, or "Maxed Level" at 120; draws nothing before the level is known. */
@Composable
fun SkillLevelBar(progress: SkillLevelProgress) {
    when {
        progress.level <= 0 -> Unit
        progress.level >= 120 -> Hint("Maxed Level")
        else -> ProgressBar(progress.fraction, "Level ${progress.level} - ${(progress.fraction * 100).toInt()}%")
    }
}
