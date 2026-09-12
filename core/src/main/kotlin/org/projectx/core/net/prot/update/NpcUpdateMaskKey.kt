package org.projectx.core.net.prot.update

/**
 * Identity of a single NPC_INFO extended-info block within a particular protocol revision. Bit
 * positions and dispatch order are revision-specific, so each revision provides its own table of
 * instances; `NpcUpdateMaskEncoder` is keyed by this interface so tables from any revision can
 * register simultaneously without collisions. [flag] is a 64-bit Long because NPC masks reach
 * bit 33 in some revisions.
 */
interface NpcUpdateMaskKey {
    /** LE bit position within the NPC-info flag bitset (0..63). */
    val bit: Int
    /** Position in the client's fixed processing order. Encoders MUST serialise in ascending order. */
    val order: Int
    /** Convenience for `1L shl bit`. */
    val flag: Long get() = 1L shl bit
    /** Stable, log-friendly name for diagnostics. */
    val name: String
}
