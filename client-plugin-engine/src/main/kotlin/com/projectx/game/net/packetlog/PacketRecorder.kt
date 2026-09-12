package com.projectx.game.net.packetlog

import com.projectx.BuildInfo
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.input.InputRecorder
import com.projectx.game.nxt.OffsetTable
import com.projectx.game.platform.Platform
import com.projectx.util.Configuration
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import org.projectx.core.net.prot.ProtRevisions
import org.projectx.packetlog.chunk.ChunkEvent
import org.projectx.packetlog.store.CapturePolicy
import org.projectx.packetlog.store.PacketRecovery
import org.projectx.packetlog.store.PacketSchema
import org.projectx.packetlog.store.PacketSchema.CaptureMode
import org.projectx.packetlog.store.PacketSchema.Quality
import org.projectx.packetlog.store.PacketSessionWriter
import org.projectx.packetlog.store.PacketStore
import org.projectx.packetlog.store.SessionFiles
import org.projectx.packetlog.store.ProtTable
import org.projectx.packetlog.store.SessionMetadata

/**
 * Records captured packets into the packet-log database.
 *
 * Capture hooks call [record] from the client's network thread while it holds `Bootstrap.lock`, so
 * that path does nothing but enqueue - no decode, no compression, no database. Everything else runs
 * in [tickFlush] on the game thread. Start and stop are requests rather than actions because the UI
 * toggles run on the render thread.
 *
 * Off unless [Configuration] says otherwise: this writes a durable record of everything the server
 * sends, so it is opt-in rather than something a user discovers after the fact.
 */
object PacketRecorder {

    /** Bounded by bytes as well as count: a single map rebuild is ~100 KB, so a count-only cap is an OOM. */
    private const val PENDING_BYTES_LIMIT = 32L * 1024 * 1024

    private const val PENDING_COUNT_LIMIT = 200_000

    /** Roughly three minutes. A world hop between a live and a local server must not go unnoticed. */
    private const val PROFILE_RECHECK_TICKS = 300

    private val queue = ConcurrentLinkedDeque<Captured>()
    private val pendingCount = AtomicLong(0)
    private val pendingBytes = AtomicLong(0)
    private val droppedCount = AtomicLong(0)

    /** Resolved by name at session open, so no opcode is written into engine source. */
    private var inputEventOpcodes = IntArray(0)

    private var writer: PacketSessionWriter? = null
    private var store: PacketStore? = null

    /** Held for the life of the session; releasing it is what marks the file recoverable. */
    private var sessionLock: SessionFiles.Owned? = null

    private var lastTick = -1
    private var ticksSinceProfileCheck = 0
    private var tickFirstMs = 0L
    private var tickLastMs = 0L
    private var tickPackets = 0

    /**
     * Capture is armed from injection, but a session cannot be opened until the client has connected
     * to something: the server it is talking to is what decides which database the session belongs
     * to, and at injection time there is no connection to read. Packets captured before then are
     * held in the queue, so the login handshake is not lost waiting for the answer.
     */
    @Volatile private var armed = false

    @Volatile private var stopRequested = false

    private class Captured(
        val epochMs: Long,
        val monoNs: Long,
        val gameTick: Int,
        val dir: Int,
        val opcode: Int,
        val quality: Quality,
        val body: ByteArray,
    )

    /** Capture is on. A session may not be open yet - see [armed]. */
    val isArmed: Boolean get() = armed

    /** A session is open and packets are reaching the database. */
    @Volatile
    var isRecording: Boolean = false
        private set

    var databaseFile: File? = null
        private set

    var profile: PacketSchema.ServerProfile = PacketSchema.ServerProfile.UNKNOWN
        private set

    var profileDetail: String = "not started"
        private set

    var packetsWritten: Long = 0
        private set

    val droppedPackets: Long get() = droppedCount.get()
    val queueDepth: Long get() = pendingCount.get()
    val queueBytes: Long get() = pendingBytes.get()
    val openChunkEvents: Int get() = writer?.openEvents ?: 0
    val sealsInFlight: Int get() = writer?.pendingSeals ?: 0

    fun requestStart() {
        stopRequested = false
        armed = true
    }

    fun requestStop() {
        armed = false
        stopRequested = true
    }

    fun recordServer(opcode: Int, payload: ByteArray) =
        enqueue(PacketSchema.Direction.SERVER_TO_CLIENT.code, opcode, Quality.OK, payload)

    fun recordClient(opcode: Int, payload: ByteArray) =
        enqueue(PacketSchema.Direction.CLIENT_TO_SERVER.code, opcode, Quality.OK, payload)

    /** A body the capture layer could not read coherently. Recorded, withheld, never fabricated. */
    fun recordServerDesync(opcode: Int) =
        enqueue(PacketSchema.Direction.SERVER_TO_CLIENT.code, opcode, Quality.DESYNC_WITHHELD, ByteArray(0))

