package org.projectx.core.net.prot.update

/**
 * Sealed hierarchy of per-entity update masks for PLAYER_INFO and NPC_INFO. The two protocols
 * share several block shapes but assign different bit positions and include some protocol-only
 * blocks; encoder selection happens via [PlayerUpdateMaskKey] / [NpcUpdateMaskKey].
 */
sealed class UpdateMask {
    data class Appearance(val data: ByteArray) : UpdateMask() {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Appearance && data.contentEquals(other.data))
        override fun hashCode(): Int = data.contentHashCode()
    }

    data class ChatText(val message: String, val effects: Int = 0, val color: Int = 0) : UpdateMask()

    data class OverheadText(val message: String) : UpdateMask()

    data class HitMarksAndHeadbars(val hits: List<Hit>, val headbars: List<Headbar>) : UpdateMask()

    /** NPC_STATS block: per-slot (current, max) vitals. Slot 3 = lifepoints (drives the hover HUD). */
    data class NpcStats(val entries: List<NpcStatEntry>) : UpdateMask()

    data class ForcedMovement(
        val srcDx: Int,
        val srcDz: Int,
        val dstDx: Int,
        val dstDz: Int,
        val delta3: Int,
        val delta4: Int,
        val startTime: Int,
        val endTime: Int,
        val animationId: Int,
    ) : UpdateMask()

    /**
     * Entity-face lock. The client stores a discriminator ([kind]) telling which list to resolve
     * [index] in — [NPC] (npc list) or [PLAYER] (player list) — plus the RAW list index (NOT
     * `0x8000|index`). [STOP] releases the lock. The two protocol face blocks pack `kind<<16 | index`
     * into a scrambled medium, differing only in byte order (player bit-0 LE, npc bit-7 BE).
     */
    data class FaceEntity(val index: Int, val kind: Int) : UpdateMask() {
        companion object {
            const val NPC = 1
            const val PLAYER = 2
            const val STOP = 0xFF
            val CLEAR = FaceEntity(0, STOP)
        }
    }

    /** Static tile-face point (NPC FACE_TILE); [x]/[y] are absolute tile coords. */
    data class FaceTile(val x: Int, val y: Int) : UpdateMask()

    data class FaceDirection(val angle: Int) : UpdateMask()

    data class Animation(val animId: Int, val speed: Int = 0) : UpdateMask()

    data class SpotAnims(val transforms: List<SpotAnimTransform>) : UpdateMask()

    data class PositionColor(
        val r: Int,
        val g: Int,
        val b: Int,
        val brightness: Int,
        val startCycle: Int,
        val endCycle: Int,
    ) : UpdateMask()

    data class VisibilityFlag(val visible: Boolean) : UpdateMask()

    /** 0xFFFF clears the override; else sets the model override slot. */
    data class ModelOverride(val modelId: Int) : UpdateMask()

    data class OverheadOpacity(val opacity: Int) : UpdateMask()

    data class NameString(val name: String) : UpdateMask()

    /**
     * If `flags & 1` the client routes to ChatHistory::AddChat with player name/clan, otherwise
     * the message is set on the entity's chat overlay directly.
     */
    data class ChatTextPrivate(val message: String, val flags: Int) : UpdateMask()

    /**
     * Provisionally HEAD_ICONS in 946-era docs but the byte layout doesn't match an icon list;
     * kept as a 3-tuple until a capture-driven RE pass disambiguates.
     */
    data class HeadIcons(val short: Int, val int: Long, val byte: Int) : UpdateMask()

    /** id == -1 triggers a "clear all" inside the same loop and breaks. */
    data class SpotAnimRemoval(val slot: Int) : UpdateMask()

    /**
     * Sentinel for NPCs whose appearance is driven by an NPC type id. Distinct from [Appearance]
     * because no extended-info block carries it on the wire — it lets world-side code register a
     * "needs respawn" signal.
     */
    data class NpcAppearance(val typeId: Int) : UpdateMask()

    data class BooleanFlag(val value: Boolean) : UpdateMask()

    /** 0xFFFF restores the default from the NPC type def. */
    data class CombatLevelHeadbarId(val id: Int) : UpdateMask()

    data class ClientScriptOverride(val payload: ByteArray) : UpdateMask() {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is ClientScriptOverride && payload.contentEquals(other.payload))
        override fun hashCode(): Int = payload.contentHashCode()
    }

    /** Wire layout identical to [ModelOverride]; a dedicated NPC-side type for encoder clarity. */
    data class ModelOverrideId(val id: Int) : UpdateMask()

    data class AnimationList(val payload: ByteArray) : UpdateMask() {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is AnimationList && payload.contentEquals(other.payload))
        override fun hashCode(): Int = payload.contentHashCode()
    }

    /** "null" restores default. */
    data class NameOverride(val name: String) : UpdateMask()

    /** Slot 0 conventionally marks the primary list, non-zero the head list. */
    data class OverheadIcon(val slotsAndIds: List<Pair<Int, Int>>) : UpdateMask()

    /**
     * Opaque pre-built ext-info block payload for a flag whose wire layout is not modelled as a
     * typed carrier. Game logic builds the exact bytes for the flag identified by [bit]; the
     * encoder emits them verbatim.
     */
    data class Raw(val bit: Int, val bytes: ByteArray) : UpdateMask() {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Raw && bit == other.bit && bytes.contentEquals(other.bytes))
        override fun hashCode(): Int = 31 * bit + bytes.contentHashCode()
    }
}
