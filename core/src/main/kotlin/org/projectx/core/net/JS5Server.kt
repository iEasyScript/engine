package org.projectx.core.net

import io.ktor.utils.io.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.projectx.core.EnvVars
import org.projectx.core.Logger.logFinest
import org.projectx.core.Logger.logInfo
import org.projectx.core.Logger.logTrace
import org.projectx.core.Logger.logWarn
import world.gregs.voidps.buffer.*
import world.gregs.voidps.cache.file.FileProvider

data class JS5Request(val index: Int, val group: Int, val urgent: Boolean, val priority: Int, val ref: Long)

sealed interface JS5QueueItem {
    data class FileRequest(val request: JS5Request) : JS5QueueItem
    data class XorKeyUpdate(val key: Int) : JS5QueueItem
}

class JS5Server(val provider: FileProvider, val prefetchKeys: IntArray) {
    val limiter = MultilogLimiter()

    suspend fun init(input: ByteReadChannel, output: ByteWriteChannel, ip: String) {
        if (!limiter.add(ip)) {
            logWarn("JS5 connection rejected (limit): $ip")
            output.finish(ResponseOpcode.LOGIN_LIMIT_EXCEEDED)
            return
        }
        logInfo("JS5 connection from $ip")
        try {
            if (!handshake(input, output, ip)) return
            requestLoop(input, output, ip)
        } finally {
            logFinest("JS5 connection closed: $ip")
            limiter.remove(ip)
        }
    }

    private suspend fun handshake(input: ByteReadChannel, output: ByteWriteChannel, ip: String): Boolean {
        // Read handshake: size(1) + major(4) + minor(4) + token(N+1) + platform(1)
        val size = input.readByte().toInt()
        val major = input.readInt()
        val minor = input.readInt()
        if (major != EnvVars.majorVersion || minor != EnvVars.minorVersion) {
            logWarn("JS5 version mismatch from $ip — got $major.$minor, expected ${EnvVars.majorVersion}.${EnvVars.minorVersion}. Accepting anyway.")
        }
        val token = input.readRSString()
        if (token != EnvVars.js5ServerToken) {
            logWarn("JS5 invalid token from $ip: $token")
            output.writeByte(ResponseOpcode.BAD_SESSION_ID)
            output.flushAndClose()
            return false
        }
        input.readByte() // trailing platform/language byte

        logInfo("JS5 handshake complete for $ip — version $major.$minor")

        // Send SYNC response (no prefetch keys in 946+)
        output.writeByte(ResponseOpcode.JS5_SYNC)
        output.flush()
        logInfo("JS5 sent SYNC to $ip")

        // Read ACK (opcode 6) + READY (opcode 3)
        val ackOpcode = input.readByte().toInt() and 0xFF
        if (ackOpcode != RequestOpcode.ACKNOWLEDGE) {
            logWarn("Expected ACK (6), got $ackOpcode from $ip")
            output.writeByte(ResponseOpcode.LOGIN_SERVER_REJECTED_SESSION)
            output.flushAndClose()
            return false
        }
        readControlPayload(input)

        val readyOpcode = input.readByte().toInt() and 0xFF
        if (readyOpcode != RequestOpcode.STATUS_LOGGED_OUT) {
            logWarn("Expected READY (3), got $readyOpcode from $ip")
        }
        readControlPayload(input)

        logInfo("JS5 ACK + READY complete for $ip")
        return true
    }

    private suspend fun requestLoop(input: ByteReadChannel, output: ByteWriteChannel, ip: String) {
        val urgentChannel = Channel<JS5QueueItem>(URGENT_QUEUE_CAPACITY)
        // UNLIMITED so the reader never suspends on prefetch backpressure: with a bounded
        // prefetch channel, a full prefetch backlog blocked the reader and head-of-line
        // blocked urgent requests sitting behind it in the TCP stream. Requests are tiny
        // (a few dozen bytes each), so an unbounded in-memory queue is safe.
        val prefetchChannel = Channel<JS5QueueItem>(Channel.UNLIMITED)

        coroutineScope {
            val readerJob = launch { reader(input, urgentChannel, prefetchChannel, ip) }
            val writerJob = launch { writer(output, urgentChannel, prefetchChannel, ip) }

            // When either coroutine finishes (normally or exceptionally), cancel the other
            readerJob.invokeOnCompletion { writerJob.cancel() }
            writerJob.invokeOnCompletion { readerJob.cancel() }
        }
    }

