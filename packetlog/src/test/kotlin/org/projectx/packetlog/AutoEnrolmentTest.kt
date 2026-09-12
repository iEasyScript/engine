package org.projectx.packetlog

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.projectx.packetlog.upload.AutoEnrolment
import org.projectx.packetlog.upload.UploadCredentials
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Registration has to be automatic and silent: a corpus only accumulates if every machine that
 * captures also uploads, and anything a person has to remember to run is a thing that does not
 * happen.
 */
class AutoEnrolmentTest {

    private lateinit var server: HttpServer
    private lateinit var endpoint: String
    private lateinit var credentialsFile: File

    private val requests = AtomicInteger(0)
    private val registrations = AtomicInteger(0)
    private val seenInstallIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var enrollStatus = 200

    @BeforeEach
    fun start() {
        credentialsFile = File(Files.createTempDirectory("enrol").toFile(), "credentials.json")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        endpoint = "http://localhost:${server.address.port}"
        server.createContext("/v1/enroll") { exchange ->
            requests.incrementAndGet()
            val body = String(exchange.requestBody.readAllBytes())
            seenInstallIds.add(body.substringAfter("\"installId\":\"").substringBefore('"'))
            if (enrollStatus !in 200..299) {
                exchange.respond(enrollStatus, """{"error":"refused","message":"this machine is banned"}""")
                return@createContext
            }
            val n = registrations.incrementAndGet()
            exchange.respond(200, """{"deviceId":"device-$n","deviceSecret":"secret-$n"}""")
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

    @Test
    fun `a fresh machine registers itself with no invite and no command`() {
        val result = AutoEnrolment.ensure(endpoint, credentialsFile)
        assertTrue(result is AutoEnrolment.Result.Ready, "registration succeeded unprompted")
        val credentials = (result as AutoEnrolment.Result.Ready).credentials
        assertTrue(credentials.enrolled)
        assertTrue(credentialsFile.isFile, "the credential is persisted")
        assertEquals(1, registrations.get())
    }

    @Test
    fun `an already registered machine does not register again`() {
        AutoEnrolment.ensure(endpoint, credentialsFile)
        AutoEnrolment.ensure(endpoint, credentialsFile)
        AutoEnrolment.ensure(endpoint, credentialsFile)
        assertEquals(1, registrations.get(), "the stored credential is reused")
    }

    /** Two clients starting together share one credential file and must not mint two credentials. */
    @Test
    fun `concurrent first runs on one machine produce exactly one credential`() {
        val ready = CountDownLatch(4)
        val results = ConcurrentHashMap.newKeySet<String>()
        val threads = (1..4).map {
            thread {
                ready.countDown()
                ready.await(10, TimeUnit.SECONDS)
                val result = AutoEnrolment.ensure(endpoint, credentialsFile)
                if (result is AutoEnrolment.Result.Ready) results.add(result.credentials.deviceId)
            }
        }
        threads.forEach { it.join(30_000) }

        assertEquals(1, registrations.get(), "the file lock stopped a duplicate registration")
        assertEquals(1, results.size, "every client ended up on the same credential")
        assertEquals(1, seenInstallIds.size, "and reported one machine identity")
    }

    /** The install id outlives a credential, so a ban targets the machine rather than the secret. */
    @Test
    fun `the machine identity is stable across a credential being replaced`() {
        AutoEnrolment.ensure(endpoint, credentialsFile)
        val first = UploadCredentials.load(credentialsFile)

        UploadCredentials.save(UploadCredentials.copyOf(first, deviceId = "", deviceSecret = ""), credentialsFile)
        AutoEnrolment.ensure(endpoint, credentialsFile)
        val second = UploadCredentials.load(credentialsFile)

        assertEquals(first.installId, second.installId, "the machine identity survived")
        assertNotEquals(first.deviceId, second.deviceId, "but the credential is a new one")
        assertEquals(2, registrations.get())
    }

    /** A refusal has to stick, or an open registration endpoint is trivially defeated. */
    @Test
    fun `a refused machine stops asking`() {
        enrollStatus = 403
        val refused = AutoEnrolment.ensure(endpoint, credentialsFile)
        assertTrue(refused is AutoEnrolment.Result.Blocked)
        assertTrue(UploadCredentials.load(credentialsFile).blocked, "the refusal is remembered")

        enrollStatus = 200
        val again = AutoEnrolment.ensure(endpoint, credentialsFile)
        assertTrue(again is AutoEnrolment.Result.Blocked, "it does not simply register again")
        assertEquals(1, requests.get(), "and did not reach the server a second time")
    }

    /** An unreachable server is a "later", not a "no". */
    @Test
    fun `an unreachable server defers rather than blocking`() {
        val result = AutoEnrolment.ensure("http://localhost:1", credentialsFile)
        assertTrue(result is AutoEnrolment.Result.Deferred)
        assertFalse(UploadCredentials.load(credentialsFile).blocked, "a transient failure is not a ban")
    }

    /** A server that has not shipped open registration yet must not be treated as a ban. */
    @Test
    fun `an unsupported registration endpoint defers`() {
        enrollStatus = 404
        val result = AutoEnrolment.ensure(endpoint, credentialsFile)
        assertTrue(result is AutoEnrolment.Result.Deferred)
        assertFalse(UploadCredentials.load(credentialsFile).blocked)
    }
}
