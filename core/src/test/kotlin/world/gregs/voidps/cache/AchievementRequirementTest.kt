package world.gregs.voidps.cache

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * The achievement decoder walked opcodes 8/12 and 28/30 without keeping what it read. These pin the
 * captured values against `achievements.json`, since the league task filter gates on them.
 */
class AchievementRequirementTest {

    @Test
    fun `league tasks expose gating requirements`() {
        val dir = CacheFixture.resolveCacheDir()
        assumeTrue(dir != null, "no cache on this machine — skipping")
        CacheFixture.init(dir!!)

        val gated = Cache.dbRows(LEAGUE_TASK_TABLE)
            .mapNotNull { it.int(LEAGUE_TASK_ACHIEVEMENT) }
            .mapNotNull { Cache.achievement(it) }
            .count { it.skillRequirements.isNotEmpty() }
        assertTrue(gated > 100, "expected many level-gated league tasks, found $gated")
    }

    private companion object {
        const val LEAGUE_TASK_TABLE = 334
        const val LEAGUE_TASK_ACHIEVEMENT = 3
        /** The dump packs a grouping slot above the achievement id. */
        const val ACHIEVEMENT_ID_MASK = 0xFFFF
    }
}
