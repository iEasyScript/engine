package org.projectx.packetlog.query

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.write
import org.projectx.core.net.prot.Codec
import org.projectx.core.net.prot.ProtRevisions
import org.projectx.core.net.prot.decode.ClientProtProjection
import org.projectx.core.net.prot.decode.DecodeStatus
import org.projectx.core.net.prot.decode.DecodedPacket
import org.projectx.core.net.prot.decode.FieldKind
import org.projectx.core.net.prot.decode.FieldValue
import org.projectx.core.net.prot.decode.RefDomain
import org.projectx.core.net.prot.decode.ProtSchema
import org.projectx.core.sqlite.SqliteDriver
import org.projectx.packetlog.chunk.ChunkFrame
import org.projectx.packetlog.upload.ArchiveReader

/**
 * Builds the investigation index from one or more archives.
 *
 * Decoding happens here rather than at capture time, which is what makes a decoder written months
 * later still useful: the bytes were kept, so the index is simply rebuilt and every past session
 * gains the new fields.
 *
 * Each session is decoded with the codec for **its own** revision, not the current one. A capture
 * outlives the build that made it, and reading it through a newer prot table would relabel every
 * packet in it.
 */
class QueryBuilder(private val databaseFile: File) : AutoCloseable {

    private val connection: Connection = run {
        SqliteDriver.register()
        databaseFile.parentFile?.mkdirs()
        DriverManager.getConnection("jdbc:sqlite:file:${databaseFile.absolutePath}?mode=rwc").apply {
            createStatement().use {
                it.execute("PRAGMA journal_mode = WAL")
                it.execute("PRAGMA synchronous = OFF")
            }
        }
    }

    class Report(
        val sessions: Int,
        val events: Int,
        val decoded: Int,
        val noDecoder: Int,
        val failed: Int,
        val indexedValues: Int,
        val missingCodecs: Set<Int>,
    )

    private val protIds = HashMap<Triple<Int, Int, Int>, Long>()
    private val fieldDefIds = HashMap<Triple<Int, String, String>, Long>()
    private val indexedFields = HashSet<Long>()

    /** Prose worth a full-text index. A type signature or an id is found by value, not by search. */
    private val textFields = HashSet<Long>()

    init {
        QuerySchema.create(connection)
    }

    fun build(archives: List<File>): Report {
        var sessions = 0
        var events = 0
        var decoded = 0
        var noDecoder = 0
        var failed = 0
        var indexed = 0
        val missingCodecs = HashSet<Int>()

        connection.autoCommit = false
        try {
            for (archive in archives) {
                ArchiveReader(archive).use { reader ->
                    for (session in reader.sessions()) {
                        val codec = ProtRevisions.codec(session.revision)
                        if (codec == null) {
                            missingCodecs.add(session.revision)
                            continue
                        }
                        registerProts(session.revision, codec)
                        registerSchemas(session.revision, codec)

                        val sessionId = insertSession(session, archive)
                        val counters = indexSession(sessionId, session, reader, codec)
                        events += counters.events
                        decoded += counters.decoded
                        noDecoder += counters.noDecoder
                        failed += counters.failed
                        indexed += counters.indexed
                        sessions++
                    }
                }
                connection.commit()
            }
        } finally {
            connection.autoCommit = true
        }
        return Report(sessions, events, decoded, noDecoder, failed, indexed, missingCodecs)
    }

    private class Counters {
        var events = 0
        var decoded = 0
        var noDecoder = 0
        var failed = 0
        var indexed = 0
    }

