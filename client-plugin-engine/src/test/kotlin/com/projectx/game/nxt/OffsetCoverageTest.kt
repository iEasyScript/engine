package com.projectx.game.nxt

import com.projectx.game.platform.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gate that keeps `OffsetUnavailableException` off the script API: every offset a script can
 * reach must resolve on every platform the engine ships a table for. The only permitted hole is a
 * field that genuinely does not exist in one platform's client, which has to say so in its
 * declaration — `by offset(Platform.LINUX)`.
 */
class OffsetCoverageTest {

    @Test
    fun `every bundled table carries every offset declared for its platform`() {
        val gaps = OffsetCoverage.gaps().filterNot { it.isEmpty }
        assertTrue(
            gaps.isEmpty(),
            gaps.joinToString("\n", prefix = "offset tables are missing declared values:\n") { gap ->
                "  ${gap.table}\n" + gap.missing.joinToString("\n") { "    $it" }
            } + "\n\nAdd the reverse engineered value to the table, delete the unused declaration, or " +
                "scope the property to the platforms whose client actually has the field."
        )
    }

    @Test
    fun `platform-scoped offsets name a platform the engine ships a table for`() {
        val bundled = OffsetTable.bundledTableNames().map { Platform.ofTableKey(it) }.toSet()
        val stranded = OffsetCoverage.declarations()
            .filter { it.isPlatformScoped && it.platforms.none { platform -> platform in bundled } }
            .map { it.key }
        assertEquals(emptyList(), stranded, "scoped to platforms with no bundled offset table")
    }

    @Test
    fun `no declaration excludes a platform whose table carries the value`() {
        val stale = OffsetTable.bundledTableNames().flatMap { table ->
            OffsetCoverage.staleScoping(table).map { "$table: $it" }
        }
        assertEquals(
            emptyList(),
            stale,
            "these tables carry a value the declaration puts out of reach — drop the portPending() " +
                "marker, or widen the platform scope so the client that has the field can use it",
        )
    }

    @Test
    fun `platform-scoped offsets are the exception, not a way to hide unported work`() {
        val scoped = OffsetCoverage.declarations().filter { it.isPlatformScoped }
        val declared = OffsetCoverage.declarations().size
        assertTrue(
            scoped.size * 4 < declared,
            "${scoped.size} of $declared offsets are platform-scoped — that is no longer an exception. " +
                "Port the fields instead of narrowing their declarations:\n" +
                scoped.joinToString("\n") { "  ${it.key} -> ${it.platforms.joinToString { p -> p.id }}" }
        )
    }
}
