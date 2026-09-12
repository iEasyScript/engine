package world.gregs.voidps.cache

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * The table schema and the row payloads are stored separately, so they can only be trusted if they
 * agree: every column a row populates must carry exactly the tuple layout its table declares.
 */
class DbTableIntegrationTest {

    @Test
    fun `declared column types match every row that populates them`() {
        init()
        var comparedTables = 0
        var comparedColumns = 0
        val mismatches = mutableListOf<String>()

        for (tableId in 0..LAST_TABLE) {
            val table = Cache.dbTable(tableId) ?: continue
            if (table.columns.isEmpty()) continue
            val rows = Cache.dbRows(tableId)
            if (rows.isEmpty()) continue
            comparedTables++
            for (row in rows) {
                for ((index, values) in row.columns) {
                    val declared = table.types(index)
                    if (declared == null) {
                        mismatches += "table $tableId row ${row.id}: column $index undeclared"
                        continue
                    }
                    if (!declared.contentEquals(values.types)) {
                        mismatches += "table $tableId row ${row.id} column $index: " +
                            "${values.types.toList()} != ${declared.toList()}"
                    }
                    comparedColumns++
                }
            }
        }
        assertTrue(comparedTables > 100, "only $comparedTables tables had both a schema and rows")
        assertTrue(comparedColumns > 10_000, "only $comparedColumns columns compared")
        assertTrue(mismatches.isEmpty(), "${mismatches.size} mismatches, first: ${mismatches.take(5)}")
    }

    @Test
    fun `league task schema is fully declared`() {
        init()
        val table = requireNotNull(Cache.dbTable(LEAGUE_TASK_TABLE)) { "league_task table missing" }
        assertTrue(table.columns.keys.containsAll((0..8).toList()), "columns: ${table.columns.keys}")
        assertTrue(table.columns.values.all { it.types.isNotEmpty() }, "every column needs a type")
    }

    private fun init() {
        val dir = CacheFixture.resolveCacheDir()
        assumeTrue(dir != null, "no cache on this machine — skipping")
        CacheFixture.init(dir!!)
    }

    private companion object {
        const val LAST_TABLE = 383
        const val LEAGUE_TASK_TABLE = 334
    }
}
