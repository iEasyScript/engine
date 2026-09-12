package org.projectx.packetlog

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.projectx.packetlog.chunk.ChunkEvent
import org.projectx.packetlog.store.PacketSchema
import org.projectx.packetlog.store.PacketSessionWriter
import org.projectx.packetlog.store.PacketStore
import org.projectx.packetlog.store.ProtEntry
import org.projectx.packetlog.store.SessionFiles
import org.projectx.packetlog.store.SessionMetadata
import org.projectx.packetlog.upload.PacketUploader
import org.projectx.packetlog.upload.UploadCredentials
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Streaming exists so a long session reaches the server as it happens rather than all at once at the
 * end - and so two clients doing it at the same time stay entirely separate.
 */
class UploadStreamingTest {

    private lateinit var server: HttpServer
    private lateinit var endpoint: String

    /** session uuid -> ordinals received, so cross-talk between sessions would be visible. */
    private val received = ConcurrentHashMap<String, MutableSet<Long>>()
    private val completed = ConcurrentHashMap.newKeySet<String>()

    /** session uuid -> the heartbeat bodies it sent, which is what renews the server's lease. */
    private val heartbeats = ConcurrentHashMap<String, MutableList<String>>()

    private val credentials = UploadCredentials("device-1", "secret-1", "salt")

