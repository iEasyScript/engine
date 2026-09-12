package com.projectx.game.input.wire

import com.projectx.game.hooks.impl.SendEventMouseClickCapture
import com.projectx.game.nxt.OffsetTable

/**
 * Capability probe for the movement-history packet - the only mechanism that actually reports cursor position
 * to the server.
 *
 * MainLogicManager owns **two** rings with identical entry layout: a small click ring drained one entry per
 * tick by the click sender, and a much larger movement ring drained in a loop by a separate, variable-length
 * movement-history prot. Motion flows only through the movement ring.
 *
 * The click ring's button field is a button **id**, not a "was this a click" flag: the per-tick minimenu
 * update reads a queued entry as "where the click happened" and treats the zero value as the left button,
 * falling back to the live cursor position when the ring is empty. The click packet puts `button != 0` in its
 * top bit - the right-button bit - so there is no encoding of it that means "the cursor moved without
 * clicking". Writing a zero-button entry and sending therefore announces a **left click**, once per tick.
 *
 * So the trail cannot be built from the click sender at all. It needs the movement ring plus the
 * movement-history prot: tiered position deltas, coarsely quantised time deltas, a back-patched sample count,
 * same-position samples elided, and emitted on a staleness interval rather than every tick.
 */
internal object EventMouseMoveSupport {
    val unavailableReason: String?
        get() = when {
            MouseRing.movement() == null ->
                "the movement ring is not reachable - MainLogicManager or its offsets are unavailable on " +
                    "the ${OffsetTable.platform} build ${OffsetTable.build} table"
            OffsetTable.function("CLIENTPROT_FLUSHMOUSEPROTSENDER") == null ->
                "ClientProt::FlushMouseProtSender has no entry in the ${OffsetTable.platform} build " +
                    "${OffsetTable.build} offset table, so the history stage cannot be driven"
            SendEventMouseClickCapture.lastClientProtAddr == 0L ->
                "waiting for the first click-sender fire to capture the ClientProt aggregate"
            else -> null
        }
}
