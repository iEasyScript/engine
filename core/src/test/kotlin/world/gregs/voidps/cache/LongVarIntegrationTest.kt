package world.gregs.voidps.cache

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import world.gregs.voidps.cache.type.data.VarDomainType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 64-bit vars are marked by [VarDomainType.LONG] and are the ones a 32-bit read silently
 * truncates. `league_localities_unlocked` is the worked example: its region bits live above
 * bit 31, so a truncated read reports unlocked regions as locked.
 */
class LongVarIntegrationTest {

    @Test
    fun `long varps are distinguishable from int varps`() {
        val dir = CacheFixture.resolveCacheDir()
        assumeTrue(dir != null, "no cache on this machine — skipping")
        CacheFixture.init(dir!!)

        val localities = requireNotNull(Cache.varPlayer(LEAGUE_LOCALITIES_UNLOCKED)) { "league_localities_unlocked missing" }
        assertTrue(localities.long, "league_localities_unlocked must decode as a long var")
        assertEquals(VarDomainType.LONG, localities.type)

        val activeLeague = requireNotNull(Cache.varPlayer(LEAGUE_ACTIVE)) { "league_active_player missing" }
        assertFalse(activeLeague.long, "league_active_player is a plain int var")
    }

    @Test
    fun `no varbit reads beyond the low word`() {
        val dir = CacheFixture.resolveCacheDir()
        assumeTrue(dir != null, "no cache on this machine — skipping")
        CacheFixture.init(dir!!)

        val beyond = Cache.varbits.filter { it.id >= 0 && it.endBit > 31 }
        assertTrue(beyond.isEmpty(), "varbits crossing bit 31 would need a long-aware mask: ${beyond.take(5).map { it.id }}")
    }

    private companion object {
        const val LEAGUE_LOCALITIES_UNLOCKED = 12327
        const val LEAGUE_ACTIVE = 12314
    }
}
