package org.projectx.core.net.prot.revision.rev950

import org.projectx.core.net.prot.update.PlayerUpdateMaskKey

enum class Rev950PlayerUpdateMaskKey(
    override val bit: Int,
    override val order: Int,
) : PlayerUpdateMaskKey {
    SPOT_ANIM_LIST_BIT26(26, 1),
    UNK_BIT8(8, 2),
    UNK_BIT20(20, 3),
    ANIMATION(3, 4),
    OVERHEAD_DISPLAY_BOOL(23, 5),
    FACE_ENTITY(7, 6),
    UNK_BIT12(12, 7),
    UNK_BIT24(24, 8),
    ENTITY_VAR_BIT19(19, 9),
    HITMARKS(6, 10),
    CHAT_TEXT_PRIVATE(11, 11),
    OVERHEAD_CHAT(22, 12),
    APPEARANCE(5, 13),
    FORCED_MOVEMENT(0, 14),
    ENTITY_VAR_BIT17(17, 15),
    POSITION_COLOR(21, 16),
    HEAD_ICON(16, 17),
    HITMARKS_2(25, 18),
    MODEL_TRANSFORM_LIST(27, 19),
    OVERHEAD_OPACITY(9, 20),
    CHAT_TEXT(10, 21),
    FACE_DIRECTION(1, 22),
    UNK_BIT2(2, 23),
    UNK_BIT13(13, 24);

    companion object {
        val byOrder: List<Rev950PlayerUpdateMaskKey> = entries.sortedBy { it.order }

        val EXPANSION_BITS = intArrayOf(4, 15, 18)
    }
}
