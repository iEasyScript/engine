package org.projectx.packetlog.query

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import org.projectx.core.sqlite.SqliteDriver

/**
 * Reads the investigation index.
 *
 * Every investigation has the same shape whatever it is about: find the moment something happened,
 * then look at what surrounds it. So there are two primitives - [find] locates events by any decoded
 * field, [window] returns everything around a point in tick order - and [investigate] is the two
 * composed. Nothing here knows what an interface or an npc is; the field dictionary does.
 */
class PacketQuery(databaseFile: File) : AutoCloseable {

    private val connection: Connection = run {
        SqliteDriver.register()
        DriverManager.getConnection("jdbc:sqlite:file:${databaseFile.absolutePath}?mode=ro")
    }

    class Event(
        val sessionId: Long,
        val seq: Long,
        val tick: Int,
        val ms: Long,
        val direction: String,
        val prot: String,
        val length: Int,
        val fields: String?,
        val decoded: Boolean,
    ) {
        override fun toString(): String =
            "tick=$tick $direction $prot${if (fields != null && fields != "{}") " $fields" else ""}"
    }

    class FieldRef(val protName: String, val path: String, val refDomain: String, val indexed: Boolean)

    class Investigation(val anchor: Event, val before: List<Event>, val after: List<Event>) {
        val all: List<Event> get() = before + anchor + after
    }

    /**
     * Locates events by a decoded field.
     *
     * [prot] and [path] narrow which field; either may be null to mean "any". An indexed field
     * resolves through the index; anything else falls back to scanning that prot's events, which is
     * bounded rather than a table scan because the prot itself is indexed.
     */
    fun find(
        path: String? = null,
        value: Long? = null,
        prot: String? = null,
        sessionId: Long? = null,
        tickFrom: Int? = null,
        tickTo: Int? = null,
        limit: Int = 100,
    ): List<Event> {
        if (value != null && path != null) {
            val indexed = findIndexed(path, value, prot, sessionId, tickFrom, tickTo, limit)
            if (indexed.isNotEmpty()) return indexed
        }
        return findByScan(path, value, prot, sessionId, tickFrom, tickTo, limit)
    }

