package com.projectx.store

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingRunReportsTest {
    private lateinit var dir: Path
    private lateinit var store: PendingRunReports

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("projectx-runs")
        store = PendingRunReports(dir)
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun body(name: String) = JsonObject().apply {
        addProperty("scriptInternalName", name)
        addProperty("endReason", "CLIENT_CLOSED")
    }

    private fun names() = dir.listDirectoryEntries().map { it.name }.toSet()

    @Test
    fun `a report waits on disk until it is claimed and done`() {
        store.add(body("A"))
        val claimed = store.claimAll().single()
        assertEquals("A", claimed.body.get("scriptInternalName").asString)
        assertTrue(store.pending().isEmpty())
        store.done(claimed)
        assertTrue(names().isEmpty())
    }

    @Test
    fun `a report can only be claimed once`() {
        val file = store.add(body("A"))
        assertNotNull(store.claim(file))
        assertNull(store.claim(file))
    }

    @Test
    fun `a report given back is pending again`() {
        store.add(body("A"))
        store.giveBack(store.claimAll().single())
        assertEquals(1, store.pending().size)
    }

    @Test
    fun `a claim left by a client that died goes back in the queue`() {
        store.add(body("A"))
        val claimed = store.claimAll().single()
        Files.setLastModifiedTime(claimed.file, FileTime.fromMillis(0))
        store.recoverStaleClaims(System.currentTimeMillis())
        assertEquals(1, store.pending().size)
    }

    @Test
    fun `a fresh claim is left to its sender`() {
        store.add(body("A"))
        store.claimAll()
        store.recoverStaleClaims(System.currentTimeMillis())
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `open runs are not mistaken for reports`() {
        store.checkpoint(42, listOf(body("A")))
        assertTrue(store.pending().isEmpty())
        assertTrue(store.claimAll().isEmpty())
    }

    @Test
    fun `an empty checkpoint removes the file`() {
        store.checkpoint(42, listOf(body("A")))
        store.checkpoint(42, emptyList())
        assertTrue(names().isEmpty())
    }

    @Test
    fun `runs left open by a closed client become reports`() {
        store.checkpoint(7, listOf(body("A"), body("B")))
        val recovered = store.recoverOpenRuns(selfPid = 1, now = System.currentTimeMillis()) { false }
        assertEquals(2, recovered)
        assertEquals(setOf("A", "B"), store.claimAll().map { it.body.get("scriptInternalName").asString }.toSet())
        assertTrue(names().none { it.startsWith("open-") })
    }

    @Test
    fun `this process's runs from an earlier engine load are recovered`() {
        store.checkpoint(1, listOf(body("A")))
        assertEquals(1, store.recoverOpenRuns(selfPid = 1, now = System.currentTimeMillis()) { true })
    }

    @Test
    fun `another client still running keeps its open runs`() {
        store.checkpoint(7, listOf(body("A")))
        assertEquals(0, store.recoverOpenRuns(selfPid = 1, now = System.currentTimeMillis()) { true })
        assertTrue("open-7.json" in names())
    }

    @Test
    fun `open runs nobody has refreshed in minutes are recovered even if the pid looks alive`() {
        store.checkpoint(7, listOf(body("A")))
        Files.setLastModifiedTime(dir.resolve("open-7.json"), FileTime.fromMillis(0))
        assertEquals(1, store.recoverOpenRuns(selfPid = 1, now = System.currentTimeMillis()) { true })
    }
}
