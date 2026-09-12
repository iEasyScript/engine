package world.gregs.voidps.cache

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import world.gregs.voidps.cache.type.data.VarDomain
import world.gregs.voidps.gameval.Gameval
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The var decoders had a failure this is built to catch: constants naming archives that no longer
 * exist. Querying a missing archive yields nothing, so the decoder loaded zero records and reported
 * success — no exception, no trailing bytes, nothing for a health check to notice. Counting records
 * is what exposes a silent miss, and the gameval tables (cache index 67, shipped separately from the
 * records they name) supply the count from outside the decoder.
 *
 * ⚠️ Only seven of the ten domains have a gameval table. WORLD, MAPSQUARE and CONTROLLER have none,
 * and neither does the archive behind `Cache.varGlobals`, which has no [VarDomain] entry at all —
 * its very name was inherited from a third-party dump rather than from the client. Those four are
 * covered here only by the floor on decoded records; their identity rests on the cache
 * cross-references used to derive them, not on any independent name table.
 */
class VarDomainIntegrityIntegrationTest {

    private data class Domain(val label: String, val gamevalType: String?, val decoded: () -> Int, val resolves: (Int) -> Boolean)

    private fun domains() = listOf(
        Domain("PLAYER", Gameval.VAR_PLAYER, { Cache.varPlayers.size }) { Cache.varPlayer(it) != null },
        Domain("CLIENT", Gameval.VAR_CLIENT, { Cache.varcs.size }) { Cache.varc(it) != null },
        Domain("NPC", Gameval.VAR_NPC, { Cache.varNpcs.size }) { Cache.varNpc(it) != null },
        Domain("PLAYER_GROUP", Gameval.VAR_PLAYER_GROUP, { Cache.varGroups.size }) { Cache.varGroup(it) != null },
        Domain("CLAN", Gameval.VAR_CLAN, { Cache.varClans.size }) { Cache.varClan(it) != null },
        Domain("CLAN_SETTING", Gameval.VAR_CLAN_SETTING, { Cache.varClanSettings.size }) { Cache.varClanSetting(it) != null },
        Domain("OBJECT", Gameval.VAR_OBJECT, { Cache.varObjects.size }) { Cache.varObject(it) != null },
        Domain("WORLD", null, { Cache.varWorlds.size }) { Cache.varWorld(it) != null },
        Domain("MAPSQUARE", null, { Cache.varMapSquares.size }) { Cache.varMapSquare(it) != null },
        Domain("CONTROLLER", null, { Cache.varControllers.size }) { Cache.varController(it) != null },
        Domain("GLOBAL", null, { Cache.varGlobals.size }) { Cache.varGlobal(it) != null },
    )

    @Test
    fun `every var domain decodes records`() {
        CacheFixture.requireCache()
        val empty = domains().filter { it.decoded() == 0 }
        assertTrue(empty.isEmpty(), "these var domains decoded nothing — a missing archive reads as success: ${empty.map { it.label }}")
    }

    @Test
    fun `every gameval named var resolves in its domain`() {
        CacheFixture.requireCache()
        assumeTrue(Gameval.has(Gameval.VAR_PLAYER), "no gameval tables on this machine — skipping")

        for (domain in domains()) {
            val type = domain.gamevalType ?: continue
            val named = Gameval.entries(type).keys
            assumeTrue(named.isNotEmpty(), "no gameval $type table — skipping")
            assertEquals(named.size, named.count(domain.resolves), "${domain.label}: gameval-named vars that do not decode")
        }
    }

    @Test
    fun `every domain maps to a distinct config archive`() {
        val archives = VarDomain.entries.map { it.configArchive }
        assertEquals(archives.size, archives.toSet().size, "two var domains claim the same config archive: $archives")
        for (domain in VarDomain.entries) assertEquals(domain, VarDomain.forId(domain.id), "domain id ${domain.id} does not round-trip")
    }
}
