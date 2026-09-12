package org.projectx.core.net

import org.projectx.core.net.prot.revision.rev950.Rev950NpcUpdateMaskKey
import org.projectx.core.net.prot.revision.rev950.Rev950PlayerUpdateMaskKey
import org.projectx.core.net.prot.revision.rev950.register950
import org.projectx.core.net.prot.update.Headbar
import org.projectx.core.net.prot.update.Hit
import org.projectx.core.net.prot.update.NpcStatEntry
import org.projectx.core.net.prot.update.NpcUpdateMaskEncoder
import org.projectx.core.net.prot.update.PlayerUpdateMaskEncoder
import org.projectx.core.net.prot.update.UpdateMask
import world.gregs.voidps.buffer.write.BufferWriter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Byte-level checks for the NPC ext-info encoders that carry health (NPC_STATS, HITMARKS_AND_HEADBARS)
 * plus the facing and animation blocks, against the 950-1 read order and transforms.
 */
class Rev950NpcExtInfoEncoderTest {
    init {
        register950()
    }

    private fun encode(key: Rev950NpcUpdateMaskKey, mask: UpdateMask): String {
        val out = BufferWriter(64)
        NpcUpdateMaskEncoder.encode(out, key, mask)
        return out.toArray().joinToString(" ") { "%02x".format(it) }
    }

    private fun encode(key: Rev950PlayerUpdateMaskKey, mask: UpdateMask): String {
        val out = BufferWriter(64)
        PlayerUpdateMaskEncoder.encode(out, key, mask)
        return out.toArray().joinToString(" ") { "%02x".format(it) }
    }

    @Test
    fun `NPC FACE_ENTITY puts the kind byte between the two big-endian index bytes`() {
        assertEquals("00 02 05", encode(Rev950NpcUpdateMaskKey.FACE_ENTITY, UpdateMask.FaceEntity(5, UpdateMask.FaceEntity.PLAYER)))
        assertEquals("00 01 05", encode(Rev950NpcUpdateMaskKey.FACE_ENTITY, UpdateMask.FaceEntity(5, UpdateMask.FaceEntity.NPC)))
        assertEquals("00 ff 00", encode(Rev950NpcUpdateMaskKey.FACE_ENTITY, UpdateMask.FaceEntity.CLEAR))
    }

    @Test
    fun `FACE_TILE encodes half-tile centre (x big-endian, y little-endian)`() {
        assertEquals("19 01 01 19", encode(Rev950NpcUpdateMaskKey.FACE_TILE, UpdateMask.FaceTile(3200, 3200)))
    }

    @Test
    fun `NPC ANIMATION is 4 big-smart layers plus a raw delay byte`() {
        assertEquals("09 46 7f ff 7f ff 7f ff 00", encode(Rev950NpcUpdateMaskKey.ANIMATION, UpdateMask.Animation(2374)))
    }

    @Test
    fun `player ANIMATION is 4 big-smart layers plus a raw delay byte`() {
        assertEquals("09 46 7f ff 7f ff 7f ff 00", encode(Rev950PlayerUpdateMaskKey.ANIMATION, UpdateMask.Animation(2374)))
    }

    @Test
    fun `player FACE_DIRECTION encodes the angle big-endian`() {
        assertEquals("04 00", encode(Rev950PlayerUpdateMaskKey.FACE_DIRECTION, UpdateMask.FaceDirection(1024)))
    }

    @Test
    fun `player FACE_ENTITY is a little-endian index then the kind byte`() {
        assertEquals("e6 5e 01", encode(Rev950PlayerUpdateMaskKey.FACE_ENTITY, UpdateMask.FaceEntity(0x5ee6, UpdateMask.FaceEntity.NPC)))
        assertEquals("05 00 02", encode(Rev950PlayerUpdateMaskKey.FACE_ENTITY, UpdateMask.FaceEntity(5, UpdateMask.FaceEntity.PLAYER)))
        assertEquals("00 00 ff", encode(Rev950PlayerUpdateMaskKey.FACE_ENTITY, UpdateMask.FaceEntity.CLEAR))
    }

    @Test
    fun `NPC_STATS encodes an add-byte slot, a little-endian current and a permuted max`() {
        val mask = UpdateMask.NpcStats(listOf(NpcStatEntry(NpcStatEntry.SLOT_LIFEPOINTS, 137, 45000)))
        assertEquals("01 83 89 00 00 00 00 c8 af", encode(Rev950NpcUpdateMaskKey.NPC_STATS, mask))
    }

    @Test
    fun `single-fill headbar with a hitsplat`() {
        val mask = UpdateMask.HitMarksAndHeadbars(
            hits = listOf(Hit(type = 3, damage = 10, delay = 0)),
            headbars = listOf(Headbar(type = 0, fromFill = 128, delay = 150)),
        )
        assertEquals("ff 03 0a 00 01 00 00 80 96 00 00", encode(Rev950NpcUpdateMaskKey.HITMARKS_AND_HEADBARS, mask))
    }

    @Test
    fun `hitmarks_and_headbars_2 hitsplat plus health bar`() {
        val mask = UpdateMask.HitMarksAndHeadbars(
            hits = listOf(Hit(type = 139, damage = 1681, delay = 0)),
            headbars = listOf(Headbar(type = 0, fromFill = 50)),
        )
        assertEquals("81 80 8b 91 06 00 00 00 7f 00 00 00 32 00", encode(Rev950NpcUpdateMaskKey.HITMARKS_AND_HEADBARS_2, mask))
    }

    @Test
    fun `hitmarks_and_headbars_2 empty bar plus remove`() {
        val mask = UpdateMask.HitMarksAndHeadbars(
            hits = emptyList(),
            headbars = listOf(Headbar(type = 0, fromFill = 0), Headbar(type = 0, remove = true)),
        )
        assertEquals("80 7e 00 00 00 00 00 00 ff ff", encode(Rev950NpcUpdateMaskKey.HITMARKS_AND_HEADBARS_2, mask))
    }

    @Test
    fun `hitmarks_and_headbars_2 remove bar`() {
        val mask = UpdateMask.HitMarksAndHeadbars(
            hits = emptyList(),
            headbars = listOf(Headbar(type = 0, remove = true)),
        )
        assertEquals("80 7f 00 ff ff", encode(Rev950NpcUpdateMaskKey.HITMARKS_AND_HEADBARS_2, mask))
    }

    @Test
    fun `tweened headbar with a stacked second bar`() {
        val mask = UpdateMask.HitMarksAndHeadbars(
            hits = emptyList(),
            headbars = listOf(
                Headbar(type = 0, fromFill = 128, toFill = 64, transitionCycles = 1, delay = 150, secondType = 5, secondFromFill = 200, secondToFill = 100),
            ),
        )
        assertEquals("00 01 00 01 80 96 00 c0 06 c8 1c", encode(Rev950NpcUpdateMaskKey.HITMARKS_AND_HEADBARS, mask))
    }
}
