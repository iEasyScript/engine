package org.projectx.core.net.prot.update

/** One NPC vitals slot in an [UpdateMask.NpcStats] block. [current] is u32, [max] is u24. */
data class NpcStatEntry(val slot: Int, val current: Int, val max: Int) {
    companion object {
        const val SLOT_LIFEPOINTS = 3
    }
}
