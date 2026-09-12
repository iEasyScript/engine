package com.projectx.game.input.wire

import com.projectx.game.bootstrap.GameThread
import com.projectx.game.hooks.impl.SendEventMouseClickCapture
import com.projectx.game.memory.NativeAccess
import com.projectx.game.memory.NativeAccess.toFunctionHandle
import com.projectx.game.nxt.OClientProt
import com.projectx.game.nxt.OFunctions
import com.projectx.game.nxt.OMouseProtSender
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * Puts a synthetic cursor trail on the wire and nothing else.
 *
 * The client reports position only in a variable-length movement-history packet built from the movement ring,
 * so that is what this drives. It writes samples into the ring, invokes the client's own flush so Jagex's
 * encoder produces every byte, then puts the ring back exactly as it was.
 *
 * **What is restored and what deliberately is not.** The ring slots and both cursors are restored, because the
 * ring is *also* read locally — the interface code peeks the newest entry each tick for the cursor position —
 * and restoring is what keeps our samples invisible to it. The sender's delta state
 * ([OMouseProtSender.LAST_SENT_X], `LAST_SENT_Y`, `LAST_MOVE_SAMPLE_TIME`) is **not** restored: the server's
 * model of the cursor is now ours, so the client's next natural packet must encode its deltas from where we
 * said the cursor was, or the two views diverge on the very next send.
 *
 * Sequencing matches the click path: run from the engine's main-logic hook, which is entered before the
 * client's own tick body, so the whole write/flush/restore window closes before any local consumer runs.
 */
internal object MovementHistorySender {
    @Volatile var packetsSent: Long = 0
        private set
    @Volatile var samplesWritten: Long = 0
        private set
    @Volatile var skippedNaturalPending: Long = 0
        private set
    @Volatile var skippedRingBusy: Long = 0
        private set

    /**
     * Samples waiting for the client's emission predicate to come round.
     *
     * The history stage does **not** fire because the movement ring has content — it fires on a staleness
     * interval, or when a press is queued. So a trail has to be held across ticks and offered repeatedly, or
     * everything produced between two emissions is thrown away. Bounded by the ring, since that is the most
     * one packet can carry.
     */
    private val pending = ArrayDeque<MouseRing.Sample>()

    @Volatile var pendingSamples: Int = 0
        private set
    @Volatile var droppedOverflow: Long = 0
        private set

    /** Queue a run of positions. Emission happens later, on the client's own cadence. */
    fun offer(samples: List<MouseRing.Sample>) {
        if (samples.isEmpty()) return
        synchronized(pending) {
            pending.addAll(samples)
            val limit = capacityLimit()
            while (pending.size > limit) {
                pending.removeFirst()
                droppedOverflow++
            }
            pendingSamples = pending.size
        }
    }

    /**
     * Try to hand the queued trail to the client's sender. A no-op unless its predicate happens to fire this
     * tick, which is exactly the cadence a real client emits history at.
     */
    fun tick(): Boolean {
        val snapshot = synchronized(pending) { if (pending.isEmpty()) null else pending.toList() } ?: return false
        val emitted = send(snapshot)
        if (emitted) {
            synchronized(pending) {
                repeat(minOf(snapshot.size, pending.size)) { pending.removeFirst() }
                pendingSamples = pending.size
            }
        }
        return emitted
    }

    /**
     * Called directly rather than through a hook trampoline: nothing needs to observe the client's own flushes,
     * so hooking this function would add injection risk for no benefit.
     */
    private val flushSender: MethodHandle by lazy {
        NativeAccess.BASE_ADDR.asSlice(OFunctions.CLIENTPROT_FLUSHMOUSEPROTSENDER, 8)
            .toFunctionHandle(FunctionDescriptor.ofVoid(ADDRESS))
    }

    /** The event sender is a sub-object of the ClientProt aggregate the click hook already captures. */
    private fun eventSenderOrNull(): MemorySegment? {
        val clientProt = SendEventMouseClickCapture.lastClientProtAddr
        if (clientProt == 0L) return null
        return try {
            MemorySegment.ofAddress(clientProt + OClientProt.EVENT_MOUSE_SENDER)
                .reinterpret(OMouseProtSender.LAST_CLICK_SAMPLE_TIME + 8L)
        } catch (_: Throwable) {
            null
        }
    }

    private fun capacityLimit(): Int = (MouseRing.movement()?.capacity ?: DEFAULT_CAPACITY) - 1

    private const val DEFAULT_CAPACITY = 200

    /**
     * Stage [samples] and drive one flush.
     *
     * Returns true only when the client's own stage actually built a packet, which is detected by its delta
     * clock advancing — the stage is entered unconditionally but skips when its predicate is false, so "the
     * flush ran" does not mean "a packet went out".
     */
    private fun send(samples: List<MouseRing.Sample>): Boolean {
        if (samples.isEmpty()) return false
        if (!GameThread.isCurrent()) return false

        val sender = eventSenderOrNull() ?: return false
        val ring = MouseRing.movement() ?: return false
        val clicks = MouseRing.clicks() ?: return false

        // Real movement already staged: the client's own flush will send it, and adding ours would either
        // stack a second packet or splice our samples into the player's trail.
        if (!ring.isEmpty) {
            skippedRingBusy++
            return false
        }
        // The history stage fires when a click is pending as well as on staleness, so a queued press means a
        // natural packet is coming this tick regardless.
        if (!clicks.isEmpty) {
            skippedNaturalPending++
            return false
        }
        if (samples.size >= ring.capacity) return false

        val originalWrite = ring.write
        val originalRead = ring.read
        val savedSlots = ArrayList<Pair<Int, MouseRing.Sample>>(samples.size)

        var index = originalWrite
        for (sample in samples) {
            savedSlots.add(index to ring.snapshot(index))
            ring.write(index, sample)
            index = ring.advance(index)
        }

        val clockBefore = sender.get(JAVA_LONG, OMouseProtSender.LAST_MOVE_SAMPLE_TIME)

        return try {
            ring.setWrite(index)
            // invoke, not invokeExact: the latter demands the call site's inferred return type match the
            // handle's exactly, and inside a value-producing block it links as Object against a void handle.
            flushSender.invoke(sender)
            val emitted = sender.get(JAVA_LONG, OMouseProtSender.LAST_MOVE_SAMPLE_TIME) != clockBefore
            if (emitted) {
                packetsSent++
                samplesWritten += samples.size
            }
            emitted
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        } finally {
            for ((slot, saved) in savedSlots) ring.restore(slot, saved)
            ring.setWrite(originalWrite)
            ring.setRead(originalRead)
        }
    }

    /** Where the sender believes the cursor was left, so a caller can continue a trail from it. */
    fun lastSentPosition(): Pair<Int, Int>? {
        val sender = eventSenderOrNull() ?: return null
        return try {
            val x = sender.get(JAVA_INT, OMouseProtSender.LAST_SENT_X)
            val y = sender.get(JAVA_INT, OMouseProtSender.LAST_SENT_Y)
            if (x == -1 || y == -1) null else x to y
        } catch (_: Throwable) {
            null
        }
    }

    fun lastMoveSampleTimeMs(): Long {
        val sender = eventSenderOrNull() ?: return 0L
        return try {
            sender.get(JAVA_LONG, OMouseProtSender.LAST_MOVE_SAMPLE_TIME)
        } catch (_: Throwable) {
            0L
        }
    }
}
