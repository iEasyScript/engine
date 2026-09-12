package world.gregs.voidps.cache

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VarDomainTypeIntegrationTest {

    @Test
    fun `var domain archives decode from the cache`() {
        val dir = CacheFixture.resolveCacheDir()
        assumeTrue(dir != null, "no cache on this machine — skipping")
        CacheFixture.init(dir!!)

        assertTrue(Cache.varPlayers.size > 1000, "expected thousands of varp slots, got ${Cache.varPlayers.size}")
        assertTrue(Cache.varNpcs.isNotEmpty(), "varnpc archive decoded empty")
        assertTrue(Cache.varGroups.isNotEmpty(), "vargroup archive decoded empty")

        assertNotNull(Cache.varPlayer(0), "varplayer 0 should decode")
        assertNotNull(Cache.varNpc(0), "varnpc 0 should decode")

        val typedVarps = Cache.varPlayers.count { it.type != -1 }
        assertTrue(typedVarps > 0, "no varp decoded a script type — VarType opcode 3 is wrong")
    }
}
