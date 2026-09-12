package world.gregs.voidps.cache.gameval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import world.gregs.voidps.cache.sqlite.SQLiteCache
import world.gregs.voidps.gameval.Gameval
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/**
 * Decodes the real beta-only cache **index 67** (gameval / RSCM id<->name) from
 * `data/betacache/js5-67.jcache` and validates it round-trips against the bundled
 * `re-resources/gamevals/<type>.json` dictionaries (loaded via [Gameval]). Skips when the beta cache is
 * absent; it IS present in this repo. See `re-resources/docs/cache/gameval_index67.md`.
 *
 * Index 67 is read-only here: [SQLiteCache.load] opens the throwaway beta cache, never the live one.
 */
class GamevalIndexDecoderTest {

    /**
     * Resolves `data/betacache` regardless of the test working directory (Gradle runs `:core` tests
     * from the module dir, not the repo root), by walking up from the CWD looking for the beta
     * `js5-67.jcache`. An explicit `BETA_CACHE_DIR` env var wins.
     */
    private fun betaCacheDir(): Path? {
        val file = "js5-$INDEX67.jcache"
        System.getenv("BETA_CACHE_DIR")?.let { Path.of(it) }
            ?.takeIf { Files.exists(it.resolve(file)) }
            ?.let { return it }
        var here: Path? = Path.of("").toAbsolutePath()
        while (here != null) {
            val candidate = here.resolve("data/betacache")
            if (Files.exists(candidate.resolve(file))) return candidate
            here = here.parent
        }
        return null
    }

