package world.gregs.voidps.cache

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import world.gregs.voidps.cache.type.decoder.NpcDecoder

/**
 * Loads NPC definitions from the live NXT cache on this machine (the dirs the engine bootstrap
 * resolves), proving the cache path resolves AND every multi-file NPC archive parses + decodes.
 * Regression guard for: the multi-file offset width (24-bit-in-an-int, not a 3-byte medium) and
 * the NPC opcode-121 model-translation layout. Skips on machines without the cache.
 *
 * The client's cache is opened READ-ONLY by [CacheFixture.load] — see
 * [world.gregs.voidps.cache.sqlite.LiveCacheGuard].
 */
class NpcLoadIntegrationTest {

    @Test
    fun `loads every NPC from the live cache`() {
        val dir = CacheFixture.resolveClientCacheDir() ?: CacheFixture.resolveCacheDir()
        assumeTrue(dir != null, "no live NXT cache on this machine — skipping")
        println("[npc-test] cache dir = $dir")
        val cache = CacheFixture.load(dir!!)

        val npcs = assertDoesNotThrow("decoding all NPCs must not throw") { NpcDecoder().load(cache) }
        assertTrue(npcs.size > 20000, "expected tens of thousands of NPC slots, got ${npcs.size}")

        val named = npcs.filter { !it.name.isNullOrBlank() && it.name != "null" }
        assertTrue(named.size > 5000, "expected many named NPCs, got ${named.size}")
        println("[npc-test] loaded ${npcs.size} npc slots, ${named.size} named")
        named.take(8).forEach {
            println("[npc-test]   ${it.id} = ${it.name} (models=${it.modelIds?.size ?: 0}, opts=${it.options.filterNotNull()})")
        }

        // The engine's hot path: the lazy per-id holder backing Cache.npc(id).
        val holder = Types(NpcDecoder(), cache)
        for (id in listOf(0, 1, 100, 1000, 7987, 13000, 20000, npcs.size - 1)) {
            assertDoesNotThrow("per-id NPC decode of $id must not throw") { holder.getOrNull(id) }
        }
        assertNotNull(holder.getOrNull(1000), "per-id NPC 1000 should decode")
    }
}
