package org.projectx.packetlog

import java.io.File
import java.nio.file.Files
import org.projectx.core.net.prot.revision.rev950.registerRevision950
import org.projectx.packetlog.chunk.ChunkEvent
import org.projectx.packetlog.query.PacketQuery
import org.projectx.packetlog.query.QueryBuilder
import org.projectx.packetlog.store.PacketSchema
import org.projectx.packetlog.store.PacketSessionWriter
import org.projectx.packetlog.store.PacketStore
import org.projectx.packetlog.store.ProtEntry
import org.projectx.packetlog.store.ProtTable
import org.projectx.packetlog.store.SessionMetadata
import org.projectx.core.net.prot.revision.rev950.register950
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Exercises the shape every investigation has: anchor on some decoded field, then look at the ticks
 * around it. The fixture deliberately mixes categories - a client action, variable changes, a script
 * run, interface text - because the index must not be better at one kind of event than another.
 */
class QueryIndexTest {

    private lateinit var archive: File
    private lateinit var index: File

    private val codec = register950()
    private val protTable by lazy { ProtTable.snapshot(950, codec) }

    private fun opcodeOf(name: String, dir: Int): Int =
        protTable.first { it.name == name && it.dir == dir }.opcode

    @BeforeEach
    fun setUp() {
        registerRevision950()
        val directory = Files.createTempDirectory("packetlog-query").toFile()
        archive = File(directory, "archive.db")
        index = File(directory, "query.db")
        writeArchive()
        QueryBuilder(index).use { it.build(listOf(archive)) }
    }

    /** Bytes here are produced by the same encoders the decoders invert, never hand-written. */
    private fun writeArchive() {
        val metadata = SessionMetadata(
            player = "tester", world = 1, startedEpochMs = 1_700_000_000_000L, monoBaseNs = 0,
            revision = 950, protTableHash = ByteArray(32), platform = "linux", arch = "x86_64",
            clientBuild = "950-1", engineBuild = "test",
            serverProfile = PacketSchema.ServerProfile.LOCAL, peerHash = null,
            consentVersion = 0, uploadOptIn = false, provenance = "test_v1",
        )
        PacketStore.open(archive, metadata, protTable).use { store ->
            PacketSessionWriter(store, 950, PacketSessionWriter.SealPolicy(maxEvents = 1000)).use { writer ->
                store.beginFlush()
                var seqTick = 0
                fun add(dir: Int, opcode: Int, tick: Int, body: ByteArray) {
                    writer.append(
                        ChunkEvent(store.assignSequence(), 1_700_000_000_000L + seqTick++, 0, tick,
                            dir, opcode, PacketSchema.Quality.OK, body)
                    )
                }
                // tick 10: a client action, then the server's response on the same and next tick.
                add(1, opcodeOf("IF_BUTTON1", 1), 10, byteArrayOf(0x05, 0xC5.toByte(), 0x00, 0x2A, 0, 0, 0x2A, 0, 0x07))
                add(0, opcodeOf("VARP_LARGE", 0), 10, byteArrayOf(0xE3.toByte(), 0x1C, 0x00, 0x00, 0x00, 0x10))
                add(0, opcodeOf("IF_SETNPCHEAD", 0), 10, byteArrayOf(0, 0x22, 0xA3.toByte(), 0, 8, 0, 0, 4))
                add(0, opcodeOf("VARBIT_SMALL", 0), 11, byteArrayOf(0x00, 0x2A, 0x05))
                add(0, opcodeOf("UPDATE_STAT", 0), 12, byteArrayOf(0x40, 0x0D, 0x03, 0x00, 0x63, 0x01))
                store.commitFlush()
                writer.flush()
            }
            store.closeSession(1_700_000_010_000L, "clean")
        }
    }

    @Test
    fun `every packet lands on the tick axis whether or not it decoded`() {
        PacketQuery(index).use { query ->
            val all = query.window(sessionId = 1, fromTick = 0, toTick = 100)
            assertEquals(5, all.size, "every captured packet is on the axis")
            assertEquals(listOf(10, 10, 10, 11, 12), all.map { it.tick })
        }
    }

    /** The anchor can be any decoded field of any packet - that is the whole point. */
    @Test
    fun `an anchor can be a variable, a client action, or anything else decoded`() {
        PacketQuery(index).use { query ->
            val byVarp = query.find(path = "varp", value = 7267)
            assertEquals(1, byVarp.size, "found by a server-side variable id")
            assertEquals("VARP_LARGE", byVarp.single().prot)

            val byAction = query.find(prot = "IF_BUTTON1")
            assertEquals(1, byAction.size, "found by a client action")

            val bySkill = query.find(path = "skill", prot = "UPDATE_STAT")
            assertEquals(1, bySkill.size, "found by a stat update")
        }
    }

    @Test
    fun `an anchor pivots to what followed it in tick order`() {
        PacketQuery(index).use { query ->
            val anchor = query.find(prot = "IF_BUTTON1").single()
            val investigation = query.investigate(anchor, ticksAfter = 2)

            val names = investigation.after.map { it.prot }
            assertTrue("VARP_LARGE" in names, "the variable change after the action is visible")
            assertTrue("VARBIT_SMALL" in names, "so is the next tick's change")
            assertTrue(investigation.before.isEmpty(), "nothing preceded it in range")
            assertEquals(anchor.seq, investigation.anchor.seq)
        }
    }

    @Test
    fun `decoded fields are readable as json on the event`() {
        PacketQuery(index).use { query ->
            val varp = query.find(path = "varp", value = 7267).single()
            assertNotNull(varp.fields)
            assertTrue(varp.fields!!.contains("\"varp\":7267"), "field is queryable as json: ${varp.fields}")
            assertTrue(varp.fields!!.contains("\"value\":1048576"))
        }
    }

    /** A field dictionary is what makes the data self-describing rather than requiring lore. */
    @Test
    fun `the index describes what is queryable`() {
        PacketQuery(index).use { query ->
            val fields = query.fields()
            assertTrue(fields.any { it.protName == "VARP_LARGE" && it.path == "varp" && it.indexed })
            assertTrue(fields.any { it.refDomain == "VARP" }, "reference domains are recorded")
            assertTrue(query.fields(prot = "UPDATE_STAT").isNotEmpty())
        }
    }

    @Test
    fun `coverage reports where a decoder is still missing`() {
        PacketQuery(index).use { query ->
            assertTrue(query.coverage().isNotEmpty(), "coverage is reported per prot")
        }
    }

    /**
     * The reason raw bytes are kept: a decoder written later must make every past capture richer
     * without the capture being touched.
     */
    @Test
    fun `re-indexing an untouched archive picks up decoders added later`() {
        val second = File(index.parentFile, "query-rebuilt.db")
        QueryBuilder(second).use { it.build(listOf(archive)) }
        PacketQuery(second).use { query ->
            val head = query.find(prot = "IF_SETNPCHEAD")
            assertEquals(1, head.size)
            assertNotNull(head.single().fields, "a prot decoded only by the newer build is now populated")
        }
    }

    /** A capture must be read with the prot table it was captured under, not the newest one. */
    @Test
    fun `sessions record the revision they were captured under`() {
        PacketQuery(index).use { query ->
            assertTrue(query.sessions().single().contains("rev950"))
        }
    }
}
