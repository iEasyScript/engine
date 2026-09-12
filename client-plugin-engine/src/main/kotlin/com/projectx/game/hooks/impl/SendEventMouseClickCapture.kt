package com.projectx.game.hooks.impl

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.HookManager
import com.projectx.game.input.InputRecorder
import com.projectx.game.input.wire.MouseRing
import com.projectx.game.nxt.OFunctions
import java.lang.foreign.MemorySegment

/**
 * Hook on `jag::ClientProt::SendEventMouseClick` — fires once per drained entry
 * (one packet per call). Reads the entry the game is about to send and feeds it
 * into the recorder (for training) and the synth window (so the model sees
 * server-bound history). Does NOT modify the entry or skip the trampoline.
 */
object SendEventMouseClickCapture {
    /** Last-seen ClientProt pointer — captured here so the wire path can drive sends from outside a real hook fire. Zero until the hook fires at least once. */
    @JvmStatic
    @Volatile
    var lastClientProtAddr: Long = 0L

    @JvmStatic
    @Hook("CLIENTPROT_SENDEVENTMOUSECLICK")
    fun sendEventMouseClickHook(clientProt: MemorySegment) {
        if (clientProt.address() != 0L) lastClientProtAddr = clientProt.address()
        try {
            // The hook fires every tick, but the ring only holds an entry when the player actually
            // moved or clicked. Reading tail unconditionally returns a slot the client never wrote —
            // a phantom (0, 0) motion event once per idle tick, which is 38% of a typical recording
            // and would teach the model that the origin is the most human place to put the cursor.
            val ring = MouseRing.clicks()
            if (ring != null && !ring.isEmpty) {
                val entry = ring.snapshot(ring.read)
                if (!entry.isEmptySlot()) {
                    val gameTick = Bootstrap.client.clientCycle
                    if (InputRecorder.isRecording) {
                        InputRecorder.recordServerRing(
                            buttonFlag = entry.button,
                            x = entry.x.toInt(),
                            y = entry.y.toInt(),
                            clientTimestampMs = entry.timestampMs,
                            gameTick = gameTick,
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        HookManager.trampoline(::sendEventMouseClickHook.name).invokeExact(clientProt)
    }
}
