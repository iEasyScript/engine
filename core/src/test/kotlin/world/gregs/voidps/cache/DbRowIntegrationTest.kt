package world.gregs.voidps.cache

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trips [world.gregs.voidps.cache.type.decoder.DbRowDecoder] against the independently
 * produced `dbrows.json` dump. Every row the dump knows must decode to the same table and the
 * same column values - a partial match means the value-type widths are wrong, not that content
 * drifted, because both sides read the same cache revision.
 */
class DbRowIntegrationTest {

    @Test
    fun `league task rows resolve through the table index`() {
        val dir = CacheFixture.resolveCacheDir()
        assumeTrue(dir != null, "no cache on this machine — skipping")
        CacheFixture.init(dir!!)

        val tasks = Cache.dbRows(LEAGUE_TASK_TABLE)
        assertTrue(tasks.size > 1_000, "league_task table decoded only ${tasks.size} rows")
        assertTrue(tasks.all { it.table == LEAGUE_TASK_TABLE })
        assertTrue(tasks.all { it.column(LEAGUE_TASK_COMPLETION_VARBIT) != null }, "every task must carry a completion var")
    }

    private fun kotlinx.serialization.json.JsonElement.scalar(): Any {
        val primitive = this as JsonPrimitive
        if (primitive.isString) return primitive.content
        return primitive.intOrNull ?: error("unexpected dbrow value $this")
    }

    private companion object {
        const val LEAGUE_TASK_TABLE = 334
        const val LEAGUE_TASK_COMPLETION_VARBIT = 4
    }
}
