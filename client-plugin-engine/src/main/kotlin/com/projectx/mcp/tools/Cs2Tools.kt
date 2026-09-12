package com.projectx.mcp.tools

import com.projectx.game.cs2.CS2Executor
import com.projectx.game.cs2.CS2Trace
import com.projectx.game.memory.NativeAccess
import com.projectx.game.nxt.OFunctions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object Cs2Tools {

    private const val DUMP_DIR_NAME = "cs2-dump"
    private const val DUMP_MISSING = "$DUMP_DIR_NAME/ not found; run `gradlew :tools:cs2 -Pargs=\"decompile-all $DUMP_DIR_NAME\"` or set PROJECTX_DUMPS_DIR"

    private var scriptDirCache: Path? = null
    private var opcodeMapCache: Map<Int, OpcodeRow>? = null

    data class OpcodeRow(val opcode: Int, val hex: String, val handlerAddr: Long, val ghidraName: String)

    fun register(server: Server): Int {
        registerGetCs2Script(server)
        registerSearchCs2Scripts(server)
        registerGetCs2OpcodeInfo(server)
        registerFindCs2Callers(server)
        registerCs2ExecutorStatus(server)
        registerCs2Trace(server)
        return 6
    }

    private fun locateScriptsDir(): Path? {
        scriptDirCache?.let { if (Files.isDirectory(it)) return it }
        val env = System.getenv("PROJECTX_DUMPS_DIR")
        val cwd = Paths.get(System.getProperty("user.dir"))
        val candidates = mutableListOf<Path>()
        if (env != null) candidates.add(Paths.get(env))
        candidates.add(cwd.resolve(DUMP_DIR_NAME))
        cwd.parent?.resolve(DUMP_DIR_NAME)?.let { candidates.add(it) }
        val found = candidates.firstOrNull { Files.isDirectory(it) }
        scriptDirCache = found
        return found
    }

    private fun scriptPathFor(id: Int): Path? = locateScriptsDir()?.resolve("clientscript-$id.ts")

    private fun loadOpcodeMap(): Map<Int, OpcodeRow> {
        opcodeMapCache?.let { return it }
        val cwd = Paths.get(System.getProperty("user.dir"))
        val candidates = listOfNotNull(
            cwd.resolve("re-resources/symbols/cs2_opcode_handlers.txt"),
            cwd.parent?.resolve("re-resources/symbols/cs2_opcode_handlers.txt"),
        )
        val file = candidates.firstOrNull { Files.isRegularFile(it) }
            ?: throw EngineState("cs2_opcode_handlers.txt not found in any of: $candidates")
        val map = mutableMapOf<Int, OpcodeRow>()
        Files.lines(file).use { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split("|").map { it.trim() }
                if (parts.size < 4) continue
                runCatching {
                    val opcode = parts[0].toInt()
                    val hex = parts[1]
                    val handlerAddr = java.lang.Long.parseUnsignedLong(parts[2].removePrefix("0x"), 16)
                    val ghidraName = parts[3]
                    map[opcode] = OpcodeRow(opcode, hex, handlerAddr, ghidraName)
                }
            }
        }
        opcodeMapCache = map
        return map
    }

    private fun registerGetCs2Script(server: Server) {
        server.addTool(
            name = "get_cs2_script",
            description = """
                Purpose: Read the decompiled source of a single CS2 client script by id from the cs2-dump/ folder produced by `gradlew :tools:cs2 decompile-all`. Returns the full source text plus metadata (line count, byte size, file path).
                || Returns: JSON envelope with: script_id, exists, path, lines, size_bytes, source (the file content; only included when line_range or full file requested).
                || Inputs: `id` (required int). `line_range` (optional string "start-end", 1-indexed) - return only those lines. `head` (optional int) - return only the first N lines. `tail` (optional int) - return only the last N lines. If multiple slicing params are passed, head takes precedence.
                || Use cases: "Show me the source of clientscript-75 to understand what it does", "Read the first 30 lines of a 2000-line script to find its signature", "Inspect the dialog continuation script".
                || Related tools: search_cs2_scripts (regex grep across all scripts), find_cs2_callers (who uses this varbit/varp/interface), get_cs2_opcode_info (handler addr for an opcode).
                || Pitfalls: The dump is generated from the cache on disk; regenerate it after a cache update or the sources will lag the running client. Missing script ids return exists=false (not an error). Scripts can be very large (>10k lines); always slice for unknown ids.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("id") {
                        put("type", "integer")
                        put("description", "CS2 script id")
                    }
                    putJsonObject("line_range") {
                        put("type", "string")
                        put("description", "Slice e.g. '1-50'. Optional.")
                    }
                    putJsonObject("head") {
                        put("type", "integer")
                        put("description", "Return only the first N lines. Optional.")
                    }
                    putJsonObject("tail") {
                        put("type", "integer")
                        put("description", "Return only the last N lines. Optional.")
                    }
                },
                required = listOf("id"),
            ),
        ) { request ->
            safeJsonCall("get_cs2_script") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val id = args["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw BadRequest("missing/invalid 'id'")
                val path = scriptPathFor(id) ?: throw EngineState(DUMP_MISSING)

                put("script_id", id)
                put("path", path.toAbsolutePath().toString())
                if (!Files.exists(path)) {
                    put("exists", false)
                    return@safeJsonCall
                }
                put("exists", true)
                val text = Files.readString(path)
                val lines = text.split("\n")
                put("lines", lines.size)
                put("size_bytes", text.length)

                val head = args["head"]?.jsonPrimitive?.content?.toIntOrNull()
                val tail = args["tail"]?.jsonPrimitive?.content?.toIntOrNull()
                val rangeStr = args["line_range"]?.jsonPrimitive?.content

                val sliced = when {
                    head != null -> lines.take(head)
                    tail != null -> lines.takeLast(tail)
                    rangeStr != null -> {
                        val parts = rangeStr.split("-")
                        val start = parts.getOrNull(0)?.toIntOrNull() ?: 1
                        val end = parts.getOrNull(1)?.toIntOrNull() ?: lines.size
                        lines.subList((start - 1).coerceAtLeast(0), end.coerceAtMost(lines.size))
                    }
                    else -> lines
                }
                put("source", sliced.joinToString("\n"))
            }
        }
    }

    private fun registerSearchCs2Scripts(server: Server) {
        server.addTool(
            name = "search_cs2_scripts",
            description = """
                Purpose: Search every clientscript-*.ts file in cs2-dump/ for a query string or regex. Returns each hit with the script id, line number, matched line, and optional context lines.
                || Returns: JSON envelope with query, regex, count, total_files_scanned, items[]. Each item: script_id, line, text, context_before[], context_after[].
                || Inputs: `query` (required string), `regex` (optional bool, default false), `case_sensitive` (optional bool, default false), `before` (optional int, default 0) lines of context above match, `after` (optional int, default 0), `limit` (optional int, default 50, hard max 500).
                || Use cases: "Where is varbit_9159 referenced?", "Find every script calling gosub_with_params(75, ...)", "Look up uses of an opcode name".
                || Related tools: find_cs2_callers (kind-specific patterns for varbit/varp/interface/struct/etc), get_cs2_script (read a specific script).
                || Pitfalls: ~20k files; first-call latency is fs-bound (~200-500ms warm). Regex compile errors are returned as bad_request. Pure-substring queries are faster than regex.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Search query (substring or regex pattern depending on `regex`)")
                    }
                    putJsonObject("regex") {
                        put("type", "boolean")
                        put("description", "Treat query as a regex. Default false.")
                    }
                    putJsonObject("case_sensitive") {
                        put("type", "boolean")
                        put("description", "Case-sensitive match. Default false.")
                    }
                    putJsonObject("before") {
                        put("type", "integer")
                        put("description", "Lines of context before each match")
                    }
                    putJsonObject("after") {
                        put("type", "integer")
                        put("description", "Lines of context after each match")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "Max hits returned (default 50, hard max 500)")
                    }
                },
                required = listOf("query"),
            ),
        ) { request ->
            safeJsonCall("search_cs2_scripts") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val query = args["query"]?.jsonPrimitive?.content ?: throw BadRequest("missing 'query'")
                val isRegex = args["regex"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val caseSensitive = args["case_sensitive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val before = args["before"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val after = args["after"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = (args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 50).coerceIn(1, 500)

                val dir = locateScriptsDir() ?: throw EngineState(DUMP_MISSING)

                val pattern: Regex? = if (isRegex) {
                    val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                    try { Regex(query, opts) } catch (t: Throwable) { throw BadRequest("regex error: ${t.message}") }
                } else null

                val needle = query
                val hits = mutableListOf<JsonPrimitive>()

                put("query", query)
                put("regex", isRegex)
                put("case_sensitive", caseSensitive)

                var totalFiles = 0
                val itemsArr = buildJsonArray {
                    Files.list(dir).use { stream ->
                        for (path in stream) {
                            if (hits.size >= limit) break
                            val name = path.fileName.toString()
                            if (!name.startsWith("clientscript-") || !name.endsWith(".ts")) continue
                            totalFiles++
                            val text = runCatching { Files.readString(path) }.getOrNull() ?: continue
                            val matchPos: Int = if (pattern != null) {
                                if (pattern.containsMatchIn(text)) 0 else -1
                            } else {
                                if (caseSensitive) text.indexOf(needle) else text.indexOf(needle, ignoreCase = true)
                            }
                            if (matchPos < 0) continue

                            val lines = text.split("\n")
                            for ((idx, line) in lines.withIndex()) {
                                val match = if (pattern != null) pattern.containsMatchIn(line)
                                else if (caseSensitive) line.contains(needle)
                                else line.contains(needle, ignoreCase = true)
                                if (!match) continue
                                if (hits.size >= limit) break

                                val scriptId = name.removePrefix("clientscript-").removeSuffix(".ts").toIntOrNull() ?: -1
                                add(buildJsonObject {
                                    put("script_id", scriptId)
                                    put("line", idx + 1)
                                    put("text", line)
                                    if (before > 0) {
                                        put("context_before", buildJsonArray {
                                            for (i in (idx - before).coerceAtLeast(0) until idx) {
                                                add(JsonPrimitive(lines[i]))
                                            }
                                        })
                                    }
                                    if (after > 0) {
                                        put("context_after", buildJsonArray {
                                            for (i in (idx + 1)..(idx + after).coerceAtMost(lines.size - 1)) {
                                                add(JsonPrimitive(lines[i]))
                                            }
                                        })
                                    }
                                })
                                hits.add(JsonPrimitive("hit"))
                            }
                        }
                    }
                }
                put("total_files_scanned", totalFiles)
                put("items", itemsArr)
                put("count", hits.size)
                put("truncated", hits.size == limit)
            }
        }
    }

    private fun registerGetCs2OpcodeInfo(server: Server) {
        server.addTool(
            name = "get_cs2_opcode_info",
            description = """
                Purpose: Look up a CS2 opcode by id and return its native handler address (absolute hex) and Ghidra function name from the project-root cs2_opcode_handlers.txt table.
                || Returns: JSON envelope with: opcode, hex, handler_addr (absolute hex string), handler_addr_rel (base-relative), ghidra_name. When the opcode is not in the table, returns exists=false.
                || Inputs: `opcode` (optional int) - pass either this OR `hex` (optional hex string). One of the two is required.
                || Use cases: "What native function implements opcode 0x1234?", "Get the handler address so I can read its bytes with read_memory", "Map an unknown opcode in a script back to a Ghidra function name".
                || Related tools: read_memory (drill into handler bytes), find_cs2_callers (who calls this opcode), search_cs2_scripts (find scripts using opcode name).
                || Pitfalls: The table at cs2_opcode_handlers.txt is generated offline from RegisterAllOpcodes; it can be stale if the binary version changed. Handler addrs are absolute in the loaded process image.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("opcode") {
                        put("type", "integer")
                        put("description", "Decimal opcode id")
                    }
                    putJsonObject("hex") {
                        put("type", "string")
                        put("description", "Hex opcode, e.g. '0x1234'")
                    }
                },
            ),
        ) { request ->
            safeJsonCall("get_cs2_opcode_info") { _ ->
                val args = request.arguments
                val opcode = args?.get("opcode")?.jsonPrimitive?.content?.toIntOrNull()
                    ?: args?.get("hex")?.jsonPrimitive?.content?.let { java.lang.Long.parseUnsignedLong(it.removePrefix("0x").removePrefix("0X"), 16).toInt() }
                    ?: throw BadRequest("must provide either 'opcode' (int) or 'hex' (string)")
                val map = loadOpcodeMap()
                val row = map[opcode]
                put("opcode", opcode)
                put("hex", "0x" + opcode.toString(16).padStart(4, '0'))
                if (row == null) {
                    put("exists", false)
                } else {
                    put("exists", true)
                    putHex("handler_addr", row.handlerAddr + NativeAccess.BASE_ADDR.address())
                    put("handler_addr_rel", "0x" + row.handlerAddr.toString(16))
                    put("ghidra_name", row.ghidraName)
                }
            }
        }
    }

    private fun registerFindCs2Callers(server: Server) {
        server.addTool(
            name = "find_cs2_callers",
            description = """
                Purpose: Find every CS2 client script that references a given content id of a given kind (varbit, varp, interface, script, enum, struct, dbrow). Builds a kind-specific regex and runs it across all scripts via the search_cs2_scripts engine.
                || Returns: JSON envelope with kind, target_id, patterns (the regexes used), count, items[]. Each item: script_id, line, text.
                || Inputs: `target_kind` (required string: varbit|varp|interface|script|enum|struct|dbrow), `target_id` (required int), `limit` (optional int, default 50).
                || Use cases: "Which scripts read varbit 9159 (Cook's Assistant)?", "Who calls script 75?", "Find every reference to interface 1188".
                || Related tools: search_cs2_scripts (raw query), get_cs2_script (read a hit's source).
                || Pitfalls: Patterns are heuristic - they match common naming conventions (`varbit_NNNN`, `getvarbit(NNNN`, etc.) but may miss obfuscated or unusual usages. Cross-check with search_cs2_scripts using your own query if a hit is missing.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("target_kind") {
                        put("type", "string")
                        put("description", "Kind: varbit|varp|interface|script|enum|struct|dbrow")
                    }
                    putJsonObject("target_id") {
                        put("type", "integer")
                        put("description", "Content id to find callers of")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "Max hits (default 50, hard max 500)")
                    }
                },
                required = listOf("target_kind", "target_id"),
            ),
        ) { request ->
            safeJsonCall("find_cs2_callers") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val kind = args["target_kind"]?.jsonPrimitive?.content?.lowercase()
                    ?: throw BadRequest("missing 'target_kind'")
                val id = args["target_id"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: throw BadRequest("missing/invalid 'target_id'")
                val limit = (args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 50).coerceIn(1, 500)

                val patterns = patternsForKind(kind, id) ?: throw BadRequest("unknown target_kind '$kind'")
                put("kind", kind)
                put("target_id", id)
                put("patterns", buildJsonArray { for (p in patterns) add(JsonPrimitive(p)) })

                val dir = locateScriptsDir() ?: throw EngineState(DUMP_MISSING)
                val regexes = patterns.map { Regex(it) }

                val hits = mutableListOf<Map<String, Any>>()
                Files.list(dir).use { stream ->
                    for (path in stream) {
                        if (hits.size >= limit) break
                        val name = path.fileName.toString()
                        if (!name.startsWith("clientscript-") || !name.endsWith(".ts")) continue
                        val text = runCatching { Files.readString(path) }.getOrNull() ?: continue
                        if (regexes.none { it.containsMatchIn(text) }) continue
                        val scriptId = name.removePrefix("clientscript-").removeSuffix(".ts").toIntOrNull() ?: -1
                        val lines = text.split("\n")
                        for ((idx, line) in lines.withIndex()) {
                            if (regexes.any { it.containsMatchIn(line) }) {
                                if (hits.size >= limit) break
                                hits.add(mapOf("script_id" to scriptId, "line" to (idx + 1), "text" to line))
                            }
                        }
                    }
                }

                put("count", hits.size)
                put("truncated", hits.size == limit)
                put("items", buildJsonArray {
                    for (h in hits) {
                        add(buildJsonObject {
                            put("script_id", h["script_id"] as Int)
                            put("line", h["line"] as Int)
                            put("text", h["text"] as String)
                        })
                    }
                })
            }
        }
    }

    private fun patternsForKind(kind: String, id: Int): List<String>? = when (kind) {
        "varbit" -> listOf("\\bvarbit_${id}\\b", "\\bgetvarbit\\(${id}[,)]", "\\bsetvarbit\\(${id}[,)]")
        "varp" -> listOf("\\bvarplayer_${id}\\b", "\\bvarclient_${id}\\b", "\\bgetvar\\(${id}[,)]", "\\bsetvar\\(${id}[,)]")
        "interface" -> listOf("\\binterface_${id}\\b", "\\bif_set[A-Za-z]+\\(\\d+,\\s*${id}[,)]")
        "script" -> listOf("\\bscript${id}\\b", "\\bgosub_with_params\\(${id}[,)]", "\\bgosub\\(${id}[,)]")
        "enum" -> listOf("\\benum_getoutputvalue\\(${id}[,)]", "\\benum_getoutputs\\(${id}[,)]")
        "struct" -> listOf("\\bstruct_getparam\\(${id}[,)]", "\\bgetparam\\([^,]*,\\s*${id}[,)]")
        "dbrow" -> listOf("\\bdbrow_getfield\\(${id}[,)]", "\\b${id}\\s+as\\s+dbrow\\b")
        else -> null
    }

    private fun registerCs2ExecutorStatus(server: Server) {
        server.addTool(
            name = "cs2_executor_status",
            description = """
                Purpose: Diagnostic snapshot of the CS2 script executor - whether the ScriptRunner pointer has been captured, how many scripts are queued, the script-runner heap address, and the ExecuteHookInner native function address (so the agent can disassemble it via read_memory).
                || Returns: JSON envelope with: is_ready, pending_count, script_runner_addr, execute_hook_inner_addr, execute_hook_inner_rel.
                || Inputs: none.
                || Use cases: "Why isn't execute_cs2_script working - is the executor ready yet?", "What's the queue depth right now?", "Get the ScriptRunner addr to drill into its struct".
                || Related tools: execute_cs2_script (queue a script), read_memory (drill into the captured ScriptRunner).
                || Pitfalls: is_ready=false means ExecuteHookInner has not yet been called by the game, so the ScriptRunner pointer is unknown - wait until the player is logged in and engaged with the world.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) { _ ->
            safeJsonCall("cs2_executor_status") { _ ->
                put("is_ready", CS2Executor.isReady())
                put("pending_count", CS2Executor.pendingCount())
                val ehAddr = NativeAccess.BASE_ADDR.address() + OFunctions.SCRIPTRUNNER_EXECUTEHOOKINNER
                putHex("execute_hook_inner_addr", ehAddr)
                put("execute_hook_inner_rel", "0x" + OFunctions.SCRIPTRUNNER_EXECUTEHOOKINNER.toString(16))
            }
        }
    }

    private fun registerCs2Trace(server: Server) {
        server.addTool(
            name = "cs2_trace",
            description = """
                Purpose: Control and read the CS2 script interceptor - captures every executed script's id, entry arguments, and return values into a ring buffer (also shown in the Developer > CS2 Trace panel). Use it to discover which script(s) a UI interaction (e.g. dragging a slider) fires and with what parameters.
                || Returns: for action=read, JSON with `entries` (most-recent-first), each: seq, script_id, arg_ints/arg_longs/arg_strings, ret_ints/ret_longs/ret_strings. For enable/disable/clear: the new enabled state and buffer size.
                || Inputs: `action` ("enable"|"disable"|"clear"|"read", default "read"). `script_id` (optional int filter). `limit` (optional int, default 50, max 500).
                || Use cases: "Turn on interception, I'll drag the quantity slider, then show me what fired", "What args did script 10450 get called with?", "Clear the trace before a fresh capture".
                || Related tools: execute_cs2_script (replay a captured call), get_cs2_script (read the source of a captured script id).
                || Pitfalls: interception adds a small per-execution read cost while enabled - disable when done. The buffer holds the last 2000 executions.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("action") { put("type", "string"); put("description", "enable | disable | clear | read") }
                    putJsonObject("script_id") { put("type", "integer"); put("description", "Only return executions of this script id") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Max entries (default 50, max 500)") }
                },
            ),
        ) { request ->
            safeJsonCall("cs2_trace") { _ ->
                val args = request.arguments
                val action = args?.get("action")?.jsonPrimitive?.content ?: "read"
                val reqId = args?.get("script_id")?.jsonPrimitive?.content?.toIntOrNull()
                when (action) {
                    "enable" -> CS2Trace.enabled = true
                    "disable" -> CS2Trace.enabled = false
                    "clear" -> CS2Trace.clear()
                    "blacklist" -> CS2Trace.addBlacklist(reqId ?: throw BadRequest("blacklist needs script_id"))
                    "unblacklist" -> CS2Trace.removeBlacklist(reqId ?: throw BadRequest("unblacklist needs script_id"))
                    "read" -> {}
                    else -> throw BadRequest("unknown action '$action' (enable|disable|clear|read|blacklist|unblacklist)")
                }
                put("enabled", CS2Trace.enabled)
                put("action", action)
                put("blacklisted", buildJsonArray { CS2Trace.blacklistedIds().forEach { add(JsonPrimitive(it)) } })
                if (action == "read") {
                    val filterId = args?.get("script_id")?.jsonPrimitive?.content?.toIntOrNull()
                    val limit = (args?.get("limit")?.jsonPrimitive?.content?.toIntOrNull() ?: 50).coerceIn(1, 500)
                    val snap = CS2Trace.snapshot()
                    put("buffer_size", snap.size)
                    val selected = snap.asReversed()
                        .filter { filterId == null || it.scriptId == filterId }
                        .take(limit)
                    put("entries", buildJsonArray {
                        selected.forEach { e ->
                            add(buildJsonObject {
                                put("seq", e.seq)
                                put("script_id", e.scriptId)
                                put("arg_ints", buildJsonArray { e.argInts.forEach { add(JsonPrimitive(it)) } })
                                put("arg_longs", buildJsonArray { e.argLongs.forEach { add(JsonPrimitive(it)) } })
                                put("arg_strings", buildJsonArray { e.argStrings.forEach { add(JsonPrimitive(it)) } })
                                put("ret_ints", buildJsonArray { e.retInts.forEach { add(JsonPrimitive(it)) } })
                                put("ret_longs", buildJsonArray { e.retLongs.forEach { add(JsonPrimitive(it)) } })
                                put("ret_strings", buildJsonArray { e.retStrings.forEach { add(JsonPrimitive(it)) } })
                            })
                        }
                    })
                } else {
                    put("buffer_size", CS2Trace.snapshot().size)
                }
            }
        }
    }
}
