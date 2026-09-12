package org.projectx.packetlog

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.projectx.core.sqlite.SqliteDriver
import org.projectx.packetlog.agent.AgentStatus
import org.projectx.packetlog.agent.PacketAgent
import org.projectx.packetlog.chunk.ChunkEvent
import org.projectx.packetlog.store.PacketSchema
import org.projectx.packetlog.store.PacketSessionWriter
import org.projectx.packetlog.store.PacketStore
import org.projectx.packetlog.store.ProtEntry
import org.projectx.packetlog.store.SessionFiles
import org.projectx.packetlog.store.SessionMetadata
import org.projectx.packetlog.upload.RedactionPolicy
import org.projectx.packetlog.upload.UploadCredentials
import org.projectx.packetlog.upload.UploadService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The agent exists because the client cannot be trusted to report its own death, so the case that
 * matters here is the one no in-process hook can serve: a writer that is simply gone, leaving a
 * session unsealed and a lock the kernel has released.
 */
class PacketAgentTest {

    private lateinit var server: HttpServer
    private lateinit var endpoint: String
    private lateinit var realHome: String

    private val received = ConcurrentHashMap<String, MutableSet<Long>>()
    private val completed = ConcurrentHashMap.newKeySet<String>()

    private val protTable = listOf(ProtEntry(949, 0, 45, "PLAYER_INFO", -2))

    /**
     * The agent resolves every path it uses from the home directory, so redirecting it is what keeps
     * a test off the developer's own captures and credentials.
     */
    @BeforeEach
    fun start() {
        realHome = System.getProperty("user.home")
        System.setProperty("user.home", Files.createTempDirectory("agent-home").toFile().absolutePath)

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        endpoint = "http://localhost:${server.address.port}"
        server.createContext("/v1/enroll") {
            it.respond(200, """{"deviceId":"device-1","deviceSecret":"secret-1"}""")
        }
        server.createContext("/v1/token") {
            it.respond(200, """{"token":"t","expiresAtMs":${Long.MAX_VALUE}}""")
        }
        server.createContext("/v1/sessions") { exchange ->
            val path = exchange.requestURI.path
            val uuid = path.removePrefix("/v1/sessions/").substringBefore('/')
            when {
                path.endsWith("/heartbeat") -> {
                    exchange.requestBody.readAllBytes()
                    exchange.respond(200, """{"ok":true}""")
                }
                path.endsWith("/complete") -> {
                    completed.add(uuid)
                    exchange.respond(200, """{"sessionUuid":"$uuid","haveOrdinals":[],"state":"sealed"}""")
                }
                path.contains("/chunks/") -> {
                    exchange.requestBody.readAllBytes()
                    val ordinal = path.substringAfterLast('/').toLong()
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
    fun stop() {
        server.stop(0)
        System.setProperty("user.home", realHome)
    }

    @Test
    fun `only one agent runs at a time`() {
        PacketAgent.claim().use { first ->
            assertNotNull(first, "the first agent takes the lock")
            assertNull(PacketAgent.claim(), "a second one finds it held and stands down")
        }
        PacketAgent.claim().use { assertNotNull(it, "and the lock is free again once it exits") }
    }

    /**
     * The whole point, end to end: nobody told the agent anything. It found an unsealed session, saw
     * that no live writer held it, finished it, sent it, and closed it out on the server.
     */
    @Test
    fun `a session whose writer was killed is sealed, uploaded and completed`() {
        val uuid = abandonSessionMidCapture()

        // Retention off, so the local database is still there to be inspected. What it does with the
        // file afterwards is a separate question, tested separately.
        PacketAgent.claim()!!.use {
            PacketAgent.runCycle(keepingLocalCopies())
        }

        assertTrue(received[uuid]?.isNotEmpty() == true, "the recovered chunks reached the server")
        assertTrue(uuid in completed, "and the session was closed out rather than left open")

        val file = SessionFiles.fileFor(directory(), UUID.fromString(uuid))
        SqliteDriver.register()
        DriverManager.getConnection("jdbc:sqlite:file:${file.absolutePath}?mode=ro").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT ended_epoch_ms, close_reason FROM session").use { rows ->
                    assertTrue(rows.next())
                    assertTrue(rows.getLong(1) > 0, "the local session is stamped as ended")
                    assertEquals("recovered", rows.getString(2))
                }
                statement.executeQuery("SELECT COUNT(*) FROM pending").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(0, rows.getInt(1), "and its unsealed tail was re-sealed, not dropped")
                }
            }
        }
    }

