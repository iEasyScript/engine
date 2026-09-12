package org.projectx.packetlog.query

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import org.projectx.core.net.prot.decode.RefDomain
import org.projectx.core.sqlite.SqliteDriver

/**
 * Higher-level questions built on the two primitives.
 *
 * [timeline] answers "where am I" - a capture is tens of thousands of packets and an investigation
 * starts by finding the minute worth looking at. [containerAt] answers "what did it hold", which
 * needs a small replay because a container is described by a full snapshot followed by patches, and
 * reading either alone gives the wrong answer.
 */
class SessionTools(databaseFile: File) : AutoCloseable {

    private val connection: Connection = run {
        SqliteDriver.register()
        DriverManager.getConnection("jdbc:sqlite:file:${databaseFile.absolutePath}?mode=ro")
    }

    class Moment(val tick: Int, val kind: String, val detail: String)

    private companion object {
        /** Interfaces opening together in one tick past this are a scene build, not a decision. */
        const val BURST = 5
    }

    /** Renders an action with its target named, since a bare loc id says nothing to a reader. */
    private fun describeAction(prot: String, fields: String?): String {
        if (fields == null) return prot
        val loc = extract(fields, "locId")
        val npc = extract(fields, "npcIndex")
        val option = extract(fields, "option")
        val target = when {
            loc != null -> Names.label(RefDomain.LOC, loc)
            npc != null -> "npc index $npc"
            else -> ""
        }
        val where = extract(fields, "x")?.let { x -> extract(fields, "y")?.let { y -> " at ($x,$y)" } } ?: ""
        return "$prot option=${option ?: "?"} $target$where"
    }

    private fun extract(fields: String, name: String): Long? {
        val key = "\"$name\":"
        val at = fields.indexOf(key)
        if (at < 0) return null
        val from = at + key.length
        var to = from
        while (to < fields.length && (fields[to] == '-' || fields[to].isDigit())) to++
        return fields.substring(from, to).toLongOrNull()
    }

    class Slot(val slot: Int, val objId: Int, val amount: Int) {
        fun render(): String = "%3d  %-42s x%d".format(slot, Names.label(RefDomain.OBJ, objId.toLong()), amount)
    }

    /**
     * The shape of a session: the moments that mark a change of activity, rather than every packet.
     *
     * What counts as a moment is deliberately narrow - entering an area, opening something, taking
     * an action on the world. Variable traffic and rendering chatter are the bulk of a capture and
     * say nothing about what the player was doing.
     */
    fun timeline(sessionId: Long, limit: Int = 200): List<Moment> {
        val moments = ArrayList<Moment>()
        val interfaceTicks = HashMap<Int, Int>()

        query(
            """
            SELECT e.tick, json_extract(e.fields,'$.type'), json_extract(e.fields,'$.centerZoneX'),
                   json_extract(e.fields,'$.centerZoneY'), json_extract(e.fields,'$.widthZones'),
                   json_extract(e.fields,'$.heightZones')
            FROM event e JOIN prot p ON p.id = e.prot_id
            WHERE e.session_id = ? AND p.name = 'REBUILD_REGION'
            """.trimIndent(),
            sessionId,
        ) { rows ->
            val tileX = rows.getInt(3) * 8
            val tileY = rows.getInt(4) * 8
            moments.add(
                Moment(
                    rows.getInt(1),
                    "instance",
                    "type ${rows.getInt(2)} at ($tileX,$tileY) ${rows.getInt(5)}x${rows.getInt(6)} zones",
                )
            )
        }

        query(
            """
            SELECT e.tick, json_extract(e.fields,'$.container'), json_extract(e.fields,'$.size')
            FROM event e JOIN prot p ON p.id = e.prot_id
            WHERE e.session_id = ? AND p.name = 'UPDATE_INV_FULL' AND json_extract(e.fields,'$.size') > 0
            """.trimIndent(),
            sessionId,
        ) { rows ->
            val container = rows.getLong(2)
            moments.add(
                Moment(
                    rows.getInt(1),
                    "container",
                    "${Names.label(RefDomain.INV, container)} filled with ${rows.getInt(3)} slots",
                )
            )
        }

        query(
            """
            SELECT e.tick, json_extract(e.fields,'$.interface')
            FROM event e JOIN prot p ON p.id = e.prot_id
            WHERE e.session_id = ? AND p.name IN ('IF_OPENTOP','IF_OPENSUB')
            """.trimIndent(),
            sessionId,
        ) { rows ->
            val iface = rows.getLong(2)
            Names.of(RefDomain.INTERFACE, iface)?.let {
                moments.add(Moment(rows.getInt(1), "interface", "$it($iface) opened"))
            }
            interfaceTicks.merge(rows.getInt(1), 1, Int::plus)
        }

        query(
            """
            SELECT e.tick, p.name, e.fields
            FROM event e JOIN prot p ON p.id = e.prot_id
            WHERE e.session_id = ? AND (p.name LIKE 'OPNPC%' OR p.name LIKE 'OPLOC%')
            """.trimIndent(),
            sessionId,
        ) { rows ->
            moments.add(Moment(rows.getInt(1), "action", describeAction(rows.getString(2), rows.getString(3))))
        }

        // Logging in and rebuilding a scene open a hundred interfaces in three ticks. Listing each
        // one buries everything the player actually did, so a burst becomes a single line.
        val burstTicks = interfaceTicks.filterValues { it >= BURST }.keys
        val collapsed = moments.filterNot { it.kind == "interface" && it.tick in burstTicks } +
            burstTicks.map { Moment(it, "interfaces", "${interfaceTicks[it]} opened at once (scene build)") }

        // Collapse runs of the same thing: opening a bank emits the same interface repeatedly, and
        // a list that repeats itself hides the moments that matter.
        return collapsed.sortedBy { it.tick }
            .fold(ArrayList<Moment>()) { acc, moment ->
                val previous = acc.lastOrNull()
                if (previous == null || previous.kind != moment.kind || previous.detail != moment.detail) {
                    acc.add(moment)
                }
                acc
            }
            .take(limit)
    }