    @Test
    fun `decodes index 67 and round-trips against bundled gamevals`() {
        val dir = betaCacheDir()
        assumeTrue(dir != null, "data/betacache/js5-67.jcache not present — skipping")

        val cache = SQLiteCache.load(dir!!, readOnly = true)
        val decoder = GamevalIndexDecoder()
        val all = assertDoesNotThrow("decoding all index-67 archives must not throw") { decoder.decode(cache) }

        // --- Print the derived archive-id -> type map and decoded counts ---
        println("[gameval67] decoded ${all.size} gameval types from index 67:")
        for ((archive, type) in GamevalIndex.TYPE_BY_ARCHIVE) {
            val count = all[type]?.size ?: -1
            println("[gameval67]   archive %-3d -> %-18s : %d entries".format(archive, type, count))
            // Combined var_* archives ALSO yield a per-domain varbit table (when the domain has any).
            GamevalIndex.VAR_DOMAIN_BY_ARCHIVE[archive]?.let { domain ->
                all[domain.varbitType]?.let { println("[gameval67]   archive %-3d -> %-18s : %d entries".format(archive, domain.varbitType, it.size)) }
            }
        }
        // Every non-var archive yields one type; every combined var archive yields var_<domain> plus
        // (for the 5 domains that have varbits) varbit_<domain>.
        val expectedTypes = GamevalIndex.TYPE_BY_ARCHIVE.size +
            GamevalIndex.VAR_DOMAINS.count { all[it.varbitType]?.isNotEmpty() == true }
        assertEquals(expectedTypes, all.size, "every index-67 archive should decode to its type(s)")
        for ((_, type) in GamevalIndex.TYPE_BY_ARCHIVE) {
            assertTrue(all.containsKey(type), "type $type should be present")
        }

        // --- Spot assertions (raw cache is UPPERCASE; the decoder lowercases to RSCM dev-names) ---
        assertEquals("swarm_walk", all["seq"]?.get(0), "seq 0")
        assertEquals("hans", all["npc"]?.get(0), "npc 0")
        assertEquals("mcannonremains", all["obj"]?.get(0), "obj 0")

        // --- Combined var/varbit split (verified against the real beta bytes) ---
        // var-part keeps the original archive ids; varbit-part is rebased (id - offset) with the
        // leading `_` stripped — see GamevalIndexDecoder.splitVarDomain.
        assertEquals("lastcastspell", all["var_player"]?.get(0), "var_player 0")
        assertEquals(10627, all["var_player"]?.size, "var_player var-part size")
        assertEquals("zaros_spellbook", all["varbit_player"]?.get(0), "varbit_player 0")
        assertEquals("popup_kerapac_dnd_shown", all["varbit_player"]?.get(1), "varbit_player 1")
        assertEquals(50690, all["varbit_player"]?.size, "varbit_player varbit-part size")
        // Other domains: both halves appear (npc has both; counts from the real beta data).
        assertEquals(167, all["var_npc"]?.size, "var_npc var-part size")
        assertEquals(938, all["varbit_npc"]?.size, "varbit_npc varbit-part size")
        assertTrue(all.containsKey("varbit_clan") && all.containsKey("varbit_clan_setting") && all.containsKey("varbit_object"),
            "every var domain with varbits must yield a varbit_<domain> table")
        // Domains without varbits yield no varbit table.
        assertTrue(!all.containsKey("varbit_client") && !all.containsKey("varbit_player_group"),
            "var_client / var_player_group have no varbits, so no varbit_<domain> table")

        // --- Per-domain helpers agree with the bulk decode ---
        assertEquals(all["var_player"], decoder.decode(cache, "var_player"))
        assertEquals(all["varbit_player"], decoder.decode(cache, "varbit_player"))
        assertEquals(all["var_npc"], decoder.decodeVar(cache, "npc"))
        assertEquals(all["varbit_npc"], decoder.decodeVarbit(cache, "npc"))
        // The raw archive accessor returns the UN-split combined map (10627 + 50690).
        assertEquals(61317, decoder.decodeArchive(cache, GamevalIndex.archiveId("var_player")!!)?.size, "raw var_player archive is combined")
        // varbit_<domain> resolves to the same combined archive as var_<domain>.
        assertEquals(61, GamevalIndex.archiveId("varbit_player"), "varbit_player -> archive 61")

        // --- Composite-keyed `component` archive ---
        val components = decoder.decodeComponents(cache)
        assertEquals("100guide_eggs_overlay:100_q_anim5", components["0:1"], "component 0:1")
        // The packed-int view must agree with the composite-string view.
        assertEquals(components["0:1"], all[GamevalIndex.COMPONENT]?.get((0 shl 16) or 1))

        // --- Single-type / single-archive accessors agree with the bulk decode ---
        assertEquals(all["seq"], decoder.decode(cache, "seq"))
        assertEquals(all["seq"], decoder.decodeArchive(cache, GamevalIndex.archiveId("seq")!!))

        // --- Cross-validate against the bundled gamevals: every decoded id that the bundled json
        //     also knows must map to the SAME name (100% precision), and for types whose bundled
        //     json is a faithful one-to-one snapshot the counts must match closely (rev 949 vs 949).
        val faithfulTypes = listOf("seq", "npc", "loc", "obj", "enum", "cursor", "bas", "sound")
        for (type in faithfulTypes) {
            val decoded = all[type] ?: error("$type missing from decode")
            val bundled = Gameval.entries(type)
            assertTrue(bundled.isNotEmpty(), "bundled gameval json for $type should be present")

            var overlap = 0
            var matches = 0
            for ((id, name) in decoded) {
                val expected = bundled[id] ?: continue
                overlap++
                if (expected == name) matches++
            }
            assertTrue(overlap > 0, "$type: expected overlap with bundled json")
            assertEquals(overlap, matches, "$type: all overlapping ids must match the bundled gamevals")

            val delta = abs(decoded.size - bundled.size)
            val tolerance = maxOf(5, bundled.size / 50)
            assertTrue(
                delta <= tolerance,
                "$type: decoded count ${decoded.size} drifts from bundled ${bundled.size} (delta $delta > $tolerance)",
            )
            println("[gameval67]   $type: precision $matches/$overlap, decoded=${decoded.size}, json=${bundled.size}")
        }
    }

    private companion object {
        const val INDEX67 = GamevalIndex.INDEX
    }
}