    /** Full-text over every decoded string field - chat, interface text, script string arguments. */
    fun findText(query: String, prot: String? = null, limit: Int = 100): List<Event> {
        val sql = buildString {
            append(
                """
                SELECT e.session_id, e.seq FROM field_text t
                JOIN event e ON e.session_id = t.session_id AND e.seq = t.seq
                WHERE field_text MATCH ?
                """.trimIndent()
            )
            if (prot != null) append(" AND t.prot_name = ?")
            append(" LIMIT ?")
        }
        return connection.prepareStatement(sql).use { statement ->
            var index = 1
            statement.setString(index++, query)
            if (prot != null) statement.setString(index++, prot)
            statement.setInt(index, limit)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(eventAt(rows.getLong(1), rows.getLong(2))!!) }
            }
        }
    }

    /** Everything in a tick range, in order. The other half of every investigation. */
    fun window(
        sessionId: Long,
        fromTick: Int,
        toTick: Int,
        prots: Set<String> = emptySet(),
        directions: Set<Int> = emptySet(),
        limit: Int = 2000,
    ): List<Event> {
        val sql = buildString {
            append(BASE_SELECT)
            append(" WHERE e.session_id = ? AND e.tick BETWEEN ? AND ?")
            if (prots.isNotEmpty()) append(" AND p.name IN (${prots.joinToString(",") { "?" }})")
            if (directions.isNotEmpty()) append(" AND e.dir IN (${directions.joinToString(",") { "?" }})")
            append(" ORDER BY e.seq LIMIT ?")
        }
        return connection.prepareStatement(sql).use { statement ->
            var index = 1
            statement.setLong(index++, sessionId)
            statement.setInt(index++, fromTick)
            statement.setInt(index++, toTick)
            for (name in prots) statement.setString(index++, name)
            for (dir in directions) statement.setInt(index++, dir)
            statement.setInt(index, limit)
            statement.readEvents()
        }
    }

    /**
     * An anchor plus the ticks around it - the whole point of the index. [after] is what usually
     * matters (what did the server do in response), [before] catches what led up to it.
     */
    fun investigate(
        anchor: Event,
        ticksBefore: Int = 0,
        ticksAfter: Int = 3,
        prots: Set<String> = emptySet(),
    ): Investigation {
        val surrounding = window(
            anchor.sessionId,
            anchor.tick - ticksBefore,
            anchor.tick + ticksAfter,
            prots,
        ).filterNot { it.seq == anchor.seq }
        return Investigation(
            anchor = anchor,
            before = surrounding.filter { it.seq < anchor.seq },
            after = surrounding.filter { it.seq > anchor.seq },
        )
    }

    /** What is queryable: every declared field, and whether it resolves in constant time. */
    fun fields(prot: String? = null, refDomain: String? = null): List<FieldRef> {
        val sql = buildString {
            append("SELECT prot_name, path, ref_domain, indexed FROM field_def WHERE 1=1")
            if (prot != null) append(" AND prot_name = ?")
            if (refDomain != null) append(" AND ref_domain = ?")
            append(" ORDER BY prot_name, path")
        }
        return connection.prepareStatement(sql).use { statement ->
            var index = 1
            if (prot != null) statement.setString(index++, prot)
            if (refDomain != null) statement.setString(index, refDomain)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(FieldRef(rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4) != 0))
                    }
                }
            }
        }
    }

    fun sessions(): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT id, uuid, kind, revision, packet_count, first_tick, last_tick,
                       decode_ok, decode_missing
                FROM session ORDER BY id
                """.trimIndent()
            ).use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            "session ${rows.getLong(1)} ${rows.getString(3)} rev${rows.getInt(4)} " +
                                "packets=${rows.getInt(5)} ticks=${rows.getInt(6)}..${rows.getInt(7)} " +
                                "decoded=${rows.getInt(8)} undecoded=${rows.getInt(9)}"
                        )
                    }
                }
            }
        }

    /** Per-prot decode coverage, which is what tells you where a new decoder is worth writing. */
    fun coverage(limit: Int = 40): List<String> =
        connection.prepareStatement(
            """
            SELECT prot_name, sum(row_count) total, sum(ok_count) ok
            FROM session_decode_state GROUP BY prot_name
            ORDER BY (sum(row_count) - sum(ok_count)) DESC LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val total = rows.getInt(2)
                        val ok = rows.getInt(3)
                        add("%-36s %7d packets  %5.1f%% decoded".format(rows.getString(1), total, 100.0 * ok / total))
                    }
                }
            }
        }

    private fun findIndexed(
        path: String,
        value: Long,
        prot: String?,
        sessionId: Long?,
        tickFrom: Int?,
        tickTo: Int?,
        limit: Int,
    ): List<Event> {
        val sql = buildString {
            append(BASE_SELECT)
            append(" JOIN field_index fi ON fi.session_id = e.session_id AND fi.seq = e.seq")
            append(" JOIN field_def fd ON fd.id = fi.field_def_id")
            append(" WHERE fd.path = ? AND fi.int_value = ?")
            if (prot != null) append(" AND fd.prot_name = ?")
            if (sessionId != null) append(" AND e.session_id = ?")
            if (tickFrom != null) append(" AND e.tick >= ?")
            if (tickTo != null) append(" AND e.tick <= ?")
            append(" ORDER BY e.session_id, e.seq LIMIT ?")
        }
        return connection.prepareStatement(sql).use { statement ->
            var index = 1
            statement.setString(index++, path)
            statement.setLong(index++, value)
            if (prot != null) statement.setString(index++, prot)
            if (sessionId != null) statement.setLong(index++, sessionId)
            if (tickFrom != null) statement.setInt(index++, tickFrom)
            if (tickTo != null) statement.setInt(index++, tickTo)
            statement.setInt(index, limit)
            statement.readEvents()
        }
    }

    private fun findByScan(
        path: String?,
        value: Long?,
        prot: String?,
        sessionId: Long?,
        tickFrom: Int?,
        tickTo: Int?,
        limit: Int,
    ): List<Event> {
        val sql = buildString {
            append(BASE_SELECT).append(" WHERE 1=1")
            if (prot != null) append(" AND p.name = ?")
            if (sessionId != null) append(" AND e.session_id = ?")
            if (tickFrom != null) append(" AND e.tick >= ?")
            if (tickTo != null) append(" AND e.tick <= ?")
            if (path != null && value != null) append(" AND json_extract(e.fields, '$.' || ?) = ?")
            else if (path != null) append(" AND json_extract(e.fields, '$.' || ?) IS NOT NULL")
            append(" ORDER BY e.session_id, e.seq LIMIT ?")
        }
        return connection.prepareStatement(sql).use { statement ->
            var index = 1
            if (prot != null) statement.setString(index++, prot)
            if (sessionId != null) statement.setLong(index++, sessionId)
            if (tickFrom != null) statement.setInt(index++, tickFrom)
            if (tickTo != null) statement.setInt(index++, tickTo)
            if (path != null) statement.setString(index++, path)
            if (path != null && value != null) statement.setLong(index++, value)
            statement.setInt(index, limit)
            statement.readEvents()
        }
    }

    private fun eventAt(sessionId: Long, seq: Long): Event? =
        connection.prepareStatement("$BASE_SELECT WHERE e.session_id = ? AND e.seq = ?").use { statement ->
            statement.setLong(1, sessionId)
            statement.setLong(2, seq)
            statement.readEvents().firstOrNull()
        }

    private fun PreparedStatement.readEvents(): List<Event> =
        executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        Event(
                            sessionId = rows.getLong(1),
                            seq = rows.getLong(2),
                            tick = rows.getInt(3),
                            ms = rows.getLong(4),
                            direction = if (rows.getInt(5) == 0) "S>" else "C>",
                            prot = rows.getString(6),
                            length = rows.getInt(7),
                            fields = rows.getString(8),
                            decoded = rows.getString(8) != null,
                        )
                    )
                }
            }
        }

    override fun close() {
        runCatching { connection.close() }
    }

    companion object {
        private const val BASE_SELECT =
            "SELECT e.session_id, e.seq, e.tick, e.ms, e.dir, p.name, e.len, e.fields " +
                "FROM event e JOIN prot p ON p.id = e.prot_id"
    }
}