    private fun enqueue(dir: Int, opcode: Int, quality: Quality, payload: ByteArray) {
        if (!armed) return
        val tick = runCatching { Bootstrap.client.clientCycle }.getOrDefault(lastTick.coerceAtLeast(0))
        queue.addLast(
            Captured(
                epochMs = System.currentTimeMillis(),
                monoNs = System.nanoTime(),
                gameTick = tick,
                dir = dir,
                opcode = opcode,
                quality = quality,
                body = payload,
            )
        )
        pendingCount.incrementAndGet()
        pendingBytes.addAndGet(payload.size.toLong())
        enforceLimit()
    }

    /**
     * Overflow leaves a marker rather than a hole: the dropped packet is replaced by an empty
     * [Quality.DROPPED_OVERFLOW] record so the sequence stays dense and every gap in the archive is
     * explained by a row instead of by absence.
     */
    private fun enforceLimit() {
        while (pendingCount.get() > PENDING_COUNT_LIMIT || pendingBytes.get() > PENDING_BYTES_LIMIT) {
            val evicted = queue.pollFirst() ?: return
            pendingCount.decrementAndGet()
            pendingBytes.addAndGet(-evicted.body.size.toLong())
            queue.addFirst(
                Captured(evicted.epochMs, evicted.monoNs, evicted.gameTick, evicted.dir, evicted.opcode,
                    Quality.DROPPED_OVERFLOW, ByteArray(0))
            )
            pendingCount.incrementAndGet()
            droppedCount.incrementAndGet()
        }
    }

    /** Called once per game tick. The only place the database is touched. */
    fun tickFlush() {
        if (stopRequested) {
            stopRequested = false
            closeSession("clean")
            return
        }
        if (!armed) return
        if (store == null) {
            // Nothing to attribute a session to yet, or nothing to write into it. Keep buffering.
            if (pendingCount.get() == 0L) return
            val connection = peer()
            if (connection is Peer.None) return
            openSession(ServerProfileResolver.resolve(connection))
        } else if (++ticksSinceProfileCheck >= PROFILE_RECHECK_TICKS) {
            ticksSinceProfileCheck = 0
            rotateIfServerChanged()
        }
        val active = writer ?: return
        val open = store ?: return

        val drained = ArrayList<Captured>(minOf(pendingCount.get(), 4096L).toInt())
        while (true) {
            val next = queue.pollFirst() ?: break
            pendingCount.decrementAndGet()
            pendingBytes.addAndGet(-next.body.size.toLong())
            drained.add(next)
            if (drained.size >= 8192) break
        }

        if (drained.isNotEmpty()) {
            open.beginFlush()
            try {
                val keepInput = InputRecorder.isRecording
                for (captured in drained) {
                    var mode = open.capturePolicy.modeFor(captured.dir, captured.opcode)
                    if (mode == CaptureMode.DROP) continue
                    if (!keepInput && isInputEvent(captured)) mode = CaptureMode.COUNT_ONLY
                    val event = ChunkEvent(
                        seq = open.assignSequence(),
                        epochMs = captured.epochMs,
                        monoNs = captured.monoNs,
                        gameTick = captured.gameTick,
                        dir = captured.dir,
                        opcode = captured.opcode,
                        quality = captured.quality,
                        body = if (mode == CaptureMode.COUNT_ONLY) ByteArray(0) else captured.body,
                    )
                    active.append(event)
                    accumulateTick(open, event)
                    packetsWritten++
                }
                open.commitFlush()
            } catch (e: Exception) {
                open.rollbackFlush()
                println("[packetlog] flush failed, ${drained.size} packets not spilled: ${e.message}")
            }
        }

        runCatching { active.pump(System.currentTimeMillis()) }
    }

    private fun isInputEvent(captured: Captured): Boolean =
        captured.dir == PacketSchema.Direction.CLIENT_TO_SERVER.code &&
            inputEventOpcodes.any { it == captured.opcode }

    private fun accumulateTick(open: PacketStore, event: ChunkEvent) {
        if (event.gameTick != lastTick) {
            if (lastTick >= 0 && tickPackets > 0) open.addTick(lastTick, tickFirstMs, tickLastMs, tickPackets)
            lastTick = event.gameTick
            tickFirstMs = event.epochMs
            tickPackets = 0
        }
        tickLastMs = event.epochMs
        tickPackets++
    }

    /**
     * Closes and reopens the session when the client moves between the live and the local server
     * inside one injection, so a single session can never span two of them.
     */
    private fun rotateIfServerChanged() {
        val connection = peer()
        if (connection is Peer.None) return
        val resolution = ServerProfileResolver.resolve(connection)
        if (resolution.profile == profile) return
        println("[packetlog] server changed (${profile.wireName} -> ${resolution.profile.wireName}), rotating session")
        closeSession("server_changed")
        armed = true
        openSession(resolution)
    }

