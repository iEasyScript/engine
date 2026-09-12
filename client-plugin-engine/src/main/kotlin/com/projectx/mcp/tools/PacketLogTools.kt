package com.projectx.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.io.File
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.projectx.packetlog.query.PacketQuery

/**
 * GENERIC (keep). Investigates captured packets: find any event by a decoded field, then read the
 * ticks around it. Pure file I/O against the derived index, so it runs off the game tick and works
 * whether or not a client is attached.
 */
object PacketLogTools {

    private fun indexFile(): File =
        File(File(System.getProperty("user.home"), ".projectx/packetlog"), "query.db")

    fun register(server: Server): Int {
        registerFind(server)
        registerWindow(server)
        registerFields(server)
        return 3
    }

    private fun registerFind(server: Server) {
        server.addTool(
            name = "packetlog_find",
            description = "Purpose: Locate captured packets by any decoded field, then optionally return the ticks around the first match - the standard way to ask 'what did the server do when X happened'. || Returns: envelope + {matches, anchor, before[], after[]}. || Inputs: `path` (field name, e.g. varp/component/script/npcId), `value` (int), `prot` (prot NAME), `session` (int), `ticks_after` (default 3), `ticks_before` (default 0), `limit`. || Use cases: 'what changed after this button', 'where was this varbit set', 'which script ran with this argument'. || Related: packetlog_window, packetlog_fields.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string"); put("description", "Decoded field name to match.") }
                    putJsonObject("value") { put("type", "integer"); put("description", "Value the field must equal.") }
                    putJsonObject("prot") { put("type", "string"); put("description", "Restrict to one prot NAME.") }
                    putJsonObject("session") { put("type", "integer"); put("description", "Restrict to one session id.") }
                    putJsonObject("ticks_after") { put("type", "integer"); put("description", "Ticks of context after the anchor (default 3).") }
                    putJsonObject("ticks_before") { put("type", "integer"); put("description", "Ticks of context before the anchor (default 0).") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Max matches to list (default 20).") }
                },
                required = emptyList(),
            ),
        ) { request ->
            safeJsonCallAsync("packetlog_find") { warnings ->
                val args = request.arguments
                val index = indexFile()
                if (!index.isFile) {
                    warnings.add("no query index at ${index.absolutePath}; build one with :packetlog:packetQuery index")
                    put("matches", 0)
                    return@safeJsonCallAsync
                }
                PacketQuery(index).use { query ->
                    val matches = query.find(
                        path = args?.get("path")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                        value = args?.get("value")?.jsonPrimitive?.content?.toLongOrNull(),
                        prot = args?.get("prot")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                        sessionId = args?.get("session")?.jsonPrimitive?.content?.toLongOrNull(),
                        limit = args?.get("limit")?.jsonPrimitive?.content?.toIntOrNull() ?: 20,
                    )
                    put("matches", matches.size)
                    putJsonArray("events") { matches.take(20).forEach { add(it.toString()) } }

                    val anchor = matches.firstOrNull() ?: return@use
                    val investigation = query.investigate(
                        anchor,
                        ticksBefore = args?.get("ticks_before")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        ticksAfter = args?.get("ticks_after")?.jsonPrimitive?.content?.toIntOrNull() ?: 3,
                    )
                    put("anchor", anchor.toString())
                    putJsonArray("before") { investigation.before.forEach { add(it.toString()) } }
                    putJsonArray("after") { investigation.after.forEach { add(it.toString()) } }
                }
            }
        }
    }

    private fun registerWindow(server: Server) {
        server.addTool(
            name = "packetlog_window",
            description = "Purpose: Return every captured packet in a tick range, decoded and in order. || Returns: envelope + {returned, events[]}. || Inputs: `session` (int, required), `from` (tick), `to` (tick), `prot` (comma-separated prot NAMEs to keep), `limit`. || Use cases: 'what happened between tick 4200 and 4210', 'show only variable traffic in this window'. || Related: packetlog_find.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("session") { put("type", "integer"); put("description", "Session id.") }
                    putJsonObject("from") { put("type", "integer"); put("description", "First tick, inclusive.") }
                    putJsonObject("to") { put("type", "integer"); put("description", "Last tick, inclusive.") }
                    putJsonObject("prot") { put("type", "string"); put("description", "Comma-separated prot NAMEs to keep.") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Max events (default 300).") }
                },
                required = listOf("session", "from", "to"),
            ),
        ) { request ->
            safeJsonCallAsync("packetlog_window") { warnings ->
                val args = request.arguments
                val index = indexFile()
                if (!index.isFile) {
                    warnings.add("no query index at ${index.absolutePath}")
                    put("returned", 0)
                    return@safeJsonCallAsync
                }
                PacketQuery(index).use { query ->
                    val events = query.window(
                        sessionId = args?.get("session")?.jsonPrimitive?.content?.toLongOrNull() ?: 1L,
                        fromTick = args?.get("from")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        toTick = args?.get("to")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        prots = args?.get("prot")?.jsonPrimitive?.content
                            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
                        limit = args?.get("limit")?.jsonPrimitive?.content?.toIntOrNull() ?: 300,
                    )
                    put("returned", events.size)
                    putJsonArray("events") { events.forEach { add(it.toString()) } }
                }
            }
        }
    }

    private fun registerFields(server: Server) {
        server.addTool(
            name = "packetlog_fields",
            description = "Purpose: List what is queryable - every decoded field, its reference domain, and whether it resolves in constant time - plus per-prot decode coverage, which is where a new decoder is worth writing. || Returns: envelope + {sessions[], fields[], coverage[]}. || Inputs: `prot` (prot NAME), `ref` (reference domain, e.g. VARP/COMPONENT/NPC). || Use cases: 'what can I filter on', 'which prots still have no decoder'. || Related: packetlog_find.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("prot") { put("type", "string"); put("description", "Restrict to one prot NAME.") }
                    putJsonObject("ref") { put("type", "string"); put("description", "Restrict to one reference domain.") }
                },
                required = emptyList(),
            ),
        ) { request ->
            safeJsonCallAsync("packetlog_fields") { warnings ->
                val args = request.arguments
                val index = indexFile()
                if (!index.isFile) {
                    warnings.add("no query index at ${index.absolutePath}")
                    return@safeJsonCallAsync
                }
                PacketQuery(index).use { query ->
                    putJsonArray("sessions") { query.sessions().forEach { add(it) } }
                    putJsonArray("fields") {
                        query.fields(
                            args?.get("prot")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                            args?.get("ref")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                        ).take(400).forEach {
                            add("${it.protName}.${it.path} [${it.refDomain}]${if (it.indexed) " indexed" else ""}")
                        }
                    }
                    putJsonArray("coverage") { query.coverage(30).forEach { add(it) } }
                }
            }
        }
    }
}
