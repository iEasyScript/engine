package org.projectx.packetlog.store

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.projectx.packetlog.chunk.ChunkEvent
import org.projectx.packetlog.chunk.ChunkFrame
import org.projectx.packetlog.chunk.SealedChunk

/**
 * Drives one capture session: spills events, seals full chunks on a worker, and commits finished
 * frames back on the caller's thread.
 *
 * [append], [pump] and [close] must all be called from the same thread - the one that owns the
 * database. Compression is the only work that leaves it, because a chunk takes tens of milliseconds
 * to compress and the caller here is the game thread.
 */
class PacketSessionWriter(
    val store: PacketStore,
    private val revision: Int,
    private val policy: SealPolicy = SealPolicy(),
) : AutoCloseable {

    /**
     * [maxEvents] is the primary trigger; the byte cap only stops one burst from producing a chunk
     * too large to decompress for a single-packet query.
     *
     * [maxAgeMs] closes an under-full chunk, and it is short on purpose. A sealed chunk is the
     * smallest thing that can be uploaded, so the age cap is also how long a capture in progress
     * stays invisible to the corpus. Longer cadences do compress better - a quiet session pays for
     * this in ratio - but nothing downstream can see a session until it produces its first chunk,
     * and that mattered more. Crash safety is still the spill table's job, not this.
     */
    class SealPolicy(
        val maxEvents: Int = 8192,
        val maxPlainBytes: Long = 512L * 1024,
        val maxAgeMs: Long = 30_000,
    )

    private val open = ArrayList<ChunkEvent>(policy.maxEvents)
    private var openBodyBytes = 0L
    private var openStartedMs = 0L
    private var lastMs = Long.MIN_VALUE
    private var lastTick = Int.MIN_VALUE
    private var clamped = 0L

    private val sealer: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "packetlog-sealer").apply { isDaemon = true }
        }

    private val finished = ConcurrentLinkedQueue<SealedChunk>()
    private val inFlight = AtomicInteger(0)
    private var failures = 0L

    val pendingSeals: Int get() = inFlight.get()
    val openEvents: Int get() = open.size
    val sealFailures: Long get() = failures

    /** Events whose clock reading went backwards and was pinned forward. See [append]. */
    val clampedTimestamps: Long get() = clamped

    /**
     * The chunk columns are delta-encoded and unsigned, so they need a non-decreasing sequence. A
     * wall clock is not: an NTP correction mid-session moves it backwards, and over hours that is a
     * question of when rather than whether. Pin such a reading to its predecessor and count it,
     * rather than rejecting the batch - the alternative is a chunk that can never seal, which would
     * cost real packets to protect a millisecond of timing.
     */
    fun append(event: ChunkEvent) {
        val monotonic = when {
            event.epochMs >= lastMs && event.gameTick >= lastTick -> event
            else -> {
                clamped++
                ChunkEvent(
                    seq = event.seq,
                    epochMs = maxOf(event.epochMs, lastMs),
                    monoNs = event.monoNs,
                    gameTick = maxOf(event.gameTick, lastTick),
                    dir = event.dir,
                    opcode = event.opcode,
                    quality = event.quality,
                    body = event.body,
                )
            }
        }
        lastMs = monotonic.epochMs
        lastTick = monotonic.gameTick
        if (open.isEmpty()) openStartedMs = monotonic.epochMs
        open.add(monotonic)
        openBodyBytes += monotonic.body.size
        store.addPending(monotonic)
    }

    /** Commits whatever the sealer finished and starts a new seal if the policy says so. */
    fun pump(nowMs: Long) {
        drainFinished()
        if (shouldSeal(nowMs)) seal()
    }

    /** Seals whatever is open regardless of policy, then waits for every outstanding frame. */
    fun flush(timeoutMs: Long = 30_000) {
        if (open.isNotEmpty()) seal()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            drainFinished()
            Thread.sleep(5)
        }
        drainFinished()
    }

    private fun shouldSeal(nowMs: Long): Boolean {
        if (open.isEmpty()) return false
        return open.size >= policy.maxEvents ||
            openBodyBytes >= policy.maxPlainBytes ||
            nowMs - openStartedMs >= policy.maxAgeMs
    }

    private fun seal() {
        val batch = ArrayList(open)
        open.clear()
        openBodyBytes = 0
        lastMs = Long.MIN_VALUE
        lastTick = Int.MIN_VALUE
        inFlight.incrementAndGet()
        sealer.execute {
            try {
                finished.add(ChunkFrame.seal(batch))
            } catch (e: Exception) {
                // The spill rows are still there, so the events are not lost - they are re-sealed on
                // the next open. Losing the frame is recoverable; losing the packets would not be.
                failures++
                println("[packetlog] seal failed for ${batch.size} events: ${e.message}")
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun drainFinished() {
        while (true) {
            val sealed = finished.poll() ?: return
            runCatching { store.commitChunk(revision, sealed) }
                .onFailure { println("[packetlog] chunk commit failed: ${it.message}") }
        }
    }

    override fun close() {
        runCatching { flush() }
        sealer.shutdown()
        runCatching { sealer.awaitTermination(30, TimeUnit.SECONDS) }
    }

    companion object {
        fun open(
            file: File,
            metadata: SessionMetadata,
            protTable: List<ProtEntry>,
            policy: SealPolicy = SealPolicy(),
            uuid: UUID = UUID.randomUUID(),
        ): PacketSessionWriter =
            PacketSessionWriter(PacketStore.open(file, metadata, protTable, uuid), metadata.revision, policy)
    }
}
