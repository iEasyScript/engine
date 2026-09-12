package world.gregs.voidps.cache

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DormantDecoderIntegrationTest {

    @Test
    fun `newly wired dormant decoders decode from the cache`() {
        val dir = CacheFixture.resolveCacheDir()
        assumeTrue(dir != null, "no cache on this machine — skipping")
        CacheFixture.init(dir!!)

        assertTrue(Cache.overlays.isNotEmpty(), "overlay decoded empty")
        assertTrue(Cache.underlays.isNotEmpty(), "underlay decoded empty")
        assertTrue(Cache.mapScenes.isNotEmpty(), "map scene decoded empty")
        assertTrue(Cache.materials.isNotEmpty(), "material decoded empty")
        assertTrue(Cache.quickChatCats.isNotEmpty(), "quick chat category decoded empty")
        assertTrue(Cache.quickChatPhrases.isNotEmpty(), "quick chat phrase decoded empty")

        assertNotNull(Cache.overlay(0), "overlay 0 should decode")
        assertNotNull(Cache.underlay(0), "underlay 0 should decode")
        assertNotNull(Cache.mapScene(0), "map scene 0 should decode")
        assertNotNull(Cache.material(0), "material 0 should decode")
    }
}
