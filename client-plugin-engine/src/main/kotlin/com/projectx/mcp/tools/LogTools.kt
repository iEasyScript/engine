package com.projectx.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.RandomAccessFile

/**
 * GENERIC (keep). Tails the newest engine log (`~/.projectx/logs/projectx-*.log`, where every `println`
 * lands) with optional substring / errors-only filtering. This is how an agent detects crashes, exception
 * loops, and stuck states while running headless. Runs off the game tick (pure file I/O).
 */
object LogTools {

    private val ERROR_REGEX = Regex(
        "Exception|Error|SIGSEGV|Caused by|<clinit>|\\tat |NoClassDefFound|FATAL|Timed out|failed",
        RegexOption.IGNORE_CASE
    )

    fun register(server: Server): Int {
        registerReadLogs(server)
        return 1
    }

    private fun newestLog(): File? {
        val dir = File(System.getProperty("user.home"), ".projectx/logs")
        if (!dir.isDirectory) return null
        return dir.listFiles { f -> f.name.startsWith("projectx-") && f.name.endsWith(".log") }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun tailLines(file: File, maxBytes: Long): List<String> {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val from = (len - maxBytes).coerceAtLeast(0)
            raf.seek(from)
            val bytes = ByteArray((len - from).toInt())
            raf.readFully(bytes)
            var text = String(bytes)
            if (from > 0) text = text.substringAfter('\n') // drop the partial first line
            return text.split('\n')
        }
    }

    private fun registerReadLogs(server: Server) {
        server.addTool(
            name = "read_logs",
            description = "Purpose: Return the tail of the newest engine log with optional filtering - the primary way to spot crashes, exception loops, and stuck states while running headless. || Returns: envelope + {file, size, returned, lines[]}. || Inputs: `lines` (int, default 100, max 2000), `grep` (case-insensitive substring filter), `errors_only` (bool - keep only exception/stack-frame/timeout lines). || Use cases: 'Is the bot throwing every tick?', 'grep for ScriptsTab', 'show the last stack trace'. || Related: list_scripts, dungeon_state.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("lines") { put("type", "integer"); put("description", "Max lines from the tail (default 100, max 2000).") }
                    putJsonObject("grep") { put("type", "string"); put("description", "Case-insensitive substring; only matching lines are returned.") }
                    putJsonObject("errors_only") { put("type", "boolean"); put("description", "Keep only lines that look like errors/exceptions/stack frames. Default false.") }
                },
                required = emptyList(),
            ),
        ) { request ->
            safeJsonCallAsync("read_logs") { _ ->
                val args = request.arguments
                val limit = (args?.get("lines")?.jsonPrimitive?.content?.toIntOrNull() ?: 100).coerceIn(1, 2000)
                val grep = args?.get("grep")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val errorsOnly = args?.get("errors_only")?.jsonPrimitive?.content?.toBoolean() ?: false

                val file = newestLog()
                if (file == null) {
                    put("file", "")
                    put("returned", 0)
                    putJsonArray("lines") { }
                    return@safeJsonCallAsync
                }
                put("file", file.name)
                put("size", file.length())

                var lines: List<String> = tailLines(file, 1_048_576L)
                if (grep != null) lines = lines.filter { it.contains(grep, ignoreCase = true) }
                if (errorsOnly) lines = lines.filter { ERROR_REGEX.containsMatchIn(it) }
                val out = lines.filter { it.isNotBlank() }.takeLast(limit)

                put("returned", out.size)
                putJsonArray("lines") { out.forEach { add(it) } }
            }
        }
    }
}