    /**
     * A container's contents as of [tick].
     *
     * Rebuilt rather than read: the wire sends one full snapshot and then only what changed, so the
     * latest full update alone is stale and the latest patch alone is meaningless. Replaying the
     * patches over the snapshot is the only way to get what was actually held.
     */
    fun containerAt(sessionId: Long, container: Int, tick: Int): List<Slot> {
        val slots = HashMap<Int, Slot>()

        var snapshotTick = -1
        query(
            """
            SELECT e.tick, e.fields FROM event e JOIN prot p ON p.id = e.prot_id
            WHERE e.session_id = ? AND p.name = 'UPDATE_INV_FULL' AND e.tick <= ?
              AND json_extract(e.fields,'$.container') = ?
            ORDER BY e.seq DESC LIMIT 1
            """.trimIndent(),
            sessionId, tick.toLong(), container.toLong(),
        ) { rows ->
            snapshotTick = rows.getInt(1)
            val fields = rows.getString(2) ?: return@query
            val items = jsonArray(fields, "items")
            val amounts = jsonArray(fields, "amounts")
            for (index in items.indices) {
                val objId = items[index]
                if (objId < 0) continue
                slots[index] = Slot(index, objId, amounts.getOrElse(index) { 0 })
            }
        }
        if (snapshotTick < 0) return emptyList()

        query(
            """
            SELECT e.fields FROM event e JOIN prot p ON p.id = e.prot_id
            WHERE e.session_id = ? AND p.name = 'UPDATE_INV_PARTIAL' AND e.tick BETWEEN ? AND ?
              AND json_extract(e.fields,'$.container') = ?
            ORDER BY e.seq
            """.trimIndent(),
            sessionId, snapshotTick.toLong(), tick.toLong(), container.toLong(),
        ) { rows ->
            val fields = rows.getString(1) ?: return@query
            val changed = jsonArray(fields, "slots")
            val items = jsonArray(fields, "items")
            val amounts = jsonArray(fields, "amounts")
            for (index in changed.indices) {
                val slot = changed[index]
                val objId = items.getOrElse(index) { -1 }
                if (objId < 0) slots.remove(slot) else slots[slot] = Slot(slot, objId, amounts.getOrElse(index) { 0 })
            }
        }

        return slots.values.sortedBy { it.slot }
    }

    /** Containers this session ever described, so a caller can ask for one by name. */
    fun containers(sessionId: Long): List<String> {
        val out = ArrayList<String>()
        query(
            """
            SELECT json_extract(e.fields,'$.container') c, count(*), max(json_extract(e.fields,'$.size'))
            FROM event e JOIN prot p ON p.id = e.prot_id
            WHERE e.session_id = ? AND p.name LIKE 'UPDATE_INV%'
            GROUP BY c ORDER BY 2 DESC
            """.trimIndent(),
            sessionId,
        ) { rows ->
            val id = rows.getLong(1)
            out.add("%-44s updates=%-5d largest=%d".format(Names.label(RefDomain.INV, id), rows.getInt(2), rows.getInt(3)))
        }
        return out
    }

    /** `[1,2,3]` out of the flattened field json, which stores list members as `name[i]`. */
    private fun jsonArray(fields: String, name: String): List<Int> {
        val out = ArrayList<Int>()
        var index = 0
        while (true) {
            val key = "\"$name[$index]\":"
            val at = fields.indexOf(key)
            if (at < 0) return out
            val from = at + key.length
            var to = from
            while (to < fields.length && (fields[to] == '-' || fields[to].isDigit())) to++
            out.add(fields.substring(from, to).toIntOrNull() ?: return out)
            index++
        }
    }

    private fun query(sql: String, vararg args: Long, row: (ResultSet) -> Unit) {
        connection.prepareStatement(sql).use { statement ->
            for ((index, value) in args.withIndex()) statement.setLong(index + 1, value)
            statement.executeQuery().use { rows -> while (rows.next()) row(rows) }
        }
    }

    override fun close() {
        runCatching { connection.close() }
    }
}
