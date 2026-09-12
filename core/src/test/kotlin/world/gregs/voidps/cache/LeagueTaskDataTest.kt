package world.gregs.voidps.cache

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shape the league task board reads. League membership is a per-league boolean column, so
 * column N flags league N and a task can belong to more than one league at once.
 */
class LeagueTaskDataTest {

    @Test
    fun `tasks split across league membership columns`() {
        init()
        val tasks = Cache.dbRows(TASK_TABLE)
        assertTrue(tasks.size > 1_000, "only ${tasks.size} league tasks")
        for (league in 1..2) {
            val members = tasks.count { it.int(league) == 1 }
            assertTrue(members > 1_000, "league $league has only $members tasks")
        }
        assertTrue(tasks.all { it.int(COMPLETION_COLUMN) != null }, "every task needs a completion var")
        assertTrue(tasks.all { it.int(ACHIEVEMENT_COLUMN) != null }, "every task needs an achievement")
    }

    @Test
    fun `completion column packs a resolvable player varbit`() {
        init()
        val unresolved = Cache.dbRows(TASK_TABLE)
            .mapNotNull { it.int(COMPLETION_COLUMN) }
            .map { it and VARBIT_ID_MASK }
            .count { Cache.varbit(it) == null }
        assertEquals(0, unresolved, "$unresolved task completion varbits do not resolve")
    }

    @Test
    fun `regions resolve to named area sets`() {
        init()
        val regions = Cache.dbRows(LOCALITY_TABLE)
        assertEquals(REGION_COUNT, regions.size, "expected one row per region")
        val names = requireNotNull(Cache.enum(REGION_NAME_ENUM)) { "region name enum missing" }

        val areasByName = regions.mapIndexedNotNull { index, row ->
            val areaEnum = row.int(LOCALITY_AREA_ENUM_COLUMN) ?: return@mapIndexedNotNull null
            val areas = Cache.enum(areaEnum)?.values?.values?.filterIsInstance<Int>()?.toSet() ?: emptySet()
            names.getString(index + 1) to areas
        }.toMap()

        assertEquals(setOf(6), areasByName["Karamja"], "Karamja area set")
        assertEquals(setOf(36), areasByName["Havenhythe"], "Havenhythe area set")
        assertEquals(setOf(9, 37), areasByName["Morytania"], "Morytania area set")
        assertEquals(setOf(1, 2, 18, 19, 21, 27, 34, 35), areasByName["Misthalin"], "Misthalin area set")

        val overlapping = areasByName.values.flatten().groupBy { it }.filterValues { it.size > 1 }
        assertTrue(overlapping.isEmpty(), "areas claimed by several regions: ${overlapping.keys}")
    }

    private fun init() {
        val dir = CacheFixture.resolveCacheDir()
        assumeTrue(dir != null, "no cache on this machine — skipping")
        CacheFixture.init(dir!!)
    }

    private companion object {
        const val TASK_TABLE = 334
        const val LOCALITY_TABLE = 382
        const val LOCALITY_AREA_ENUM_COLUMN = 1
        const val REGION_NAME_ENUM = 9057
        const val REGION_COUNT = 11
        const val ACHIEVEMENT_COLUMN = 3
        const val COMPLETION_COLUMN = 4
        const val VARBIT_ID_MASK = 0xFFFFFF
    }
}
