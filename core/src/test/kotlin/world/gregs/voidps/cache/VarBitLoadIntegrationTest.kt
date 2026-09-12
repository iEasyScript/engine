package world.gregs.voidps.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import world.gregs.voidps.cache.type.decoder.VarBitDecoder

/**
 * Regression guard for the multi-file group split: the CONFIG/69 varbit archive is a 61k-file group
 * whose file 0 begins with opcode byte 0x01. Treating that leading 0x01 as a "modern format" marker
 * mis-split the group and overran the buffer, crashing the world the first time it decoded a varbit
 * (Vars.setVarBit -> Cache.varbit). This asserts a known varbit decodes from the split.
 */
class VarBitLoadIntegrationTest {

    @Test
    fun `decodes toplevel_v2_slim_mode varbit from the group split`() {
        val dir = CacheFixture.resolveCacheDir() ?: CacheFixture.resolveClientCacheDir()
        assumeTrue(dir != null, "no cache on this machine — skipping")
        val cache = CacheFixture.load(dir!!)

        val holder = Types(VarBitDecoder(), cache)
        val slimMode = assertDoesNotThrow("varbit 19924 must decode without a buffer overrun") {
            holder.getOrNull(19924)
        }
        assertNotNull(slimMode, "varbit 19924 (toplevel_v2_slim_mode) should decode")
        assertEquals(0, slimMode!!.domainId.toInt(), "varbit 19924 domain")
        assertEquals(3814, slimMode.index, "varbit 19924 base varp index")

        val varbits = assertDoesNotThrow("decoding every varbit must not throw") { VarBitDecoder().load(cache) }
        assertTrue(varbits.size > 50000, "expected tens of thousands of varbits, got ${varbits.size}")
    }
}
