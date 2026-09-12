package com.projectx.script.impl.trent.dungeoneering

import com.projectx.script.impl.trent.dungeoneering.map.MapIcons
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.sqlite.SQLiteCache
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pins the doorway decode against the real Daemonheim room graphics. The 19 `rand_map_rooms` shapes are the
 * whole connection graph: 8 single-door dead-ends (4 rotations x 2 variants), 4 corners, 2 straights, 4 T's
 * and the cross. A regression here silently breaks which rooms the assistant thinks connect.
 */
class RoomOpeningsTest {

    private val N = MapIcons.NORTH
    private val E = MapIcons.EAST
    private val S = MapIcons.SOUTH
    private val W = MapIcons.WEST

    private val expected = intArrayOf(
        S, W, N, E, S, W, N, E, S or W, N or W, N or E, E or S,
        N or S or W, N or E or W, N or E or S, E or S or W, N or E or S or W, N or S, E or W
    )

    @Test
    fun `decodes every room shape's doorways`() {
        assumeTrue(loadCache(), "data/cache not present — skipping")

        for (i in 0..18) {
            assertEquals(expected[i], MapIcons.doorMask(2787 + i), "rooms_off_$i (graphic ${2787 + i})")
            assertEquals(expected[i], MapIcons.doorMask(2806 + i), "rooms_on_$i (graphic ${2806 + i})")
        }
        // "?" rooms carry only the single door they were revealed through.
        assertEquals(S, MapIcons.doorMask(35883))
        assertEquals(W, MapIcons.doorMask(35884))
        assertEquals(N, MapIcons.doorMask(35885))
        assertEquals(E, MapIcons.doorMask(35886))

        // Start/boss icons are not room shapes — no decodable doorways.
        assertEquals(0, MapIcons.doorMask(MapIcons.START))
        assertEquals(0, MapIcons.doorMask(MapIcons.BOSS))

        assertEquals(null, MapIcons.roomOpenings(MapIcons.START), "start is not a room-shape graphic")
        assertEquals(S, MapIcons.roomOpenings(2787))
    }

    private fun loadCache(): Boolean {
        var here: Path? = Path.of("").toAbsolutePath()
        while (here != null) {
            val dir = here.resolve("data/cache")
            if (Files.exists(dir.resolve("js5-8.jcache"))) {
                Cache.init(SQLiteCache.load(dir, readOnly = true))
                return true
            }
            here = here.parent
        }
        return false
    }
}