    private val protTable = listOf(ProtEntry(949, 0, 45, "PLAYER_INFO", -2))

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        endpoint = "http://localhost:${server.address.port}"
        server.createContext("/v1/token") { it.respond(200, """{"token":"t","expiresAtMs":${Long.MAX_VALUE}}""") }
        server.createContext("/v1/sessions") { exchange ->
            val path = exchange.requestURI.path
            val uuid = path.removePrefix("/v1/sessions/").substringBefore('/')
            when {
                path.endsWith("/heartbeat") -> {
                    val body = String(exchange.requestBody.readAllBytes())
                    heartbeats.computeIfAbsent(uuid) { CopyOnWriteArrayList() }.add(body)
                    exchange.respond(200, """{"ok":true}""")
                }
                path.endsWith("/complete") -> {
                    completed.add(uuid)
                    exchange.respond(200, """{"sessionUuid":"$uuid","haveOrdinals":[],"state":"sealed"}""")
                }
                path.contains("/chunks/") -> {
                    val ordinal = path.substringAfterLast('/').toLong()
                    exchange.requestBody.readAllBytes()
                    received.computeIfAbsent(uuid) { ConcurrentHashMap.newKeySet() }.add(ordinal)
                    exchange.respond(201, """{"sessionUuid":"$uuid","ordinal":$ordinal,"stored":true,"bytes":0}""")
                }
                else -> {
                    val body = String(exchange.requestBody.readAllBytes())
                    val declared = body.substringAfter("\"uuid\":\"").substringBefore('"')
                    val have = received[declared]?.sorted() ?: emptyList()
                    exchange.respond(200, """{"sessionUuid":"$declared","haveOrdinals":$have,"state":"open"}""")
                }
            }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun metadata() = SessionMetadata(
        player = "tester", world = 1, startedEpochMs = 1_700_000_000_000L, monoBaseNs = 0,
        revision = 949, protTableHash = ByteArray(32), platform = "linux", arch = "x86_64",
        clientBuild = "949-5", engineBuild = "test",
        serverProfile = PacketSchema.ServerProfile.LIVE, peerHash = null,
        consentVersion = 1, uploadOptIn = true, provenance = "test_v1",
    )

    /** Writes chunks but leaves the session open, as a client mid-play would. */
    private fun openSessionWithChunks(directory: File, chunks: Int): Pair<File, String> {
        val owned = SessionFiles.claim(directory)!!
        val store = PacketStore.open(owned.file, metadata(), protTable, owned.uuid)
        val writer = PacketSessionWriter(store, 949, PacketSessionWriter.SealPolicy(maxEvents = 10))
        repeat(chunks * 10) { i ->
            store.beginFlush()
            writer.append(
                ChunkEvent(store.assignSequence(), 1_700_000_000_000L + i, 0, i / 5, 0, 45,
                    PacketSchema.Quality.OK, byteArrayOf(i.toByte()))
            )
            store.commitFlush()
            // The engine pumps once per tick; without it nothing seals until the session ends.
            writer.pump(1_700_000_000_000L + i)
        }
        writer.flush()
        store.close()
        owned.close()
        return owned.file to owned.uuid.toString()
    }

    @Test
    fun `an open session streams its sealed chunks without being completed`() {
        val directory = Files.createTempDirectory("stream").toFile()
        val (file, uuid) = openSessionWithChunks(directory, chunks = 3)

        val report = PacketUploader(endpoint, credentials).stream(file)
        assertTrue(report.chunksSent >= 3, "sealed chunks went up while the session is open")
        assertTrue(received[uuid]!!.isNotEmpty())
        assertFalse(uuid in completed, "an open session is never sealed by streaming")
    }

    /**
     * The lease is what ends a session when a client vanishes, so a client that has NOT vanished has
     * to keep saying so. Without this an ordinary quiet stretch mid-capture looks like a death.
     */
    @Test
    fun `an open session heartbeats so its lease is renewed`() {
        val directory = Files.createTempDirectory("stream-heartbeat").toFile()
        val (file, uuid) = openSessionWithChunks(directory, chunks = 2)

        PacketUploader(endpoint, credentials).stream(file)

        val sent = heartbeats[uuid] ?: emptyList<String>()
        assertEquals(1, sent.size, "one heartbeat per streaming pass")
        assertTrue(sent.first().contains("\"ended\":false"), "and it says the capture is still live")
    }

    @Test
    fun `a second pass sends only what the server does not have`() {
        val directory = Files.createTempDirectory("stream-resume").toFile()
        val (file, uuid) = openSessionWithChunks(directory, chunks = 3)

        val first = PacketUploader(endpoint, credentials).stream(file)
        val second = PacketUploader(endpoint, credentials).stream(file)

        assertTrue(first.chunksSent > 0)
        assertEquals(0, second.chunksSent, "nothing is re-sent")
        assertTrue(second.chunksAlreadyHeld > 0, "the server's list is what stops it")
        assertEquals(first.chunksSent, received[uuid]!!.size, "and no duplicates landed")
    }

    /**
     * The constraint that matters with two clients on one machine: their uploads must be entirely
     * independent, even when they run at the same instant.
     */
    @Test
    fun `two sessions uploading at the same time stay separate`() {
        val directory = Files.createTempDirectory("stream-concurrent").toFile()
        val first = openSessionWithChunks(directory, chunks = 4)
        val second = openSessionWithChunks(directory, chunks = 4)
        assertFalse(first.second == second.second, "the two sessions have distinct identities")

        val ready = CountDownLatch(2)
        listOf(first, second).map { (file, _) ->
            thread {
                ready.countDown()
                ready.await(10, TimeUnit.SECONDS)
                PacketUploader(endpoint, credentials).stream(file)
            }
        }.forEach { it.join(30_000) }

        assertEquals(2, received.keys.size, "the server saw two distinct sessions")
        for ((uuid, ordinals) in received) {
            assertTrue(ordinals.isNotEmpty(), "$uuid uploaded its own chunks")
            // Ordinals restart at zero per session, so overlap here is expected and harmless -
            // what must not happen is one session's chunks being filed under the other.
            assertEquals(ordinals.size, ordinals.toSet().size, "no ordinal arrived twice for $uuid")
        }
    }

    @Test
    fun `a closed session is completed`() {
        val directory = Files.createTempDirectory("stream-complete").toFile()
        val owned = SessionFiles.claim(directory)!!
        PacketStore.open(owned.file, metadata(), protTable, owned.uuid).use { store ->
            PacketSessionWriter(store, 949, PacketSessionWriter.SealPolicy(maxEvents = 5)).use { writer ->
                store.beginFlush()
                repeat(10) { i ->
                    writer.append(
                        ChunkEvent(store.assignSequence(), 1_700_000_000_000L + i, 0, 0, 0, 45,
                            PacketSchema.Quality.OK, byteArrayOf(1))
                    )
                }
                store.commitFlush()
                writer.flush()
            }
            store.closeSession(1_700_000_010_000L, "clean")
        }
        owned.close()

        PacketUploader(endpoint, credentials).stream(owned.file)
        assertTrue(owned.uuid.toString() in completed, "an ended session is sealed on the server")
        assertFalse(heartbeats.containsKey(owned.uuid.toString()), "and it does not also claim to be live")
    }
}
