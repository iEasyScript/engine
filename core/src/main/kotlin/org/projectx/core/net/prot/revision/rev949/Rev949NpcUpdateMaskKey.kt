package org.projectx.core.net.prot.revision.rev949

import org.projectx.core.net.prot.update.NpcUpdateMaskKey

/**
 * Rev949 NPC_INFO extended-info flag bitset, derived from an exhaustive walk of the 949
 * `jag::NPCEntity::ProcessExtendedInfo` (FUN_0016b700, reached from NPCList::ProcessNpcInfo)
 * top-level `(mask & flag)` branch table. `order` is the client's fixed dispatch order (branch
 * source order); encoders serialise ascending.
 *
 * Bit 22 (modelled as MODEL_OVERRIDE_ID) and bit 10 (FACE_ENTITY) share a 0xFFFF sentinel; they are
 * distinguished by callee and store offset, not by shape. Disambiguating the bit-22 name is a follow-up.
 *
 * HYPOTHESIS entries (UNK_*, SPOT_ANIM_LIST_*, HITMARKS_AND_HEADBARS_2): identity inferred from read
 * shape / callee only — a live NPC capture (walk near NPCs that animate/attack/transform/spot-anim)
 * would confirm bit↔block. All HYPOTHESIS entries are Raw-encoded, so a wrong identity among
 * same-shape blocks cannot misframe the wire.
 */
enum class Rev949NpcUpdateMaskKey(
    override val bit: Int,
    override val order: Int,
) : NpcUpdateMaskKey {
    CLIENT_SCRIPT_OVERRIDE(20, 1),
    MODEL_OVERRIDE_ID(22, 2),
    UNK_BIT4(4, 3),
    TRANSIENT_BOOL(28, 4),
    ANIMATION(3, 5),
    CHAT_OVERHEAD(1, 6),
    HITMARKS_AND_HEADBARS(0, 7),

    /** bit 7 = NPC interaction-face — the analogue of the player's bit-0 face. Reads `gScrambledMedium`
     * (`shortLE(index) + flag`, `01`=face / `ff`=stop) and stores the index to `NPCEntity+0x1B4`
     * (interactionSid) via FUN_004ac200. This is the soft interaction facing that coexists with walking;
     * use THIS to make an NPC face its target, NOT [TRACKED_FACE_LOCK] (+0x10d0), which hard-locks the
     * render orientation every frame and makes a moving NPC slide instead of animating a walk. */
    FACE_ENTITY(7, 8),
    VISIBILITY_FLAG(25, 9),
    UNK_BIT9(9, 10),
    UNK_BIT21(21, 11),
    COMBAT_LEVEL_OVERRIDE_RGB(31, 12),
    FORCED_MOVEMENT(11, 13),
    NPC_STATS(16, 14),
    UNK_BIT27(27, 15),
    UNK_BIT33(33, 16),
    UNK_BIT30(30, 17),
    SPOT_ANIM_LIST_BIT19(19, 18),
    FACE_TILE(6, 19),
    STRING_OVERRIDE(2, 20),
    UNK_BIT13(13, 21),
    UNK_BIT34(34, 22),
    UNK_BIT29(29, 23),
    HITMARKS_AND_HEADBARS_2(32, 24),
    UNK_BIT8(8, 25),
    SPOT_ANIM_LIST_BIT23(23, 26),
    NAME_OVERRIDE(17, 27),
    UNK_BIT24(24, 28),
    UNK_BIT12(12, 29),

    /** bit 10 = tracked render-face lock (`writeShortLittle(index)` → `NPCEntity+0x10d0`, ref-counted,
     * `0xFFFF`=stop). Hard per-frame orientation lock — a moving NPC with this set SLIDES (no walk anim).
     * NOT used for interaction facing (see [FACE_ENTITY], bit 7); kept for stationary "stare" cases. */
    TRACKED_FACE_LOCK(10, 30);

    companion object {
        val byOrder: List<Rev949NpcUpdateMaskKey> = entries.sortedBy { it.order }

        /** Header expansion ("continue") bits — 949 = {5,14,18,26} (CHANGED from 948 {6,8,19,25}). */
        val EXPANSION_BITS = intArrayOf(5, 14, 18, 26)
    }
}
