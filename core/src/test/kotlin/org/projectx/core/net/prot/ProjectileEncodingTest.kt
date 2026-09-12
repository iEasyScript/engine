package org.projectx.core.net.prot

import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.projectx.core.net.prot.revision.rev950.register950
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins both projectile packets to the wire layout read out of the 950-1 client's own handlers.
 *
 * The layout that matters most here is the delta order: the handler adds the first delta byte to the
 * X it derives from the zone header and the second to the Y. Sending them the other way round is
 * invisible in a log and looks, in game, like the projectile flying off at right angles.
 */
class ProjectileEncodingTest {
    private val codec = register950()

    /** The encoder writes the body only; framing adds the opcode, so [opcode] is asserted separately. */
    private fun body(prot: ServerProt, opcode: Int): List<Int> = runBlocking {
        val entry = codec.serverProts[prot::class] ?: error("no encoder for ${prot::class.simpleName}")
        assertEquals(opcode, entry.opcode, "opcode")
        val buffer = Buffer()
        val channel = buffer.asByteWriteChannel()
        entry.encoder!!.invoke(prot, channel)
        channel.flush()
        buffer.readByteArray().map { it.toInt() and 0xFF }
    }

    @Test
    fun `MAP_PROJANIM lays its fields out the way the client reads them`() {
        val bytes = body(
            MapProjAnim(
                srcPackedCoord = 0x2B,
                targetDeltaX = 5,
                targetDeltaY = -3,
                lockOnId = 0x0102,
                spotAnim = 0x1F90,
                startHeight = 40,
                endHeight = 12,
                startTime = 0x0021,
                endTime = 0x0043,
                lockOnSlot = 0,
                alpha = 0xFF,
            ),
            opcode = 114,
        )

        assertEquals(20, bytes.size, "MAP_PROJANIM is a fixed 20-byte packet")
        assertEquals(0x2B, bytes[0], "+0 packed source, X in bits 3-5 and Y in bits 0-2")
        assertEquals(5, bytes[1], "+1 is the X delta")
        assertEquals(0xFD, bytes[2], "+2 is the Y delta, signed")
        assertEquals(listOf(0x00, 0x01, 0x02), bytes.subList(3, 6), "+3 lock-on entity id, 24-bit")
        assertEquals(listOf(0x1F, 0x90), bytes.subList(6, 8), "+6 spotanim")
        assertEquals(40, bytes[8])
        assertEquals(12, bytes[9])
        assertEquals(listOf(0x00, 0x21), bytes.subList(10, 12), "+10 launch time")
        assertEquals(listOf(0x00, 0x43), bytes.subList(12, 14), "+12 arrival time")
        assertEquals(0xFF, bytes[14], "+14 alpha")
        assertEquals(listOf(0x00, 0x00), bytes.subList(15, 17), "+15 lock-on slot")
        assertEquals(listOf(0, 0, 0), bytes.subList(17, 20), "+17 three bytes the handler never reads")
    }

    @Test
    fun `MAP_PROJANIM_HALFSQ carries both entity ids in half-square space`() {
        val bytes = body(
            MapProjAnimHalfsq(
                srcPackedCoord = 0x5C,
                flags = 0x02,
                destDeltaX = -6,
                destDeltaY = 9,
                sourceId = 0x0304,
                lockOnId = 0x0506,
                spotAnim = 0x1F90,
                startHeight = 40,
                endHeight = 12,
                startTime = 0x0021,
                endTime = 0x0043,
                lockOnSlot = 0,
                alpha = 0xFF,
            ),
            opcode = 154,
        )

        assertEquals(21, bytes.size, "MAP_PROJANIM_HALFSQ is a fixed 21-byte packet")
        assertEquals(0x5C, bytes[0], "+0 packed half-square source")
        assertEquals(0x02, bytes[1], "+1 flags")
        assertEquals(0xFA, bytes[2], "+2 is the X delta, signed")
        assertEquals(9, bytes[3], "+3 is the Y delta")
        assertEquals(listOf(0x00, 0x03, 0x04), bytes.subList(4, 7), "+4 source entity id")
        assertEquals(listOf(0x00, 0x05, 0x06), bytes.subList(7, 10), "+7 lock-on entity id")
        assertEquals(listOf(0x1F, 0x90), bytes.subList(10, 12), "+10 spotanim")
        assertEquals(40, bytes[12])
        assertEquals(12, bytes[13])
        assertEquals(listOf(0x00, 0x21), bytes.subList(14, 16), "+14 launch time")
        assertEquals(listOf(0x00, 0x43), bytes.subList(16, 18), "+16 arrival time")
        assertEquals(0xFF, bytes[18], "+18 alpha")
        assertEquals(listOf(0x00, 0x00), bytes.subList(19, 21), "+19 lock-on slot")
    }