    private fun indexSession(
        sessionId: Long,
        session: ArchiveReader.Session,
        reader: ArchiveReader,
        codec: Codec,
    ): Counters {
        val counters = Counters()
        val perProt = HashMap<String, IntArray>()

        val insertEvent = connection.prepareStatement(
            """
            INSERT OR REPLACE INTO event(session_id, seq, tick, ms, dir, prot_id, len, status, decode_rev, fields)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        )
        val insertIndex = connection.prepareStatement(
            """
            INSERT OR REPLACE INTO field_index(field_def_id, int_value, session_id, seq, tick)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
        )
        val insertText = connection.prepareStatement(
            "INSERT INTO field_text(text_value, prot_name, path, session_id, seq, tick) VALUES (?, ?, ?, ?, ?, ?)"
        )

        insertEvent.use { eventStatement ->
            insertIndex.use { indexStatement ->
                insertText.use { textStatement ->
                    var firstTick = Int.MAX_VALUE
                    var lastTick = Int.MIN_VALUE

                    for (chunk in reader.chunks(session.id)) {
                        for (event in ChunkFrame.open(chunk.frame, chunk.firstSeq)) {
                            counters.events++
                            firstTick = minOf(firstTick, event.gameTick)
                            lastTick = maxOf(lastTick, event.gameTick)

                            val protName = protName(session.revision, event.dir, event.opcode, codec)
                            val protId = protId(session.revision, event.dir, event.opcode, protName)
                            val decodedPacket = decode(codec, event.dir, event.opcode, protName, event.body)

                            val stats = perProt.getOrPut(protName) { IntArray(3) }
                            stats[0]++
                            when {
                                decodedPacket == null -> {
                                    counters.noDecoder++
                                    stats[2]++
                                }
                                decodedPacket.status == DecodeStatus.FAILED -> {
                                    counters.failed++
                                    stats[2]++
                                }
                                else -> {
                                    counters.decoded++
                                    stats[1]++
                                }
                            }

                            eventStatement.setLong(1, sessionId)
                            eventStatement.setLong(2, event.seq)
                            eventStatement.setInt(3, event.gameTick)
                            eventStatement.setLong(4, event.epochMs)
                            eventStatement.setInt(5, event.dir)
                            eventStatement.setLong(6, protId)
                            eventStatement.setInt(7, event.body.size)
                            eventStatement.setInt(8, (decodedPacket?.status ?: DecodeStatus.NO_DECODER).ordinal)
                            eventStatement.setNull(9, Types.INTEGER)
                            eventStatement.setString(10, decodedPacket?.let { toJson(it) })
                            eventStatement.addBatch()

                            if (decodedPacket != null) {
                                if (event.dir == 1) {
                                    ensureProjectedFieldDefs(session.revision, protName, decodedPacket)
                                }
                                counters.indexed += indexFields(
                                    session.revision, protName, decodedPacket, sessionId,
                                    event.seq, event.gameTick, indexStatement, textStatement,
                                )
                            }
                            if (counters.events % 20_000 == 0) {
                                eventStatement.executeBatch()
                                indexStatement.executeBatch()
                                textStatement.executeBatch()
                            }
                        }
                    }
                    eventStatement.executeBatch()
                    indexStatement.executeBatch()
                    textStatement.executeBatch()
                    updateSessionCounters(sessionId, counters, firstTick, lastTick)
                }
            }
        }
        writeDecodeState(sessionId, perProt)
        return counters
    }

    private fun decode(
        codec: Codec,
        dir: Int,
        opcode: Int,
        protName: String,
        body: ByteArray,
    ): DecodedPacket? {
        if (body.isEmpty()) return null
        codec.structuredDecoder(dir, opcode)?.let { decoder ->
            return runCatching {
                runBlocking { decoder.decode.invoke(Buffer().apply { write(body) }, body.size) }
            }.getOrElse {
                DecodedPacket(protName, opcode, status = DecodeStatus.FAILED, error = it.message)
            }
        }
        // Client prots have verified decoders already; project what they produced rather than
        // re-reading the bytes a second way.
        if (dir == 1) {
            val clientCodec = codec.clientProtsByOpcode[opcode]?.decoder ?: return null
            return runCatching {
                val prot = runBlocking { clientCodec.invoke(Buffer().apply { write(body) }, body.size) }
                ClientProtProjection.project(protName, opcode, prot)
            }.getOrNull()
        }
        return null
    }

