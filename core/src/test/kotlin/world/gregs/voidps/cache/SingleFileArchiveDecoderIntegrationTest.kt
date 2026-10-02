package world.gregs.voidps.cache

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import world.gregs.voidps.cache.sqlite.SQLiteCache
import world.gregs.voidps.cache.type.decoder.AnimStateMachineDecoder
import world.gregs.voidps.cache.type.decoder.CutsceneOverlayDecoder
import world.gregs.voidps.cache.type.decoder.HuffmanDecoder
import world.gregs.voidps.cache.type.decoder.ParticleSystemDecoder
import world.gregs.voidps.cache.type.decoder.StylesheetDecoder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * These indices hold one entity per archive in a single file 0, with archives numbered from 1. The
 * base mapping is `archive = id, file = id`, which resolves nothing at all here and reports no
 * failure while doing it, so the guard is a decoded count rather than an absence of errors.
 */
class SingleFileArchiveDecoderIntegrationTest {

    @Test
    fun `single-file-per-archive indices decode every archive they hold`() {
        val dir = CacheFixture.resolveClientCacheDir() ?: CacheFixture.resolveCacheDir()
        assumeTrue(dir != null, "no cache on this machine — skipping")
        val cache = SQLiteCache.load(dir!!, readOnly = true)
        val report = DecodeReport()

        val decoders = listOf(
            "HuffmanDecoder" to HuffmanDecoder(),
            "StylesheetDecoder" to StylesheetDecoder(),
            "ParticleSystemDecoder" to ParticleSystemDecoder(),
            "AnimStateMachineDecoder" to AnimStateMachineDecoder(),
            "CutsceneOverlayDecoder" to CutsceneOverlayDecoder(),
        )
        val archives = try {
            decoders.associate { (name, decoder) ->
                decoder.report = report
                decoder.load(cache)
                name to cache.lastArchiveId(decoder.index)
            }
        } finally {
            cache.close()
        }

        for ((name, decoder) in decoders) {
            assumeTrue(archives.getValue(name) > 0, "$name: index ${decoder.index} is absent from this cache")
            assertTrue(report.decoded(name) > 0, "$name decoded nothing from index ${decoder.index}")
            assertEquals(emptyList(), report.failures(name), "$name failed to decode")
        }
    }
}