    private fun openSession(resolution: ServerProfileResolver.Resolution) {
        profile = resolution.profile
        profileDetail = resolution.detail

        val directory = SessionFiles.directoryFor(profile)

        // Finish any session a previous run left behind - but only ones no live client still owns,
        // which is what the lock decides. Another client's running session must never be touched.
        runCatching {
            var sessions = 0
            var resealed = 0
            for (abandoned in SessionFiles.abandoned(directory)) {
                val result = PacketRecovery.recover(abandoned)
                sessions += result.sessions
                resealed += result.resealed
            }
            if (sessions > 0) println("[packetlog] recovered $sessions abandoned session(s), re-sealed $resealed packets")
        }.onFailure { println("[packetlog] recovery failed: ${it.message}") }

        val owned = SessionFiles.claim(directory)
        if (owned == null) return failStart("could not claim a session file in $directory")
        sessionLock = owned
        val file = owned.file

        val revision = ProtRevisions.current
        val codec = ProtRevisions.currentCodec()
        val protTable = ProtTable.snapshot(revision, codec)
        inputEventOpcodes = codec.clientProtInfo
            .filter { it.value.name in CapturePolicy.INPUT_EVENT_PROTS }
            .keys.toIntArray()
        val metadata = SessionMetadata(
            player = runCatching { Bootstrap.client.loggedInPlayer.getPlayerName() }.getOrNull(),
            world = null,
            startedEpochMs = System.currentTimeMillis(),
            monoBaseNs = System.nanoTime(),
            revision = revision,
            protTableHash = ProtTable.hash(protTable),
            platform = Platform.current.id,
            arch = Platform.arch,
            clientBuild = runCatching { OffsetTable.build }.getOrDefault("unknown"),
            engineBuild = BuildInfo.VERSION,
            serverProfile = profile,
            peerHash = resolution.peerHash,
            consentVersion = Configuration.config.packetLogConsentVersion,
            uploadOptIn = Configuration.config.packetLogUploadEnabled,
            provenance = "live_v${PacketSchema.VERSION}",
        )

        runCatching { PacketSessionWriter.open(file, metadata, protTable, uuid = owned.uuid) }
            .onSuccess {
                writer = it
                store = it.store
                databaseFile = file
                isRecording = true
                if (Configuration.config.packetLogUploadEnabled) PacketUploadRunner.start()
                lastTick = -1
                packetsWritten = 0
                println("[packetlog] recording into ${file.absolutePath} (${resolution.detail})")
            }
            .onFailure { failStart("could not open ${file.absolutePath}: ${it.message}") }
    }

    private fun failStart(reason: String) {
        println("[packetlog] not recording - $reason")
        isRecording = false
        writer = null
        store = null
        sessionLock?.close()
        sessionLock = null
    }

    private fun closeSession(reason: String) {
        isRecording = false
        val active = writer
        val open = store
        writer = null
        store = null
        // Release the claim whatever happened above: a file nobody holds is recoverable, one whose
        // lock leaked would be skipped by recovery forever.
        val releaseLock = {
            runCatching {
                sessionLock?.close()
                sessionLock = null
            }.onFailure { println("[packetlog] releasing the session lock failed: ${it.message}") }
        }
        if (active == null || open == null) {
            releaseLock()
            return
        }

        runCatching {
            if (lastTick >= 0 && tickPackets > 0) {
                open.beginFlush()
                open.addTick(lastTick, tickFirstMs, tickLastMs, tickPackets)
                open.commitFlush()
            }
            active.close()
            open.recordDropped(droppedCount.get())
            open.closeSession(System.currentTimeMillis(), reason)
            open.close()
        }.onFailure { println("[packetlog] close failed: ${it.message}") }
        releaseLock()
        println("[packetlog] stopped. packets=$packetsWritten dropped=${droppedCount.get()}")
    }

    /**
     * Called from engine teardown, which is a hot reload - not a client close.
     *
     * ⛔ There is deliberately no shutdown hook behind this. A JVM embedded in the client does not
     * run one when the host process exits or is killed, so the hook that used to live here never
     * fired for the case it was written for, and its presence is what hid that for so long. A
     * session the client takes to the grave is finished by the capture agent instead, which is a
     * separate process and sees the death through the released file lock.
     */
    fun shutdown() {
        // Stop the capture hooks before touching the database: at exit the game thread may still be
        // mid-tick, and this runs on a different one.
        armed = false
        drainRemaining()
        queue.clear()
        pendingCount.set(0)
        pendingBytes.set(0)
        if (isRecording) closeSession("engine_reload")
    }

    /** Spills whatever the game thread never got to, so the tail is recovered rather than lost. */
    private fun drainRemaining() {
        val open = store ?: return
        val remaining = ArrayList<Captured>()
        while (true) remaining.add(queue.pollFirst() ?: break)
        if (remaining.isEmpty()) return
        runCatching {
            open.beginFlush()
            for (captured in remaining) {
                writer?.append(
                    ChunkEvent(
                        seq = open.assignSequence(),
                        epochMs = captured.epochMs,
                        monoNs = captured.monoNs,
                        gameTick = captured.gameTick,
                        dir = captured.dir,
                        opcode = captured.opcode,
                        quality = captured.quality,
                        body = captured.body,
                    )
                )
            }
            open.commitFlush()
        }.onFailure { open.rollbackFlush() }
    }

    private fun peer(): Peer = runCatching { PacketPeer.current() }.getOrDefault(Peer.Unavailable)
}
