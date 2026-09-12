package world.gregs.voidps.cache

import kotlinx.serialization.json.Json
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

class AchievementTypeIntegrationTest {

    @Test
    fun `achievement 2 decodes the Cook's Assistant fields`() {
        val dir = CacheFixture.resolveCacheDir()
        assumeTrue(dir != null, "no cache on this machine — skipping")
        CacheFixture.init(dir!!)

        val cooks = Cache.achievement(2)!!
        assertEquals("Cook's Assistant", cooks.name)
        assertEquals("Complete this quest.", cooks.description)
        assertEquals(2379, cooks.graphicId)
        assertEquals(4746, cooks.category)
        assertEquals(4747, cooks.subcategory)
    }
}
