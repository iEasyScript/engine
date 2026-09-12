package world.gregs.voidps.cache

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import world.gregs.voidps.cache.sqlite.SQLiteCache
import world.gregs.voidps.cache.type.decoder.CursorDecoder
import world.gregs.voidps.cache.type.decoder.HeadbarDecoder
import world.gregs.voidps.cache.type.decoder.HitmarkDecoder
import world.gregs.voidps.cache.type.decoder.MapElementDecoder
import world.gregs.voidps.cache.type.decoder.MapSceneDecoder
import world.gregs.voidps.cache.type.decoder.AchievementDecoder
import world.gregs.voidps.cache.type.decoder.DbRowDecoder
import world.gregs.voidps.cache.type.decoder.DbTableDecoder
import world.gregs.voidps.cache.type.decoder.DbTableIndexDecoder
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Config archives with near-identical opcode tables still produce plausible-looking definitions when
 * the archive id is wrong. Trailing bytes and unhandled opcodes are what actually separate them.
 */
class DecodeTelemetryIntegrationTest {

    private fun assertClean(report: DecodeReport, vararg types: String) {
        for (type in types) {
            assertEquals(emptyList(), report.trailing(type), "$type left unconsumed bytes")
            assertEquals(emptyList(), report.failures(type), "$type failed to decode")
            assertEquals(emptyMap(), report.unknownOpcodeCounts(type), "$type hit unhandled opcodes")
        }
    }

    @Test
    fun `cursors and map scenes decode from their own archives`() {
        val dir = CacheFixture.resolveCacheDir()
        assumeTrue(dir != null, "no cache on this machine — skipping")
        val cache = SQLiteCache.load(dir!!, readOnly = true)
        val report = DecodeReport()

        val cursors = CursorDecoder().apply { this.report = report }
        val scenes = MapSceneDecoder().apply { this.report = report }
        cursors.load(cache)
        scenes.load(cache)
        cache.close()

        assertClean(report, "CursorDecoder", "MapSceneDecoder")
        assertEquals(185, report.decoded("CursorDecoder"))
        assertEquals(150, report.decoded("MapSceneDecoder"))
    }

    @Test
    fun `hitmarks headbars and map elements decode from their own archives`() {
        val dir = CacheFixture.resolveCacheDir()
        assumeTrue(dir != null, "no cache on this machine — skipping")
        val cache = SQLiteCache.load(dir!!, readOnly = true)
        val report = DecodeReport()

        HitmarkDecoder().apply { this.report = report }.load(cache)
        HeadbarDecoder().apply { this.report = report }.load(cache)
        val elements = MapElementDecoder().apply { this.report = report }.load(cache)
        cache.close()

        assertClean(report, "HitmarkDecoder", "HeadbarDecoder", "MapElementDecoder")
        assertEquals(527, report.decoded("HitmarkDecoder"))
        assertEquals(63, report.decoded("HeadbarDecoder"))
        assertEquals(5812, report.decoded("MapElementDecoder"))
        assertEquals("Draynor Manor", elements[100].name)
        assertEquals("Falador", elements[91].name)
    }

    /**
     * The db and achievement formats carry variable-width column values and length-prefixed
     * requirement records, so a wrong width does not throw - it silently reinterprets the rest of
     * the record. Full consumption is what catches that: every one of these records must end with
     * the reader exactly empty.
     */
    @Test
    fun `db and achievement records are consumed to the last byte`() {
        val dir = CacheFixture.resolveCacheDir()
        assumeTrue(dir != null, "no cache on this machine — skipping")
        val cache = SQLiteCache.load(dir!!, readOnly = true)
        val report = DecodeReport()

        AchievementDecoder().apply { this.report = report }.load(cache)
        DbRowDecoder().apply { this.report = report }.load(cache)
        DbTableIndexDecoder().apply { this.report = report }.load(cache)
        DbTableDecoder().apply { this.report = report }.load(cache)
        cache.close()

        assertClean(report, "AchievementDecoder", "DbRowDecoder", "DbTableIndexDecoder", "DbTableDecoder")
        assertTrue(report.decoded("AchievementDecoder") > 4_000, "achievements decoded ${report.decoded("AchievementDecoder")}")
        assertTrue(report.decoded("DbRowDecoder") > 15_000, "db rows decoded ${report.decoded("DbRowDecoder")}")
        assertTrue(report.decoded("DbTableIndexDecoder") > 200, "db table indexes decoded ${report.decoded("DbTableIndexDecoder")}")
        assertTrue(report.decoded("DbTableDecoder") > 300, "db tables decoded ${report.decoded("DbTableDecoder")}")
    }

    @Test
    fun `telemetry is off unless asked for`() {
        assertNull(CursorDecoder().report)
    }
}
