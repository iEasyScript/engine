package world.gregs.voidps.cache

import org.projectx.core.game.skill.Skill
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import world.gregs.voidps.gameval.Gameval
import kotlin.test.assertTrue

/**
 * What these prove, and what they do not.
 *
 * A decoder that reads a field at the wrong width does not throw; it reinterprets the rest of the
 * record and returns something plausible. `DecodeTelemetryIntegrationTest` catches the case where
 * that leaves bytes unconsumed. These catch the case where it does not, by requiring separately
 * decoded structures to agree about facts neither one can see on its own:
 *
 *  - the db row list (config archive) and the db table index (its own cache index) are decoded by
 *    different decoders from different records, yet each row names its table and each table names
 *    its rows. Garbage on either side breaks the agreement.
 *  - a column index stores its row ids twice, once per value and again as sorted ranges over one
 *    concatenated list. The two sub-encodings are read by different code and must yield one set.
 *  - the gameval tables (cache index 67) are Jagex's own id->name lists, shipped independently of
 *    the records they name. Every id they name must decode.
 *  - achievement requirement ids must resolve to real varbits, real achievements and real skills,
 *    and required levels must sit inside the cache's own stat caps. A shifted opcode walk yields
 *    ids that resolve to nothing.
 *
 * None of this pins a field to an externally supplied expected value, so none of it proves a
 * correctly-parsed field carries the meaning we think it does - that comes from the RE'd layouts.
 * What it does prove is that the layouts are being applied consistently, which is the failure mode
 * a regression actually produces.
 */
class DbIntegrityIntegrationTest {

    /**
     * `Types.getOrNull` hands back a default-constructed object for any in-range id, so a null
     * check only tests bounds. Presence means a field the decoder must have written.
     */
    private fun decodedRow(id: Int) = Cache.dbRow(id)?.takeIf { it.table != -1 }

    private fun decodedAchievement(id: Int) = Cache.achievement(id)?.takeIf { it.name != "null" }

    private fun decodedVarbit(id: Int) = Cache.varbit(id)?.takeIf { it.domain != null }

    private fun tableIndex(id: Int) = Cache.dbTableIndex(id)?.takeIf { it.rowIds.isNotEmpty() || it.columns.isNotEmpty() }

    @Test
    fun `every decoded row is listed by its own table index`() {
        CacheFixture.requireCache()

        var rows = 0
        val mismatches = mutableListOf<String>()
        for (id in 0 until Cache.dbTableIndexes.size + MAX_ROW_ID) {
            val row = decodedRow(id) ?: continue
            rows++
            val index = tableIndex(row.table) ?: continue
            if (!index.rowIds.contains(id)) mismatches += "row $id claims table ${row.table}, which does not list it"
        }
        assertTrue(rows > 15_000, "only $rows rows decoded — the scan found nothing to cross-check")
        assertTrue(mismatches.isEmpty(), "${mismatches.size} mismatches, first: ${mismatches.take(5)}")
    }

    @Test
    fun `column indexes agree with themselves and with their table`() {
        CacheFixture.requireCache()

        var columns = 0
        var ranges = 0
        val mismatches = mutableListOf<String>()
        for (id in Cache.dbTableIndexes.indices) {
            val index = tableIndex(id) ?: continue
            val tableRows = index.rowIds.toSet()
            for ((column, data) in index.columns) {
                columns++
                for (rowId in data.rowIds) {
                    if (rowId !in tableRows) mismatches += "table $id column $column indexes row $rowId, absent from the table"
                }
                for (range in data.ranges) {
                    ranges++
                    if (range.values.size != range.starts.size || range.values.size != range.ends.size) {
                        mismatches += "table $id column $column range has ${range.values.size} values but ${range.starts.size}/${range.ends.size} bounds"
                        continue
                    }
                    val byValue = data.fields.filter { it.type == range.type }
                        .flatMap { it.values }
                        .flatMap { it.rowIds.asSequence() }
                        .toSet()
                    if (byValue.isNotEmpty() && byValue != range.rowIds.toSet()) {
                        mismatches += "table $id column $column: per-value and range row sets differ"
                    }
                }
            }
        }
        assertTrue(columns > 500, "only $columns column indexes scanned")
        assertTrue(ranges > 100, "only $ranges range encodings scanned")
        assertTrue(mismatches.isEmpty(), "${mismatches.size} mismatches, first: ${mismatches.take(5)}")
    }

    /**
     * The gameval tables and the records they name ship as separate cache indexes and can drift by
     * a record or two across a content update, so this allows a small shortfall. A decoder that
     * regressed does not drift — it stops resolving in bulk.
     */
    @Test
    fun `gameval named ids decode`() {
        CacheFixture.requireCache()
        assumeTrue(Gameval.has(Gameval.DBROW), "no gameval tables on this machine — skipping")

        for ((type, resolves) in listOf<Pair<String, (Int) -> Boolean>>(
            Gameval.DBROW to { id -> decodedRow(id) != null },
            Gameval.DBTABLE to { id -> tableIndex(id) != null },
            ACHIEVEMENT to { id -> decodedAchievement(id) != null },
        )) {
            val named = Gameval.entries(type).keys
            assumeTrue(named.isNotEmpty(), "no gameval $type table — skipping")
            val resolved = named.count(resolves)
            assertTrue(
                resolved >= named.size - named.size / 100,
                "$type: only $resolved of ${named.size} gameval-named ids decode",
            )
        }
    }

    @Test
    fun `achievement requirements reference content that exists`() {
        CacheFixture.requireCache()

        val statCaps = runCatching { Cache.enum(Gameval.requireId(Gameval.ENUM, STAT_CAP_ENUM)) }.getOrNull()
        var progressVars = 0
        var links = 0
        var skills = 0
        val mismatches = mutableListOf<String>()
        for (id in Cache.achievements.indices) {
            val achievement = decodedAchievement(id) ?: continue
            for (requirement in achievement.progressRequirements) {
                for (varbit in requirement.vars) {
                    progressVars++
                    if (decodedVarbit(varbit) == null) mismatches += "achievement $id tracks varbit $varbit, which does not decode"
                }
            }
            for (other in achievement.previousAchievements + achievement.subAchievements) {
                links++
                if (decodedAchievement(other) == null) mismatches += "achievement $id links achievement $other, which does not decode"
            }
            for (requirement in achievement.skillRequirements) {
                for (skill in requirement.skills) {
                    skills++
                    if (Skill.byId(skill) == null) {
                        mismatches += "achievement $id requires skill $skill, which is not a skill"
                        continue
                    }
                    val cap = statCaps?.let { runCatching { it.getInt(skill) }.getOrNull() } ?: DEFAULT_STAT_CAP
                    if (requirement.level !in 1..cap) {
                        mismatches += "achievement $id requires ${Skill.byId(skill)} ${requirement.level}, outside 1..$cap"
                    }
                }
            }
        }
        assertTrue(progressVars > 5_000, "only $progressVars progress vars decoded")
        assertTrue(links > 2_000, "only $links achievement links decoded")
        assertTrue(skills > 1_000, "only $skills skill requirements decoded")
        assertTrue(mismatches.isEmpty(), "${mismatches.size} mismatches, first: ${mismatches.take(5)}")
    }

    private companion object {
        /** Row ids run well past the table count; the config archive is scanned, not the index. */
        const val MAX_ROW_ID = 25_000
        const val ACHIEVEMENT = "achievement"
        const val STAT_CAP_ENUM = "stat_to_statcap"
        const val DEFAULT_STAT_CAP = 99
    }
}
