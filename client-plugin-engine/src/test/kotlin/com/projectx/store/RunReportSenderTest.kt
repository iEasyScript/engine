package com.projectx.store

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunReportSenderTest {
    private lateinit var dir: Path
    private lateinit var store: PendingRunReports
    private val sent = mutableListOf<Pair<JsonObject, String>>()
    private val pauses = mutableListOf<Long>()
    private var answers = ArrayDeque<Int?>()
    private var token: String? = "px_token"
    private val now = 1_800_000_000_000L

    private val transport = RunReportTransport { body, token ->
        sent += JsonParser.parseString(body).asJsonObject to token
        if (answers.isEmpty()) 200 else answers.removeFirst()
    }

    private val sender get() = RunReportSender(store, transport, { token }, { pauses += it }, { now })

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("projectx-sender")
        store = PendingRunReports(dir)
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun body(startedAt: Long = now - 3_600_000L) = RunReport(
        "LividFarm", "Livid Farm", "1.0.0", true, startedAt, startedAt + 600_000L, RunEnd.MANUAL, null, mapOf("FARMING" to 9_000), false,
    ).toJson("RS3 engine test")

    private fun reply(vararg statuses: Int?) {
        answers = ArrayDeque(statuses.toList())
    }

    @Test
    fun `a delivered report leaves nothing behind`() {
        sender.report(body())
        assertEquals(1, sent.size)
        assertEquals("px_token", sent.single().second)
        assertEquals("LividFarm", sent.single().first.get("scriptInternalName").asString)
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `an unreachable site is tried a couple more times, then kept for later`() {
        reply(null, 502, 503)
        sender.report(body())
        assertEquals(3, sent.size)
        assertEquals(listOf(2_000L, 5_000L), pauses)
        assertEquals(1, store.pending().size)
    }

    @Test
    fun `a kept report goes out on the next pass`() {
        reply(null, null, null)
        sender.report(body())
        reply(200)
        sender.sendPending()
        assertEquals(4, sent.size)
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `a refused sign-in keeps the report without hammering the site`() {
        reply(401)
        sender.report(body())
        assertEquals(1, sent.size)
        assertEquals(1, store.pending().size)
    }

    @Test
    fun `a report the site calls malformed is dropped`() {
        reply(400)
        sender.report(body())
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `signed out sends and keeps nothing`() {
        token = null
        sender.report(body())
        assertTrue(sent.isEmpty())
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `reports waiting from before signing out are not sent`() {
        store.add(body())
        token = null
        sender.sendPending()
        assertTrue(sent.isEmpty())
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `a run too old for the site is dropped unsent`() {
        store.add(body(startedAt = now - Duration.ofDays(7).toMillis()))
        sender.sendPending()
        assertTrue(sent.isEmpty())
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `a death is sent at once and never kept`() {
        reply(null, null, null)
        val death = JsonObject().apply {
            addProperty("kind", "death")
            addProperty("scriptInternalName", "LividFarm")
        }
        sender.death(death)
        assertEquals(3, sent.size)
        assertEquals("death", sent.first().first.get("kind").asString)
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `statuses map to what happens next`() {
        assertEquals(RunReportSender.Outcome.SENT, RunReportSender.outcomeOf(201))
        assertEquals(RunReportSender.Outcome.RETRY, RunReportSender.outcomeOf(null))
        assertEquals(RunReportSender.Outcome.RETRY, RunReportSender.outcomeOf(429))
        assertEquals(RunReportSender.Outcome.RETRY, RunReportSender.outcomeOf(500))
        assertEquals(RunReportSender.Outcome.KEEP, RunReportSender.outcomeOf(403))
        assertEquals(RunReportSender.Outcome.DROP, RunReportSender.outcomeOf(404))
    }
}