    private fun indexFields(
        revision: Int,
        protName: String,
        packet: DecodedPacket,
        sessionId: Long,
        seq: Long,
        tick: Int,
        indexStatement: PreparedStatement,
        textStatement: PreparedStatement,
    ): Int {
        var written = 0
        for ((path, value) in packet.flatten()) {
            val defId = fieldDefIds[Triple(revision, protName, normalisePath(path))] ?: continue
            when (value) {
                is FieldValue.I -> {
                    if (defId !in indexedFields) continue
                    indexStatement.setLong(1, defId)
                    indexStatement.setLong(2, value.v)
                    indexStatement.setLong(3, sessionId)
                    indexStatement.setLong(4, seq)
                    indexStatement.setInt(5, tick)
                    indexStatement.addBatch()
                    written++
                }
                is FieldValue.S -> {
                    if (value.v.isBlank() || defId !in textFields) continue
                    textStatement.setString(1, value.v)
                    textStatement.setString(2, protName)
                    textStatement.setString(3, path)
                    textStatement.setLong(4, sessionId)
                    textStatement.setLong(5, seq)
                    textStatement.setInt(6, tick)
                    textStatement.addBatch()
                    written++
                }
                else -> Unit
            }
        }
        return written
    }

    /** `args[3]` and `args[7]` are the same declared field, so they share one definition. */
    private fun normalisePath(path: String): String = path.replace(Regex("\\[\\d+]"), "[]")

    private fun protName(revision: Int, dir: Int, opcode: Int, codec: Codec): String =
        if (dir == 0) codec.serverProtName(opcode) else codec.clientProtName(opcode)

