package org.projectx.packetlog.upload

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.projectx.packetlog.store.PacketRecovery
import org.projectx.packetlog.store.PacketSchema
import org.projectx.packetlog.store.SessionFiles

/**
 * Pushes captures to the ingest server in the background.
 *
 * Runs on its own daemon thread and never touches the capture path: a network problem must slow
 * nothing down and lose nothing, so a failed cycle simply leaves the chunks on disk to be retried.
 * The archive is the source of truth and uploading is a mirror of it.
 *
 * Work is scoped per session file, which is what keeps two clients on one machine - or two people
 * entirely - from interfering: a session is owned by exactly one writer, uploaded under its own
 * identity, and the server keys chunks by `(session, ordinal)`, so concurrent uploads cannot collide
 * even when they land at the same instant.
 */
class UploadService(
    private val endpoint: String,
    private val credentials: () -> UploadCredentials,
    private val policy: RedactionPolicy = RedactionPolicy.DEFAULT,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val profiles: List<PacketSchema.ServerProfile> = listOf(
        PacketSchema.ServerProfile.LIVE,
        PacketSchema.ServerProfile.LOCAL,
    ),
    /**
     * Delete a session file once the server has confirmed it holds and has sealed it.
     *
     * On by default because captures are large and nothing ever removed them, so the directory grew
     * without bound. Off keeps the local copy queryable after it uploads - [SessionFiles.markUploaded]
     * records that it is safe to remove later, and [SessionFiles.purgeUploaded] is how a user actually
     * reclaims that space once they no longer need the capture.
     */
    private val deleteAfterUpload: Boolean = true,
) {

    private val running = AtomicBoolean(false)
    private val cyclesRun = AtomicLong(0)
    private val chunksSent = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private val filesDeleted = AtomicLong(0)

    @Volatile private var worker: Thread? = null
    @Volatile private var lastError: String? = null
    @Volatile private var lastRunMs: Long = 0

    /** Backs off after a failure so an outage does not become a tight retry loop. */
    @Volatile private var backoffUntilMs: Long = 0
    @Volatile private var blocked = false
    private var consecutiveFailures = 0

    val isRunning: Boolean get() = running.get()
    val cycles: Long get() = cyclesRun.get()
    val uploadedChunks: Long get() = chunksSent.get()
    val uploadedBytes: Long get() = bytesSent.get()
    val deletedFiles: Long get() = filesDeleted.get()
    val error: String? get() = lastError

    /** The server refused this machine; there is nothing to retry until an operator lifts it. */
    val isBlocked: Boolean get() = blocked
    val lastRunEpochMs: Long get() = lastRunMs

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ loop() }, "packetlog-upload").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    private fun loop() {
        while (running.get()) {
            runCatching { Thread.sleep(intervalMs) }.onFailure { return }
            if (!running.get()) return
            if (System.currentTimeMillis() < backoffUntilMs) continue
            runOnce()
        }
    }

    /**
     * One pass over every session file. Sessions that are still open are streamed rather than
     * completed, so a long play session reaches the server as it happens instead of all at once at
     * the end.
     */
    fun runOnce(): Int {
        // Registration is automatic and happens here rather than at start-up, so a machine that was
        // offline when the engine loaded simply registers on the first cycle that reaches the server.
        val ready = when (val enrolment = AutoEnrolment.ensure(endpoint)) {
            is AutoEnrolment.Result.Ready -> enrolment.credentials
            is AutoEnrolment.Result.Blocked -> {
                lastError = "refused by $endpoint: ${enrolment.reason}"
                blocked = true
                stop()
                return 0
            }
            is AutoEnrolment.Result.Deferred -> {
                lastError = enrolment.reason
                backOff()
                return 0
            }
        }
        var sent = 0
        val uploader = PacketUploader(endpoint, ready, policy)
        for (profile in profiles) {
            val directory = SessionFiles.directoryFor(profile)
            if (!directory.isDirectory) continue
            // A killed client leaves its session unfinished, and an unfinished session is never
            // completed on the server - so finish anything no live client still owns before
            // uploading. Recovery skips held files, so this cannot disturb a running capture.
            for (abandoned in SessionFiles.abandoned(directory)) {
                runCatching { PacketRecovery.recover(abandoned) }
                    .onFailure { lastError = "recovering ${abandoned.name}: ${it.message}" }
            }
            for (session in SessionFiles.all(directory)) {
                if (!running.get() && worker != null) return sent
                sent += uploadOne(uploader, session)
            }
            // Sidecars whose database is gone outlive it silently, and nothing else looks for them.
            SessionFiles.removeOrphanedSidecars(directory)
        }
        cyclesRun.incrementAndGet()
        lastRunMs = System.currentTimeMillis()
        return sent
    }

    private fun uploadOne(uploader: PacketUploader, session: File): Int =
        runCatching { uploader.stream(session) }
            .onSuccess { report ->
                chunksSent.addAndGet(report.chunksSent.toLong())
                bytesSent.addAndGet(report.bytesSent)
                lastError = null
                consecutiveFailures = 0
                backoffUntilMs = 0
                if (report.safeToDelete) {
                    if (deleteAfterUpload) discard(session) else SessionFiles.markUploaded(session)
                }
            }
            .onFailure { failure ->
                lastError = "${session.name}: ${failure.message}"
                // A revoked credential is not a transient failure: drop it so the next cycle
                // registers again, unless the server said this machine is refused outright.
                if (failure.message?.contains("401") == true) {
                    UploadCredentials.save(
                        UploadCredentials.copyOf(credentials(), deviceId = "", deviceSecret = "")
                    )
                }
                backOff()
            }
            .getOrNull()?.chunksSent ?: 0

    /**
     * Removes a capture the server has taken.
     *
     * ⛔ Claimed first. The report says the file was complete when it was read, but a client can open
     * a *new* session in the same directory in between, and taking the lock is what proves this
     * particular file is not the one being written to right now.
     */
    private fun discard(session: File) {
        val removed = SessionFiles.ifUnowned(session) { SessionFiles.delete(session) } ?: return
        if (removed) filesDeleted.incrementAndGet()
    }

    private fun backOff() {
        consecutiveFailures++
        // Full jitter, capped, so a fleet coming back after an outage does not synchronise.
        val capped = minOf(BASE_BACKOFF_MS shl minOf(consecutiveFailures, 8), MAX_BACKOFF_MS)
        backoffUntilMs = System.currentTimeMillis() + (Math.random() * capped).toLong()
    }

    companion object {
        /**
         * Short because this runs in the capture agent, a separate idle process, rather than beside
         * the game. A chunk that has been sealed for a minute is a minute the corpus cannot see it.
         */
        const val DEFAULT_INTERVAL_MS = 10_000L

        private const val BASE_BACKOFF_MS = 30_000L
        private const val MAX_BACKOFF_MS = 30 * 60 * 1000L
    }
}
