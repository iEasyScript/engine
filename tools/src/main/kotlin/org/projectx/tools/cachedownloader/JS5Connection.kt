package org.projectx.tools.cachedownloader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import world.gregs.voidps.cache.secure.CRC
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

data class FileRequest(val index: Int, val archive: Int, val version: Int = 0, val crc: Int = 0)

class JS5Connection(
    private val id: Int,
    private val host: String,
    private val port: Int,
    private val major: Int,
    private val minor: Int,
    private val token: String,
    private val queue: Channel<FileRequest>,
    private val storage: CacheStorage,
    private val progress: ProgressTracker
) {
    private val retryQueue = ConcurrentLinkedQueue<FileRequest>()
    private val sentRequests = ConcurrentLinkedQueue<FileRequest>()
    private val inflight = Semaphore(20)
    private val completedThisConnection = AtomicInteger(0)

    // Set just before the deliberate end-of-work socket close so the receiver (blocked in a
    // socket read) can tell the resulting SocketException apart from a genuine mid-download error.
    @Volatile
    private var closing = false

    suspend fun run() {
        delay(id * 500L)

        var backoff = 1000L
        var consecutiveFailures = 0
        while (true) {
            try {
                completedThisConnection.set(0)
                connect()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                while (sentRequests.isNotEmpty()) {
                    val req = sentRequests.poll() ?: break
                    retryQueue.add(req)
                    inflight.release()
                }

                // Terminal condition: nothing to retry, nothing in flight, and the shared
                // queue is closed and drained. Reconnecting would just spin on an empty
                // workload forever, so this worker is done.
                if (retryQueue.isEmpty() && sentRequests.isEmpty()) {
                    val remaining = queue.tryReceive().getOrNull()
                    if (remaining != null) {
                        retryQueue.add(remaining)
                    } else if (queue.isClosedForReceive) {
                        return
                    }
                }

                if (completedThisConnection.get() == 0 && retryQueue.isNotEmpty()) {
                    consecutiveFailures++
                    if (consecutiveFailures >= 3) {
                        val bad = retryQueue.poll()
                        if (bad != null) {
                            System.err.println("\nConnection $id: skipping unservable (${bad.index}, ${bad.archive})")
                            inflight.release()
                            progress.fail()
                        }
                        consecutiveFailures = 0
                    }
                } else {
                    consecutiveFailures = 0
                }

                if (completedThisConnection.get() > 0) {
                    backoff = 1000L
                }

                System.err.println("\nConnection $id error: ${e::class.simpleName}: ${e.message}. Reconnecting in ${backoff}ms...")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }

    private suspend fun connect() {
        closing = false
        val socket = withContext(Dispatchers.IO) {
            Socket(host, port).also { it.soTimeout = 30_000 }
        }
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 1024 * 1024))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))

        try {
            withContext(Dispatchers.IO) {
                val response = JS5Protocol.handshake(output, input, major, minor, token)
                when (response) {
                    0 -> { /* SYNC */ }
                    6 -> throw IllegalStateException("GAME_UPDATE: version $major.$minor rejected")
                    else -> throw IllegalStateException("Handshake failed: $response")
                }
                JS5Protocol.sendConnectionInit(output, major)
            }

            // Mid-download errors (receiver/sender IO failures) must propagate to run(),
            // which re-queues the in-flight requests and reconnects with backoff.
            try {
                coroutineScope {
                    val senderJob = launch(Dispatchers.IO) { sender(output) }
                    val receiverJob = launch(Dispatchers.IO) { receiver(input) }
                    senderJob.join()
                    while (sentRequests.isNotEmpty()) {
                        delay(100)
                    }
                    // All requested files have arrived; tear the connection down. The receiver
                    // is blocked in a socket read, so closing the socket is what unblocks it.
                    // Mark closing and pre-cancel the receiver first so the SocketException
                    // raised by our own close is treated as normal termination, not an error.
                    closing = true
                    receiverJob.cancel()
                    withContext(Dispatchers.IO) { socket.close() }
                }
            } catch (e: IOException) {
                // Only the self-inflicted close above may be swallowed; genuine IO failures
                // while not closing must still reach run() for re-queue + reconnect.
                if (!closing) throw e
            }
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private suspend fun sender(output: DataOutputStream) {
        while (retryQueue.isNotEmpty()) {
            val request = retryQueue.poll() ?: break
            inflight.acquire()
            sentRequests.add(request)
            JS5Protocol.sendFileRequest(output, request.index, request.archive, major)
        }

        for (request in queue) {
            inflight.acquire()
            sentRequests.add(request)
            JS5Protocol.sendFileRequest(output, request.index, request.archive, major)
        }
    }

    private fun receiver(input: DataInputStream) {
        // Jagex interleaves block segments of different responses on one connection, so
        // each segment's 5-byte id header is read here and routed to the matching
        // JS5Protocol.ResponseAssembler (the canonical block-framing implementation).
        val pending = HashMap<Long, JS5Protocol.ResponseAssembler>()
        var segmentCount = 0

        try {
            while (true) {
                val headerIndex = input.readUnsignedByte()
                val headerHash = input.readInt()
                val headerArchive = headerHash and 0x7FFFFFFF
                segmentCount++

                val key = (headerIndex.toLong() shl 32) or headerArchive.toLong()
                val resp = pending.getOrPut(key) {
                    JS5Protocol.ResponseAssembler(headerIndex, headerArchive)
                }

                if (resp.readSegment(input)) {
                    pending.remove(key)
                    val container = resp.container!!
                    val crc = CRC.calculate(container, 0, container.size)
                    val request = sentRequests.find { it.index == resp.index && it.archive == resp.archive }
                    storage.store(resp.index, resp.archive, container, request?.version ?: 0, crc)
                    progress.complete(container.size)
                    completedThisConnection.incrementAndGet()
                    sentRequests.removeIf { it.index == resp.index && it.archive == resp.archive }
                    inflight.release()
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            // Closing is only set once every in-flight request has completed, so any
            // exception after that point is teardown noise from our own socket close.
            if (closing) return
            System.err.println("\nConnection $id receiver error after $segmentCount segments, ${completedThisConnection.get()} completed, ${pending.size} pending: ${e::class.simpleName}: ${e.message}")
            throw e
        }
    }
}
