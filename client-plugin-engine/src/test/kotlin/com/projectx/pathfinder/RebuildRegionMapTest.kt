package com.projectx.pathfinder

import org.projectx.core.net.prot.ProtRevisions
import java.util.HexFormat
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RebuildRegionMapTest {
    // A REBUILD_REGION captured from the live 950 client: a 24x24-zone instance whose grid ends on the body's last bit.
    private val captured: ByteArray = HexFormat.of().parseHex(
        javaClass.getResource("/packets/instance-layout-950.txt")!!.readText().trim()
    )

    private val rebuildRegion: Int =
        ProtRevisions.currentCodec().serverProtInfo.entries.first { it.value.name == "REBUILD_REGION" }.key

    @AfterTest
    fun reset() = RebuildRegionMap.clear()

    @Test
    fun `the packet is matched by name, and on build 950 that name is opcode 5`() {
        assertEquals(950, ProtRevisions.current)
        assertEquals(5, rebuildRegion)
    }

    @Test
    fun `a captured 950 instance layout decodes`() {
        RebuildRegionMap.observe(rebuildRegion, captured)

        val corner = assertIs<RebuildRegionMap.Source>(RebuildRegionMap.entryOf(1736, 312, 0))
        assertEquals(RebuildRegionMap.Source(84, 29, 0, 0, level = 0, rotation = 0), corner)
    }

    @Test
    fun `every zone of the 24 by 24 layout is recorded on all four planes`() {
        RebuildRegionMap.observe(rebuildRegion, captured)

        var copied = 0
        var void = 0
        for (plane in 0 until 4) for (x in 1736 until 1736 + 24) for (y in 312 until 312 + 24) {
            when (RebuildRegionMap.entryOf(x, y, plane)) {
                is RebuildRegionMap.Source -> copied++
                RebuildRegionMap.Void -> void++
                null -> error("zone ($x, $y, $plane) missing")
            }
        }
        assertEquals(1836, copied)
        assertEquals(468, void)
    }

    @Test
    fun `the same bytes under any other opcode are ignored`() {
        RebuildRegionMap.observe(93, captured)
        assertNull(RebuildRegionMap.entryOf(1736, 312, 0))
    }

    @Test
    fun `a body too short to hold its zone grid is not decoded`() {
        RebuildRegionMap.observe(rebuildRegion, captured.copyOf(200))
        assertNull(RebuildRegionMap.entryOf(1736, 312, 0))
    }
}
