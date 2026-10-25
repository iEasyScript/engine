package com.projectx.pathfinder

import java.util.HexFormat
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RebuildRegionMapTest {
    // A REBUILD_REGION captured from the live 950 client: a 24x24-zone instance whose grid ends on the body's last bit.
    private val captured: ByteArray = fixture("instance-layout-950.txt")

    // Two captured from the live 951 client in Shattered Worlds: a 24x24-zone world of rotated rooms, and the 8x8 checkpoint.
    private val world951: ByteArray = fixture("instance-layout-951.txt")
    private val checkpoint951: ByteArray = fixture("instance-checkpoint-951.txt")

    private fun fixture(name: String): ByteArray =
        HexFormat.of().parseHex(javaClass.getResource("/packets/$name")!!.readText().trim())

    @AfterTest
    fun reset() = RebuildRegionMap.clear()

    @Test
    fun `the packet is matched by name, and on build 950 that name is opcode 5`() {
        assertEquals(5, RebuildRegionMap.opcodeFor(950))
    }

    @Test
    fun `on build 951 the packet is opcode 2`() {
        assertEquals(2, RebuildRegionMap.opcodeFor(951))
    }

    @Test
    fun `a captured 951 world decodes its rotated rooms`() {
        RebuildRegionMap.decode(world951)

        assertEquals(RebuildRegionMap.Source(53, 101, 6, 5, level = 0, rotation = 3), RebuildRegionMap.entryOf(1574, 198, 0))
        assertEquals(RebuildRegionMap.Source(53, 100, 3, 2, level = 0, rotation = 1), RebuildRegionMap.entryOf(1574, 200, 0))

        var copied = 0
        var void = 0
        for (plane in 0 until 4) for (x in 1568 until 1568 + 24) for (y in 192 until 192 + 24) {
            when (RebuildRegionMap.entryOf(x, y, plane)) {
                is RebuildRegionMap.Source -> copied++
                RebuildRegionMap.Void -> void++
                null -> error("zone ($x, $y, $plane) missing")
            }
        }
        assertEquals(352, copied)
        assertEquals(1952, void)
    }

    @Test
    fun `the 951 checkpoint copies its template zone for zone on every plane`() {
        RebuildRegionMap.decode(checkpoint951)

        for (plane in 0 until 4) for (dx in 0 until 8) for (dy in 0 until 8) {
            assertEquals(
                RebuildRegionMap.Source(49, 103, dx, dy, level = plane, rotation = 0),
                RebuildRegionMap.entryOf(1216 + dx, 176 + dy, plane),
            )
        }
    }

    @Test
    fun `a revision whose layout has not been checked decodes nothing`() {
        assertNull(RebuildRegionMap.opcodeFor(949))
        assertNull(RebuildRegionMap.opcodeFor(null))
    }

    @Test
    fun `a captured 950 instance layout decodes`() {
        RebuildRegionMap.decode(captured)

        val corner = assertIs<RebuildRegionMap.Source>(RebuildRegionMap.entryOf(1736, 312, 0))
        assertEquals(RebuildRegionMap.Source(84, 29, 0, 0, level = 0, rotation = 0), corner)
    }

    @Test
    fun `every zone of the 24 by 24 layout is recorded on all four planes`() {
        RebuildRegionMap.decode(captured)

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
        RebuildRegionMap.decode(captured.copyOf(200))
        assertNull(RebuildRegionMap.entryOf(1736, 312, 0))
    }
}