    private suspend fun reader(
        input: ByteReadChannel,
        urgentChannel: Channel<JS5QueueItem>,
        prefetchChannel: Channel<JS5QueueItem>,
        ip: String
    ) {
        var requestCount = 0
        var lastRequestTime = System.currentTimeMillis()

        try {
            while (true) {
                var opcodeByte: Byte? = null
                while (opcodeByte == null) {
                    opcodeByte = withTimeoutOrNull(5000) { input.readByte() }
                    if (opcodeByte == null) {
                        val idleSec = (System.currentTimeMillis() - lastRequestTime) / 1000
                        logFinest("JS5 idle ${idleSec}s after $requestCount requests from $ip")
                    }
                }

                val opcode = opcodeByte.toInt() and 0xFF
                requestCount++
                lastRequestTime = System.currentTimeMillis()

                when {
                    RequestOpcode.isFileRequest(opcode) -> {
                        val urgent = RequestOpcode.isUrgent(opcode)
                        val priority = (opcode shr 4) and 0x07
                        val index = input.readByte().toInt() and 0xFF
                        val group = input.readInt()
                        input.readInt() // padding
                        logFinest("JS5 request: index=$index group=$group opcode=$opcode (${if (urgent) "urgent" else "prefetch"} pri=$priority) from $ip")
                        val ref = (index.toLong() shl 32) or (group.toLong() and 0xFFFFFFFFL)
                        val request = JS5Request(index, group, urgent, priority, ref)
                        val item = JS5QueueItem.FileRequest(request)
                        if (urgent) {
                            urgentChannel.send(item)
                        } else {
                            prefetchChannel.send(item)
                        }
                    }

                    opcode == RequestOpcode.STATUS_LOGGED_IN -> {
                        logFinest("JS5 logged in from $ip")
                        readControlPayload(input)
                    }

                    opcode == RequestOpcode.STATUS_LOGGED_OUT -> {
                        logFinest("JS5 logged out from $ip")
                        readControlPayload(input)
                    }

                    opcode == RequestOpcode.XOR_KEY_UPDATE -> {
                        val key = input.readByte().toInt() and 0xFF
                        input.readShort()
                        input.readInt()
                        input.readShort()
                        logFinest("JS5 XOR key set to $key from $ip")
                        // Send to urgent channel so it's applied before any subsequent requests
                        urgentChannel.send(JS5QueueItem.XorKeyUpdate(key))
                    }

                    opcode == RequestOpcode.ACKNOWLEDGE -> {
                        logFinest("JS5 ACK from $ip")
                        readControlPayload(input)
                    }

                    opcode == RequestOpcode.DISCONNECT -> {
                        logInfo("JS5 disconnect from $ip")
                        readControlPayload(input)
                        return
                    }

                    else -> {
                        logWarn("JS5 unknown opcode $opcode from $ip — skipping 9 bytes")
                        input.discard(9)
                    }
                }
            }
        } finally {
            urgentChannel.close()
            prefetchChannel.close()
        }
    }

    private suspend fun writer(
        output: ByteWriteChannel,
        urgentChannel: Channel<JS5QueueItem>,
        prefetchChannel: Channel<JS5QueueItem>,
        ip: String
    ) {
        var xorKey = 0

        while (true) {
            // Drain all available urgent items first
            while (true) {
                val item = urgentChannel.tryReceive().getOrNull() ?: break
                xorKey = processItem(item, output, xorKey, ip)
            }

            // Try a prefetch item
            val prefetchItem = prefetchChannel.tryReceive().getOrNull()
            if (prefetchItem != null) {
                xorKey = processItem(prefetchItem, output, xorKey, ip)
                continue
            }

            // Both channels empty — suspend until something arrives, preferring urgent
            val item = select<JS5QueueItem?> {
                urgentChannel.onReceiveCatching { result ->
                    result.getOrNull()
                }
                prefetchChannel.onReceiveCatching { result ->
                    result.getOrNull()
                }
            }

            if (item == null) {
                // Both channels closed
                return
            }
            xorKey = processItem(item, output, xorKey, ip)
        }
    }

    private suspend fun processItem(item: JS5QueueItem, output: ByteWriteChannel, xorKey: Int, ip: String): Int {
        return when (item) {
            is JS5QueueItem.XorKeyUpdate -> {
                item.key
            }
            is JS5QueueItem.FileRequest -> {
                val req = item.request
                val ok = provider.serve(output, req.ref, prefetch = !req.urgent, xorKey = xorKey)
                if (!ok) {
                    logWarn("JS5 miss: index=${req.index} group=${req.group} from $ip")
                }
                xorKey
            }
        }
    }

    companion object {
        /** Urgent requests are served promptly, so a small bound is plenty. */
        private const val URGENT_QUEUE_CAPACITY = 200

        /** Read 9 bytes of control message payload: medium(3) + int(4) + short(2) */
        private suspend fun readControlPayload(input: ByteReadChannel) {
            input.readMedium()
            input.readInt()
            input.readShort()
        }
    }
}
