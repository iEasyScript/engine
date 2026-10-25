package org.projectx.core.net.prot.revision.rev951

import org.projectx.core.net.prot.Codec

/**
 * Opcodes and sizes for every 951 prot, straight from the client's own registration. Names are left
 * `UNKNOWN_<op>` until each is verified against this build: a major revision re-scrambles every opcode and
 * every packet layout, so nothing carried from 950 is trusted, and there are no decoders yet.
 *
 * SERVER_TICK_END is verified from a live capture: the only empty server packet that arrives once per 600 ms
 * tick. Declaration-order carry had pointed at a different empty packet that never arrives in game.
 *
 * REBUILD_REGION is verified from live captures: the only varShort server packet that arrives once per instance
 * entry, and every capture decodes under the 950 layout to a zone grid that ends on its last bit and covers the
 * coordinates of the instance's own scenery.
 */
fun register951() = Codec.register(951) {
    registerRev951ServerProtStubs()
    registerRev951ClientProtStubs()
}
