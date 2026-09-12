package org.projectx.core.net.prot.update

/**
 * Process-global registry of the currently-active set of well-known mask keys for the loaded
 * revision. Set once during codec registration (see `Rev950Codec`).
 *
 * This exists because a handful of world-side builders need to refer to specific mask blocks
 * by NAME (e.g., "the APPEARANCE block") rather than by enum identity — and a major client bump
 * resolves those names to different bit positions. Without a lookup the builder would have to know
 * about every per-revision enum directly, which defeats the point of the per-revision shape.
 *
 * The set of well-known keys exposed here is intentionally minimal — anything that game logic
 * can produce on its own (by emitting an explicit `setPlayer(MyRev.SOME_KEY, mask)`) does NOT
 * need to be in here. Only blocks that the builder MUST be able to synthesize as a fallback
 * (currently just APPEARANCE for the first-tick render path) live here.
 */
object ActiveMaskKeys {
    /**
     * APPEARANCE block key for the active revision. Set by the codec registration.
     *
     * If null, `PlayerInfoBuilder` skips its "synthesize APPEARANCE for first-tick" fast-path
     * and the caller is expected to set the appearance via `setPlayer(rev.APPEARANCE, ...)`
     * directly.
     */
    @Volatile
    var playerAppearance: PlayerUpdateMaskKey? = null

    /** CHAT_TEXT (public-chat overhead + chatbox echo) block key for the active revision. */
    @Volatile
    var playerChatText: PlayerUpdateMaskKey? = null

    /** HITMARKS_2 (hitsplats + health bar, wide variant) block key for the active revision. */
    @Volatile
    var playerHitmarks2: PlayerUpdateMaskKey? = null

    /** ANIMATION (play a seq) block key for the active revision, per entity kind. Set directly by
     * game logic (`Entity.animate`), resolved by name so world-side code needn't import the enums. */
    @Volatile
    var playerAnimation: PlayerUpdateMaskKey? = null

    @Volatile
    var npcAnimation: NpcUpdateMaskKey? = null

    /** Entity-face lock keys — the client re-orients the entity toward the tracked target every frame
     * (writes `+0x10d0`). Symmetric for players (bit 8, payload `04 01 <indexLE>`) and NPCs (bit 10,
     * `<indexLE>`). Sent once on change; the block must stand alone (see OVERHEAD_DISPLAY_BOOL). */
    @Volatile
    var playerFaceEntity: PlayerUpdateMaskKey? = null

    @Volatile
    var npcFaceEntity: NpcUpdateMaskKey? = null

    /** Player FACE_DIRECTION key (bit 7 angle) — the one-shot turn toward a static tile. */
    @Volatile
    var playerFaceDirection: PlayerUpdateMaskKey? = null

    @Volatile
    var npcFaceTile: NpcUpdateMaskKey? = null

    /**
     * NPC_STATS (per-slot current/max vitals) block key for the active revision. `NpcInfoBuilder`
     * synthesizes this on first-sight so a newly-seen NPC's hover HUD shows its lifepoints without
     * an explicit request. Null on revisions where the block isn't identified.
     */
    @Volatile
    var npcStats: NpcUpdateMaskKey? = null

    /** HITMARKS_AND_HEADBARS block key for the active revision (floating hitsplats + health bar). */
    @Volatile
    var npcHitmarksAndHeadbars: NpcUpdateMaskKey? = null

    /**
     * HITMARKS_AND_HEADBARS_2 block key — the channel live combat NPCs actually use for hitsplats +
     * health bars (the wider 32-bit-damage variant). Preferred over [npcHitmarksAndHeadbars].
     */
    @Volatile
    var npcHitmarksAndHeadbars2: NpcUpdateMaskKey? = null

    /**
     * Absolute LE bit positions of the expansion ("continue") bits in the PLAYER_INFO ext-info
     * flag bitset header, for the active revision. Index N is the continue-bit that must be set in
     * byte N so the client reads byte N+1. Published by codec registration from each revision's
     * `EXPANSION_BITS` array so `PlayerInfoBuilder` never hardcodes per-revision literals.
     */
    @Volatile
    var playerExpansionBits: IntArray = intArrayOf(0, 14, 18)

    /**
     * Absolute LE bit positions of the expansion ("continue") bits in the NPC_INFO ext-info flag
     * bitset header, for the active revision. Same index convention as [playerExpansionBits].
     */
    @Volatile
    var npcExpansionBits: IntArray = intArrayOf(6, 13, 22, 24)
}
