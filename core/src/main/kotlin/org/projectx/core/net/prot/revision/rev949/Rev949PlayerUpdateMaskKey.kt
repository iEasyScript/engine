package org.projectx.core.net.prot.revision.rev949

import org.projectx.core.net.prot.update.PlayerUpdateMaskKey

/**
 * Rev949 PLAYER_INFO extended-info flag bitset, derived from an exhaustive walk of the 949
 * `jag::PlayerEntity::ProcessExtendedInfo` (FUN_00165930) top-level `(mask & flag)` branch table.
 * `order` is the client's fixed dispatch order (branch source order); encoders serialise ascending.
 *
 * HYPOTHESIS entries (UNK_*, SPOT_ANIM_LIST_*, HITMARKS_2, HEAD_ICON): identity inferred from read
 * shape only — a live movement+appearance capture would confirm bit↔block. All are Raw-encoded, so
 * a wrong identity among same-shape blocks cannot misframe the wire.
 */
enum class Rev949PlayerUpdateMaskKey(
    override val bit: Int,
    override val order: Int,
) : PlayerUpdateMaskKey {
    UNK_BIT13(13, 1),
    CHAT_TEXT(10, 2),
    CHAT_TEXT_PRIVATE(12, 3),
    FACE_DIRECTION(7, 4),
    SPOT_ANIM_REMOVAL(26, 5),
    HITMARKS(4, 6),
    UNK_BIT9(9, 7),
    OVERHEAD_OPACITY(11, 8),
    APPEARANCE(2, 9),
    UNK_BIT3(3, 10),
    ANIMATION(1, 11),
    HEAD_ICON(23, 12),
    SPOT_ANIM_LIST_BIT24(24, 13),
    UNK_BIT27(27, 14),
    OVERHEAD_CHAT(18, 15),
    SPOT_ANIM_LIST_BIT19(19, 16),
    FORCED_MOVEMENT(6, 17),
    POSITION_COLOR(20, 18),

    /** bit 8: reads p1,p1,g2LE (`04 01 <short>`) and DISCARDS all three (asm @0x167bc0, no store).
     * Inert on 949 — NOT the player face block (that is [FACE_ENTITY] on bit 0). */
    UNK_BIT8(8, 19),
    SPOT_ANIM_LIST_BIT22(22, 20),
    UNK_BIT16(16, 21),

    /** bit 0 = the REAL player entity-face lock. Handler @0x167xxx reads `gScrambledMedium` =
     * `shortLE(index) + flag`, flag `01`=face / `ff`=stop, and stores the index to `PlayerEntity+0x1B4`
     * (interactionSid) via FUN_004ac200 (also flag→+0x228, resolved-target ref cache +0x218/+0x220).
     * This is what turns the player toward a target; capture-confirmed `<idxLE> 01`. */
    FACE_ENTITY(0, 22),
    HITMARKS_2(25, 23),

    /** bit 21: reads 1 scrambled byte → bool at `PlayerEntity+0x1071` (overhead/display toggle beside
     * OVERHEAD_OPACITY 0x1074). NOT facing-related — the old "TRANSIENT_BOOL paired with FACE_ENTITY"
     * label was a phantom from a mis-read byte-diff (official never sends it during facing). */
    OVERHEAD_DISPLAY_BOOL(21, 24);

    companion object {
        val byOrder: List<Rev949PlayerUpdateMaskKey> = entries.sortedBy { it.order }

        /** Header expansion ("continue") bits — 949 = {5,14,17} (CHANGED from 948 {0,13,22}). */
        val EXPANSION_BITS = intArrayOf(5, 14, 17)
    }
}
