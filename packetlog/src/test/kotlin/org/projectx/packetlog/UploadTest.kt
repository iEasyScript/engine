package org.projectx.packetlog

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import org.projectx.packetlog.chunk.ChunkEvent
import org.projectx.packetlog.chunk.ChunkFrame
import org.projectx.packetlog.store.PacketSchema
import org.projectx.packetlog.store.PacketSessionWriter
import org.projectx.packetlog.store.PacketStore
import org.projectx.packetlog.store.ProtEntry
import org.projectx.packetlog.store.SessionMetadata
import org.projectx.packetlog.upload.PacketUploader
import org.projectx.packetlog.upload.RedactionPolicy
import org.projectx.packetlog.upload.UploadCredentials
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Drives the uploader against a real HTTP server that mimics the ingest contract, so the wire
 * protocol is exercised rather than mocked - a client that agrees with a mock and disagrees with the
 * server is the failure this is here to catch.
 */
class UploadTest {

    private lateinit var server: HttpServer
    private lateinit var endpoint: String

    private val received = ConcurrentHashMap<Long, ByteArray>()
    private val digests = ConcurrentHashMap<Long, String>()
    private var haveOrdinals: List<Long> = emptyList()
    private var completed = false
    private var manifest: String? = null
    private var authorizedRequests = 0

    private val credentials = UploadCredentials(
        deviceId = "device-test",
        deviceSecret = "secret-test",
        pseudonymSalt = "salt",
    )

    private val protTable = listOf(
        ProtEntry(949, 0, 45, "PLAYER_INFO", -2),
        ProtEntry(949, 0, 75, "MESSAGE_PRIVATE", -2),
        ProtEntry(949, 0, 28, "UPDATE_ZONE_FULL_FOLLOWS", 3),
        ProtEntry(949, 1, 35, "MESSAGE_PUBLIC", -1),
    )

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        endpoint = "http://localhost:${server.address.port}"