    @Test
    fun `a projectile with no entity to follow leaves the lock-on empty`() {
        val bytes = body(
            MapProjAnim(
                srcPackedCoord = 0,
                targetDeltaX = 1,
                targetDeltaY = 0,
                lockOnId = 0,
                spotAnim = 10,
                startHeight = 40,
                endHeight = 40,
                startTime = 0,
                endTime = 30,
            ),
            opcode = 114,
        )
        assertEquals(listOf(0, 0, 0), bytes.subList(3, 6), "0 means aim at the tile, not an entity")
    }

    @Test
    fun `MAP_PROJANIM_ALT widens the heights and appends both fine offsets`() {
        val bytes = body(
            MapProjAnimAlt(
                srcPackedCoord = 0x2B,
                targetDeltaX = 5,
                targetDeltaY = -3,
                lockOnId = 0x0102,
                spotAnim = 0x1F90,
                startHeight = 0x0140,
                endHeight = -2,
                startTime = 0x0021,
                endTime = 0x0043,
                sourceOffset = 0x1FFBFF,
                destOffset = 0x5FFBFF,
            ),
            opcode = 169,
        )

        assertEquals(28, bytes.size, "MAP_PROJANIM_ALT is a fixed 28-byte packet")
        assertEquals(0x2B, bytes[0])
        assertEquals(5, bytes[1], "+1 is the X delta")
        assertEquals(0xFD, bytes[2], "+2 is the Y delta, signed")
        assertEquals(listOf(0x00, 0x01, 0x02), bytes.subList(3, 6), "+3 lock-on entity id")
        assertEquals(listOf(0x1F, 0x90), bytes.subList(6, 8), "+6 spotanim")
        assertEquals(listOf(0x01, 0x40), bytes.subList(8, 10), "+8 start height is 16-bit here")
        assertEquals(listOf(0xFF, 0xFE), bytes.subList(10, 12), "+10 end height, signed 16-bit")
        assertEquals(listOf(0x00, 0x21), bytes.subList(12, 14), "+12 launch time")
        assertEquals(listOf(0x00, 0x43), bytes.subList(14, 16), "+14 arrival time")
        assertEquals(0xFF, bytes[16], "+16 alpha")
        assertEquals(listOf(0x00, 0x00), bytes.subList(17, 19), "+17 lock-on slot")
        assertEquals(listOf(0, 0, 0), bytes.subList(19, 22), "+19 three bytes the handler never reads")
        assertEquals(listOf(0x1F, 0xFB, 0xFF), bytes.subList(22, 25), "+22 packed source offset")
        assertEquals(listOf(0x5F, 0xFB, 0xFF), bytes.subList(25, 28), "+25 packed dest offset")
    }

    @Test
    fun `MAP_PROJANIM_HALFSQ_ALT matches the layout live RS3 sends for spells`() {
        val bytes = body(
            MapProjAnimHalfsqAlt(
                srcPackedCoord = 0x93,
                flags = 0,
                destDeltaX = 10,
                destDeltaY = 2,
                sourceId = 0x020121,
                lockOnId = 0x0158F0,
                spotAnim = 0x22FA,
                startHeight = 0x0084,
                endHeight = 0x0032,
                startTime = 0x002A,
                endTime = 0x003C,
                sourceOffset = 0x5FFBFF,
                destOffset = 0x5FFBFF,
                lockOnSlot = 0x000F,
                alpha = 0x00,
            ),
            opcode = 196,
        )

        // Byte-for-byte the first projectile from the live magic capture.
        assertEquals(
            "93 00 0a 02 02 01 21 01 58 f0 22 fa 00 84 00 32 00 2a 00 3c 00 00 0f 5f fb ff 5f fb ff",
            bytes.joinToString(" ") { "%02x".format(it) },
            "must reproduce the captured packet exactly",
        )
    }

    @Test
    fun `the halfsq packets order their deltas the same way as the tile ones`() {
        val half = body(
            MapProjAnimHalfsqAlt(
                srcPackedCoord = 0, flags = 0, destDeltaX = 7, destDeltaY = -7,
                sourceId = 0, lockOnId = 0, spotAnim = 1, startHeight = 0, endHeight = 0,
                startTime = 0, endTime = 0, sourceOffset = 0, destOffset = 0,
            ),
            opcode = 196,
        )
        assertEquals(7, half[2], "+2 is X in every projectile packet")
        assertEquals(0xF9, half[3], "+3 is Y in every projectile packet")
    }
}
