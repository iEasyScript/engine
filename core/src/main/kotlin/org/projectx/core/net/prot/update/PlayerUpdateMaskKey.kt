package org.projectx.core.net.prot.update

/**
 * Identity of a single PLAYER_INFO extended-info block within a particular protocol revision. Bit
 * positions and dispatch order are revision-specific, so each revision provides its own table of
 * instances; `PlayerUpdateMaskEncoder` is keyed by this interface so tables from any revision can
 * register simultaneously without collisions.
 */
interface PlayerUpdateMaskKey {
    /** LE bit position within the player-info flag bitset (0..31). */
    val bit: Int
    /** Position in the client's fixed processing order. Encoders MUST serialise in ascending order. */
    val order: Int
    /** Convenience for `1 shl bit`. */
    val flag: Int get() = 1 shl bit
    /** Stable, log-friendly name for diagnostics. */
    val name: String
}
