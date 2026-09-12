package org.projectx.core.net.prot.revision.rev950

import org.projectx.core.net.prot.update.NpcUpdateMaskKey

enum class Rev950NpcUpdateMaskKey(
    override val bit: Int,
    override val order: Int,
) : NpcUpdateMaskKey {
    STRING_OVERRIDE(6, 1),
    UNK_BIT0(0, 2),
    UNK_BIT34(34, 3),
    TRACKED_FACE_LOCK(12, 4),
    CLIENT_SCRIPT_OVERRIDE(20, 5),
    TRANSIENT_BOOL(26, 6),
    UNK_BIT29(29, 7),
    COMBAT_LEVEL_OVERRIDE_RGB(28, 8),
    CHAT_OVERHEAD(2, 9),
    FORCED_MOVEMENT(14, 10),
    SPOT_ANIM_LIST_BIT21(21, 11),
    UNK_BIT8(8, 12),
    SPOT_ANIM_LIST_BIT16(16, 13),
    NPC_STATS(19, 14),
    NAME_OVERRIDE(18, 15),
    UNK_BIT15(15, 16),
    FACE_ENTITY(1, 17),
    FACE_TILE(7, 18),
    UNK_BIT31(31, 19),
    UNK_BIT11(11, 20),
    UNK_BIT32(32, 21),
    UNK_BIT10(10, 22),
    UNK_BIT24(24, 23),
    HITMARKS_AND_HEADBARS_2(33, 24),
    MODEL_OVERRIDE_ID(17, 25),
    ANIMATION(3, 26),
    UNK_BIT22(22, 27),
    HITMARKS_AND_HEADBARS(5, 28),
    VISIBILITY_FLAG(25, 29),
    UNK_BIT30(30, 30);

    companion object {
        val byOrder: List<Rev950NpcUpdateMaskKey> = entries.sortedBy { it.order }

        val EXPANSION_BITS = intArrayOf(4, 13, 23, 27)
    }
}