        server.createContext("/v1/token") { exchange ->
            exchange.respond(200, """{"token":"tok-1","expiresAtMs":${System.currentTimeMillis() + 60000}}""")
        }
        server.createContext("/v1/sessions") { exchange ->
            if (exchange.requestHeaders.getFirst("Authorization") == "Bearer tok-1") authorizedRequests++
            val path = exchange.requestURI.path
            when {
                path.endsWith("/complete") -> {
                    completed = true
                    exchange.respond(200, """{"sessionUuid":"x","haveOrdinals":[],"state":"sealed"}""")
                }
                path.contains("/chunks/") -> {
                    val ordinal = path.substringAfterLast('/').toLong()
                    digests[ordinal] = exchange.requestHeaders.getFirst("X-Plain-Sha256") ?: ""
                    received[ordinal] = exchange.requestBody.readAllBytes()
                    exchange.respond(201, """{"sessionUuid":"x","ordinal":$ordinal,"stored":true,"bytes":0}""")
                }
                else -> {
                    manifest = String(exchange.requestBody.readAllBytes())
                    exchange.respond(200, """{"sessionUuid":"x","haveOrdinals":$haveOrdinals,"state":"open"}""")
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

    private fun archive(uploadOptIn: Boolean = true, kind: PacketSchema.ServerProfile = PacketSchema.ServerProfile.LIVE): File {
        val file = File(Files.createTempDirectory("packetlog-upload").toFile(), "archive.db")
        val metadata = SessionMetadata(
            player = "tester", world = 1, startedEpochMs = 1_700_000_000_000L, monoBaseNs = 0,
            revision = 949, protTableHash = ByteArray(32), platform = "linux", arch = "x86_64",
            clientBuild = "949-5", engineBuild = "test", serverProfile = kind, peerHash = null,
            consentVersion = 1, uploadOptIn = uploadOptIn, provenance = "test_v1",
        )
        PacketStore.open(file, metadata, protTable).use { store ->
            PacketSessionWriter(store, 949, PacketSessionWriter.SealPolicy(maxEvents = 4)).use { writer ->
                store.beginFlush()
                val events = listOf(
                    Triple(0, 45, byteArrayOf(1, 2, 3)),
                    Triple(0, 75, byteArrayOf(0x53, 0x45, 0x43)),
                    Triple(1, 35, byteArrayOf(0x48, 0x49)),
                    Triple(0, 28, byteArrayOf(9)),
                )
                for ((i, event) in events.withIndex()) {
                    writer.append(
                        ChunkEvent(store.assignSequence(), 1_700_000_000_000L + i, 0, 0,
                            event.first, event.second, PacketSchema.Quality.OK, event.third)
                    )
                }
                store.commitFlush()
                writer.flush()
            }
            store.closeSession(1_700_000_010_000L, "clean")
        }
        return file
    }

    @Test
    fun `chat and social bodies never leave the machine`() {
        val report = PacketUploader(endpoint, credentials).upload(archive())
        assertEquals(1, report.sessions)
        assertEquals(1, report.chunksSent)
        assertEquals(1, report.redactedChunks, "the chunk carried redacted prots")

        val events = ChunkFrame.open(received.getValue(0L))
        val byOpcode = events.associateBy { it.dir to it.opcode }

        assertEquals(0, byOpcode.getValue(0 to 75).body.size, "MESSAGE_PRIVATE body stripped")
        assertEquals(0, byOpcode.getValue(1 to 35).body.size, "MESSAGE_PUBLIC body stripped")
        assertEquals(3, byOpcode.getValue(0 to 45).body.size, "PLAYER_INFO survives - it is the scene")
        assertEquals(1, byOpcode.getValue(0 to 28).body.size, "an unrelated prot is untouched")
        assertEquals(4, events.size, "redaction empties bodies, it does not remove events")
    }

    @Test
    fun `the strict policy also drops the name-carrying prots`() {
        PacketUploader(endpoint, credentials, RedactionPolicy.STRICT).upload(archive())
        val byOpcode = ChunkFrame.open(received.getValue(0L)).associateBy { it.dir to it.opcode }
        assertEquals(0, byOpcode.getValue(0 to 45).body.size, "PLAYER_INFO dropped under strict")
        assertEquals(1, byOpcode.getValue(0 to 28).body.size, "scene geometry still survives")
    }

    @Test
    fun `the digest header describes what was actually sent`() {
        PacketUploader(endpoint, credentials).upload(archive())
        val frame = received.getValue(0L)
        assertEquals(
            PacketUploader.sha256Hex(frame),
            digests.getValue(0L),
            "the digest must cover the redacted frame, not the original",
        )
    }

    /** Resume is why registration reports what the server holds. */
    @Test
    fun `chunks the server already holds are not resent`() {
        haveOrdinals = listOf(0L)
        val report = PacketUploader(endpoint, credentials).upload(archive())
        assertEquals(0, report.chunksSent)
        assertEquals(1, report.chunksAlreadyHeld)
        assertTrue(received.isEmpty(), "nothing was put")
    }

    @Test
    fun `a session recorded without consent is never uploaded`() {
        val report = PacketUploader(endpoint, credentials).upload(archive(uploadOptIn = false))
        assertEquals(0, report.sessions)
        assertEquals(1, report.skipped.size)
        assertTrue(report.skipped.single().contains("consent"))
        assertFalse(completed)
    }

    @Test
    fun `an unattributed session is never uploaded`() {
        val report = PacketUploader(endpoint, credentials)
            .upload(archive(kind = PacketSchema.ServerProfile.UNKNOWN))
        assertEquals(0, report.sessions)
        assertTrue(report.skipped.single().contains("attributed"))
    }

    @Test
    fun `a dry run sends nothing`() {
        val report = PacketUploader(endpoint, credentials).upload(archive(), dryRun = true)
        assertEquals(1, report.chunksSent, "it still reports what it would send")
        assertTrue(received.isEmpty())
        assertFalse(completed)
    }

    @Test
    fun `every request carries the bearer token and the manifest names the policy`() {
        PacketUploader(endpoint, credentials).upload(archive())
        assertTrue(authorizedRequests >= 3, "register, put and complete were all authorized")
        assertTrue(manifest!!.contains("\"redactionPolicy\":\"prot-v1\""), "manifest declares the policy")
        assertTrue(completed)
    }

    @Test
    fun `plaintext endpoints are refused`() {
        val failure = runCatching {
            PacketUploader("http://rs3-data.projectx.org", credentials).upload(archive())
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "a plaintext push target is refused outright")
    }
}
