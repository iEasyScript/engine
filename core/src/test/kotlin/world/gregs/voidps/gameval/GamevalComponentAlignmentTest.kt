package world.gregs.voidps.gameval

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.CacheFixture
import world.gregs.voidps.cache.Index

/**
 * Component names are beta slots re-keyed onto the served interface index. A cache download that moves that
 * index makes every name after an insertion address the wrong component without any lookup failing, so this
 * pins the dumps to the cache in the working tree: it fails after a download until the export is re-run.
 */
class GamevalComponentAlignmentTest {

    @Test
    fun `component names are aligned to the served interface index`() {
        CacheFixture.requireCache()
        assumeTrue(Gameval.source == Gameval.Source.FOLDER, "no gameval folder on this machine — skipping")

        assertNull(GamevalAlignment.drift(Cache.get()))
    }

    @Test
    fun `no aligned component name addresses a slot the served interface does not have`() {
        CacheFixture.requireCache()
        assumeTrue(Gameval.source == Gameval.Source.FOLDER, "no gameval folder on this machine — skipping")
        val stamp = Gameval.componentAlignment()
        assertNotNull(stamp, "component.json is not stamped — re-run ${GamevalAlignment.REALIGN_COMMAND}")

        val cache = Cache.get()
        val unaligned = stamp!!.unaligned.toSet()
        val servedCounts = HashMap<Int, Int>()
        val overflow = Gameval.componentEntries().keys.filter { key ->
            val interfaceId = key.substringBefore(':').toInt()
            val slot = key.substringAfter(':').toInt()
            val served = servedCounts.getOrPut(interfaceId) { cache.fileCount(Index.INTERFACES, interfaceId) }
            interfaceId !in unaligned && served > 0 && slot >= served
        }
        assertTrue(overflow.isEmpty(), "${overflow.size} component name(s) address slots past the served interface: ${overflow.take(20)}")
    }
}
