package com.projectx.game.input.wire

import com.projectx.game.bootstrap.GameThread
import com.projectx.game.hooks.HookManager
import com.projectx.game.hooks.impl.SendEventMouseClickCapture
import com.projectx.game.input.InputArbiter
import com.projectx.game.input.MouseButton
import java.lang.foreign.MemorySegment

/**
 * The server-facing input path: it makes the server observe a cursor trail, clicks and key presses, and
 * produces **no** local input at all. The real cursor is never touched, no raycast runs, no `DoAction` fires,
 * and nothing written here survives long enough for the game's local processors to observe it. The opposite
 * path is `com.projectx.game.input.action.ActionInput`; the two must never be mixed and this package must
 * never reference that one.
 *
 * No packet bytes are crafted in Kotlin. Entries are staged in the client's own rings and the client's own
 * senders build every byte, so on the wire the result is structurally indistinguishable from real input.
 *
 * Position goes through [MovementHistorySender]; a press goes through [sendClick] here, because the two travel
 * in different packets. A press ring entry is a **button press** — its button field is an id whose zero value
 * is the left button, not a "was this a click" flag — so there is no way to express motion through it, and
 * earlier code that tried was announcing a left click every tick.
 */
object WireInput {
    @Volatile var clickPacketsSent: Long = 0
        private set
    @Volatile var skippedRingBusy: Long = 0
        private set
    @Volatile var skippedIncompleteVariants: Long = 0
        private set
    @Volatile var skippedOffGameThread: Long = 0
        private set
    @Volatile var lastSendNanos: Long = 0L
        private set

    @Volatile private var variantsLogged = false

    val isEnabled: Boolean get() = WirePacketVariants.complete

    val movementPacketsSent: Long get() = MovementHistorySender.packetsSent
    val movementSamplesSent: Long get() = MovementHistorySender.samplesWritten
    val trailQueued: Int get() = MovementHistorySender.pendingSamples

    /**
     * Report a run of cursor positions. Samples must be in time order and carry the client's own millisecond
     * clock, since the encoder delta-encodes against it and elides repeats.
     */
    fun sendTrail(samples: List<TrailSample>): Boolean {
        if (samples.isEmpty()) return false
        if (!ready()) return false
        MovementHistorySender.offer(
            samples.map {
                MouseRing.Sample(MouseRing.Sample.MOVEMENT_ONLY, it.x.toFloat(), it.y.toFloat(), it.timestampMs)
            }
        )
        return drainTrail()
    }

    /**
     * Offer the queued trail to the client's sender. Call once per tick even with nothing new: the history
     * stage fires on its own cadence, so a queue can sit for a while before it is taken.
     */
    fun drainTrail(): Boolean {
        if (!ready()) return false
        if (!InputArbiter.mayWireSend()) return false
        return MovementHistorySender.tick()
    }

    val keyPacketsSent: Long get() = KeyPressSender.packetsSent
    val keyPressesSent: Long get() = KeyPressSender.pressesSent
    val keysQueued: Int get() = KeyPressSender.queuedPresses

    /**
     * Queue key presses for the server. They go out on the client's next per-tick flush, built by its own
     * encoder from records the engine owns — the key ring, held-key state, modifiers and CS2 never see them.
     *
     * [protocolKeyId] must be the client's **post-remap** key id, not a platform key code: an unmapped code
     * never reaches the ring in the first place, so the wire only ever carries mapped ids.
     *
     * Presses only. The client records no releases on this path, so there is nothing to encode for one.
     */
    fun sendKeyPresses(presses: List<KeyPress>): Boolean {
        if (presses.isEmpty()) return false
        if (!ready()) return false
        if (!InputArbiter.mayWireSend()) return false
        KeyPressSender.offer(presses.map { KeyPressSender.Press(it.protocolKeyId, it.timestampMs) })
        return true
    }

    data class KeyPress(val protocolKeyId: Int, val timestampMs: Long)

    /**
     * Report a button press at a position. Only a press: the client never records releases on this path, so
     * there is nothing to encode for one.
     */
    fun sendClick(x: Int, y: Int, button: MouseButton, timestampMs: Long): Boolean {
        if (!ready()) return false
        if (!InputArbiter.mayWireSend()) return false

        val clientProtAddr = SendEventMouseClickCapture.lastClientProtAddr
        if (clientProtAddr == 0L) return false
        val ring = MouseRing.clicks() ?: return false

        // A non-empty ring means the player's own press is staged and the tick will send it; a second packet
        // in a window that naturally holds one is the easiest possible signature.
        if (!ring.isEmpty) {
            skippedRingBusy++
            return false
        }

        // The sender consumes the slot the READ cursor points at and does not move either cursor, so the entry
        // goes there and the WRITE cursor is what has to move to make the ring look non-empty.
        val slot = ring.read
        val savedSlot = ring.snapshot(slot)
        val savedWrite = ring.write

        ring.write(slot, MouseRing.Sample(button.id, x.toFloat(), y.toFloat(), timestampMs))

        return try {
            ring.setWrite(ring.advance(slot))
            val clientProt = MemorySegment.ofAddress(clientProtAddr).reinterpret(0x40)
            HookManager.trampoline(SendEventMouseClickCapture::sendEventMouseClickHook.name)
                .invokeExact(clientProt)
            clickPacketsSent++
            lastSendNanos = System.nanoTime()
            true
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        } finally {
            ring.restore(slot, savedSlot)
            ring.setWrite(savedWrite)
        }
    }

    private fun ready(): Boolean {
        // The rings are read and written by the game without locks; an off-thread stash-drain-restore would
        // corrupt an entry the game is mid-way through reading.
        if (!GameThread.isCurrent()) {
            skippedOffGameThread++
            return false
        }
        if (!WirePacketVariants.complete) {
            skippedIncompleteVariants++
            logVariantsOnce()
            return false
        }
        return true
    }

    private fun logVariantsOnce() {
        if (variantsLogged) return
        variantsLogged = true
        println("[WireInput] ${WirePacketVariants.describe()}")
    }
}