    private fun protId(revision: Int, dir: Int, opcode: Int, name: String): Long =
        protIds.getOrPut(Triple(revision, dir, opcode)) {
            connection.prepareStatement(
                """
                INSERT INTO prot(revision, dir, opcode, name) VALUES (?, ?, ?, ?)
                ON CONFLICT(revision, dir, opcode) DO UPDATE SET name = excluded.name
                RETURNING id
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, revision)
                statement.setInt(2, dir)
                statement.setInt(3, opcode)
                statement.setString(4, name)
                statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
            }
        }

    private fun registerProts(revision: Int, codec: Codec) {
        for ((opcode, info) in codec.serverProtInfo) protId(revision, 0, opcode, info.name)
        for ((opcode, info) in codec.clientProtInfo) protId(revision, 1, opcode, info.name)
    }

    private fun registerSchemas(revision: Int, codec: Codec) {
        for ((_, decoder) in codec.structuredDecoders) registerSchema(revision, decoder.schema)
    }

    private fun registerSchema(revision: Int, schema: ProtSchema) {
        for (field in schema.fields) {
            val key = Triple(revision, schema.opcodeName, field.name)
            if (key in fieldDefIds) continue
            val id = connection.prepareStatement(
                """
                INSERT INTO field_def(revision, prot_name, path, kind, ref_domain, indexed)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(revision, prot_name, path) DO UPDATE SET
                    kind = excluded.kind, ref_domain = excluded.ref_domain, indexed = excluded.indexed
                RETURNING id
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, revision)
                statement.setString(2, schema.opcodeName)
                statement.setString(3, field.name)
                statement.setString(4, field.kind.name)
                statement.setString(5, field.ref.name)
                statement.setInt(6, if (field.indexed) 1 else 0)
                statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
            }
            fieldDefIds[key] = id
            if (field.indexed) indexedFields.add(id)
            if (field.kind == FieldKind.STRING &&
                field.ref == RefDomain.TEXT
            ) {
                textFields.add(id)
            }
        }
        connection.prepareStatement(
            """
            INSERT OR IGNORE INTO decode_rev(revision, prot_name, schema_ver, fields_hash, first_seen_ms)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, revision)
            statement.setString(2, schema.opcodeName)
            statement.setInt(3, schema.version)
            statement.setString(4, schema.fieldsHash)
            statement.setLong(5, System.currentTimeMillis())
            statement.executeUpdate()
        }
    }

    /**
     * Client prot fields are discovered from the projection rather than declared, so their
     * definitions appear the first time one is actually seen.
     */
    private fun ensureProjectedFieldDefs(revision: Int, protName: String, packet: DecodedPacket) {
        for ((path, value) in packet.flatten()) {
            val normalised = normalisePath(path)
            val key = Triple(revision, protName, normalised)
            if (key in fieldDefIds) continue
            val kind = when (value) {
                is FieldValue.I -> "INT"
                is FieldValue.S -> "STRING"
                is FieldValue.B -> "BOOL"
                is FieldValue.Bytes -> "BYTES"
                else -> "OBJECT"
            }
            val indexed = value is FieldValue.I && normalised.lowercase().let { name ->
                name.endsWith("id") || name.endsWith("hash") || name.endsWith("index")
            }
            val id = connection.prepareStatement(
                """
                INSERT INTO field_def(revision, prot_name, path, kind, ref_domain, indexed)
                VALUES (?, ?, ?, ?, 'NONE', ?)
                ON CONFLICT(revision, prot_name, path) DO UPDATE SET kind = excluded.kind
                RETURNING id
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, revision)
                statement.setString(2, protName)
                statement.setString(3, normalised)
                statement.setString(4, kind)
                statement.setInt(5, if (indexed) 1 else 0)
                statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
            }
            fieldDefIds[key] = id
            if (indexed) indexedFields.add(id)
            if (value is FieldValue.S) textFields.add(id)
        }
    }

    private fun insertSession(session: ArchiveReader.Session, archive: File): Long {
        connection.prepareStatement(
            """
            INSERT INTO session(uuid, source, kind, revision, client_build, player, started_ms, ended_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET source = excluded.source
            RETURNING id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, session.uuid)
            statement.setString(2, archive.absolutePath)
            statement.setString(3, session.kind)
            statement.setInt(4, session.revision)
            statement.setString(5, session.clientBuild)
            statement.setString(6, null)
            statement.setLong(7, session.startedEpochMs)
            if (session.endedEpochMs == null) statement.setNull(8, Types.INTEGER)
            else statement.setLong(8, session.endedEpochMs)
            return statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }
    }

    private fun updateSessionCounters(sessionId: Long, counters: Counters, firstTick: Int, lastTick: Int) {
        connection.prepareStatement(
            """
            UPDATE session SET packet_count = ?, first_tick = ?, last_tick = ?,
                               decode_ok = ?, decode_missing = ?, decode_failed = ?
            WHERE id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, counters.events)
            statement.setInt(2, if (firstTick == Int.MAX_VALUE) 0 else firstTick)
            statement.setInt(3, if (lastTick == Int.MIN_VALUE) 0 else lastTick)
            statement.setInt(4, counters.decoded)
            statement.setInt(5, counters.noDecoder)
            statement.setInt(6, counters.failed)
            statement.setLong(7, sessionId)
            statement.executeUpdate()
        }
    }

    private fun writeDecodeState(sessionId: Long, perProt: Map<String, IntArray>) {
        connection.prepareStatement(
            """
            INSERT OR REPLACE INTO session_decode_state(session_id, prot_name, decode_rev, row_count, ok_count, failed_count)
            VALUES (?, ?, NULL, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            for ((name, stats) in perProt) {
                statement.setLong(1, sessionId)
                statement.setString(2, name)
                statement.setInt(3, stats[0])
                statement.setInt(4, stats[1])
                statement.setInt(5, stats[2])
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun toJson(packet: DecodedPacket): String = buildString {
        append('{')
        var first = true
        for ((path, value) in packet.flatten()) {
            if (!first) append(',')
            first = false
            append('"').append(escape(path)).append("\":")
            when (value) {
                is FieldValue.I -> append(value.v)
                is FieldValue.B -> append(value.v)
                is FieldValue.S -> append('"').append(escape(value.v)).append('"')
                is FieldValue.Bytes -> append('"').append(value.v.joinToString("") { "%02x".format(it) }).append('"')
                else -> append("null")
            }
        }
        append('}')
    }

    private fun escape(value: String): String = buildString {
        for (c in value) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }

    override fun close() {
        runCatching {
            connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
            connection.close()
        }
    }
}