    /**
     * The corpus is the point of uploading, so once the server has confirmed it holds and sealed a
     * capture, the local copy is redundant - and captures are large enough that never removing them
     * filled the directory.
     */
    @Test
    fun `a capture the server has sealed is removed from disk`() {
        val uuid = abandonSessionMidCapture()
        val file = SessionFiles.fileFor(directory(), UUID.fromString(uuid))
        assertTrue(file.isFile)

        PacketAgent.claim()!!.use {
            PacketAgent.runCycle(UploadService(endpoint, { credentials() }, RedactionPolicy.DEFAULT))
        }

        assertTrue(uuid in completed, "it reached the server first")
        assertFalse(file.exists(), "and then the local copy went")
        for (suffix in listOf("-wal", "-shm", ".lock")) {
            assertFalse(File(file.parentFile, file.name + suffix).exists(), "$suffix went with it")
        }
    }

    /**
     * ⛔ A session that was never uploaded must survive. Deleting on "the cycle finished" rather than
     * on "the server has it" would throw away the captures that need keeping most.
     */
    @Test
    fun `a capture the server would not take is kept`() {
        val uuid = abandonSessionMidCapture(uploadOptIn = false)
        val file = SessionFiles.fileFor(directory(), UUID.fromString(uuid))

        PacketAgent.claim()!!.use {
            PacketAgent.runCycle(UploadService(endpoint, { credentials() }, RedactionPolicy.DEFAULT))
        }

        assertFalse(uuid in completed, "it was never uploadable")
        assertTrue(file.isFile, "so it is still here")
    }

    /**
     * The counterpart to "a capture the server has sealed is removed from disk" - retention trades
     * automatic deletion for a marker, so the file keeps existing for local querying and `purge`
     * becomes the only thing that can remove it.
     */
    @Test
    fun `a capture kept locally after upload is marked rather than deleted`() {
        val uuid = abandonSessionMidCapture()
        val file = SessionFiles.fileFor(directory(), UUID.fromString(uuid))

        PacketAgent.claim()!!.use {
            PacketAgent.runCycle(keepingLocalCopies())
        }

        assertTrue(uuid in completed, "it still reached the server")
        assertTrue(file.isFile, "but the local copy survives")
        assertTrue(SessionFiles.isUploaded(file), "and is marked so purge can find it later")
    }

    @Test
    fun `purge removes only sessions the server has confirmed`() {
        val uploadedUuid = abandonSessionMidCapture()
        val uploadedFile = SessionFiles.fileFor(directory(), UUID.fromString(uploadedUuid))
        PacketAgent.claim()!!.use { PacketAgent.runCycle(keepingLocalCopies()) }
        assertTrue(SessionFiles.isUploaded(uploadedFile))

        val neverUploadedUuid = abandonSessionMidCapture(uploadOptIn = false)
        val neverUploadedFile = SessionFiles.fileFor(directory(), UUID.fromString(neverUploadedUuid))

        val result = SessionFiles.purgeUploaded(directory())

        assertEquals(1, result.purged)
        assertFalse(uploadedFile.exists(), "the confirmed capture is gone")
        assertTrue(result.bytesFreed > 0)
        assertTrue(neverUploadedFile.isFile, "a capture nothing ever confirmed is never touched")
    }

    @Test
    fun `purge leaves a session a live client still owns alone`() {
        val owned = SessionFiles.claim(directory())!!
        PacketStore.open(owned.file, metadata(), protTable, owned.uuid).use { store ->
            store.closeSession(1_700_000_010_000L, "clean")
        }
        // Simulates the marker arriving while this run's writer is still the owner - the lock, not
        // the marker, is what purge must defer to.
        SessionFiles.markUploaded(owned.file)

        val result = SessionFiles.purgeUploaded(directory())
        owned.close()

        assertEquals(0, result.purged)
        assertEquals(1, result.stillLive)
        assertTrue(owned.file.isFile)
    }

    @Test
    fun `purge respects an age cutoff`() {
        val uuid = abandonSessionMidCapture()
        val file = SessionFiles.fileFor(directory(), UUID.fromString(uuid))
        PacketAgent.claim()!!.use { PacketAgent.runCycle(keepingLocalCopies()) }

        val untouched = SessionFiles.purgeUploaded(directory(), olderThanMs = 60_000)
        assertEquals(0, untouched.purged, "just-sealed capture is younger than the cutoff")
        assertTrue(file.isFile)

        file.setLastModified(System.currentTimeMillis() - 120_000)
        val purged = SessionFiles.purgeUploaded(directory(), olderThanMs = 60_000)
        assertEquals(1, purged.purged, "now old enough to go")
        assertFalse(file.exists())
    }

    @Test
    fun `a session a client is still writing is never deleted`() {
        val owned = SessionFiles.claim(directory())!!
        PacketStore.open(owned.file, metadata(), protTable, owned.uuid).use { store ->
            store.closeSession(1_700_000_010_000L, "clean")
        }

        // The lock is still held - a live writer - even though the session reads as finished.
        PacketAgent.claim()!!.use {
            PacketAgent.runCycle(UploadService(endpoint, { credentials() }, RedactionPolicy.DEFAULT))
        }
        owned.close()

        assertTrue(owned.file.isFile, "the file a running client owns is left alone")
    }

    @Test
    fun `sidecars whose database is gone are swept up`() {
        directory().mkdirs()
        val orphan = File(directory(), "11111111-2222-3333-4444-555555555555.db-wal")
        orphan.writeText("")

        PacketAgent.claim()!!.use {
            PacketAgent.runCycle(UploadService(endpoint, { credentials() }, RedactionPolicy.DEFAULT))
        }

        assertFalse(orphan.exists())
    }

    @Test
    fun `a cycle leaves a status the engine can read`() {
        abandonSessionMidCapture()

        PacketAgent.claim()!!.use {
            PacketAgent.runCycle(keepingLocalCopies())
        }

        val status = AgentStatus.read(PacketAgent.statusFile())
        assertNotNull(status)
        assertTrue(status!!.chunksSent > 0)
        assertEquals(0, status.liveSessions, "no writer still holds anything")
    }

    private fun directory() = SessionFiles.directoryFor(PacketSchema.ServerProfile.LIVE)

    /**
     * Leaves exactly what a SIGKILL leaves: sealed chunks, an unsealed tail in the spill table, no
     * `ended_epoch_ms`, and a lock the kernel has released.
     */
    private fun abandonSessionMidCapture(uploadOptIn: Boolean = true): String {
        val owned = SessionFiles.claim(directory())!!
        val store = PacketStore.open(owned.file, metadata(uploadOptIn), protTable, owned.uuid)
        val writer = PacketSessionWriter(store, 949, PacketSessionWriter.SealPolicy(maxEvents = 20))
        repeat(50) { i ->
            store.beginFlush()
            writer.append(
                ChunkEvent(store.assignSequence(), 1_700_000_000_000L + i, i * 1_000_000L, i / 5, 0, 45,
                    PacketSchema.Quality.OK, ByteArray(4) { (i + it).toByte() })
            )
            store.commitFlush()
            writer.pump(1_700_000_000_000L + i)
        }
        repeat(200) {
            writer.pump(1_700_000_000_050L)
            if (writer.pendingSeals == 0) return@repeat
            Thread.sleep(5)
        }
        writer.pump(1_700_000_000_050L)
        store.close()
        owned.close()
        return owned.uuid.toString()
    }

    private fun credentials() = UploadCredentials("device-1", "secret-1", "salt")

    private fun keepingLocalCopies() =
        UploadService(endpoint, { credentials() }, RedactionPolicy.DEFAULT, deleteAfterUpload = false)

    private fun metadata(uploadOptIn: Boolean = true) = SessionMetadata(
        player = "tester", world = 1, startedEpochMs = 1_700_000_000_000L, monoBaseNs = 0,
        revision = 949, protTableHash = ByteArray(32), platform = "linux", arch = "x86_64",
        clientBuild = "949-5", engineBuild = "test",
        serverProfile = PacketSchema.ServerProfile.LIVE, peerHash = null,
        consentVersion = 1, uploadOptIn = uploadOptIn, provenance = "test_v1",
    )

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
