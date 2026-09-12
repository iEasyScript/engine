package com.projectx.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object MetaTools {

    fun register(server: Server): Int {
        registerValidateMemory(server)
        return 1
    }

    private fun registerValidateMemory(server: Server) {
        server.addTool(
            name = "validate_memory",
            description = """
                Purpose: Probe whether a memory address (and an optional byte range starting there) lies inside a region currently mapped in this injected process, by probing it through the platform's fault-tolerant reader. Pure runtime state - does not duplicate static engine metadata.
                || Returns: JSON envelope with `mapped` (boolean), `addr` (resolved absolute hex), `size`, and `reason` (string explanation when not mapped: NULL, negative, or unmapped). Always includes the tool's standard envelope fields (tool, base_addr, client_cycle, main_state).
                || Inputs: `address` (required hex string; base-relative by default, prefix `abs:` for absolute). `size` (optional int, default 1) - the number of bytes to verify are all in a single mapped region.
                || Use cases: "Is this NPC entity pointer still valid after a world reload?", "Did the game re-inject?", "Before I drill into 0x... with read_memory, is it mapped?". Pair with read_memory and read_struct_field to avoid SIGSEGVs on stale pointers.
                || Related tools: read_memory, read_struct_field, deref_pointer, get_game_state.
                || Pitfalls: A region being mapped does NOT mean the bytes there are still semantically valid (e.g., heap memory may have been freed and reused). This only catches unmapped/freed regions. Address `0x0` is reported as NULL, not unmapped.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("address") {
                        put("type", "string")
                        put("description", "Hex address (e.g. '0x16F8CE0'). Base-relative by default; prefix 'abs:' for absolute.")
                    }
                    putJsonObject("size") {
                        put("type", "integer")
                        put("description", "Number of bytes to verify (default 1). Useful when checking a full struct.")
                    }
                },
                required = listOf("address"),
            ),
        ) { request ->
            safeJsonCall("validate_memory") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val rawAddr = args["address"]?.jsonPrimitive?.content ?: throw BadRequest("missing 'address'")
                val size = (args["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1L).coerceAtLeast(1L)
                val addr = try {
                    MemoryTools.resolveAddress(rawAddr)
                } catch (e: Throwable) {
                    throw BadRequest("malformed address '$rawAddr': ${e.message}")
                }

                putHex("addr", addr)
                put("size", size)

                if (addr == 0L) {
                    put("mapped", false)
                    put("reason", "NULL pointer (0x0)")
                    return@safeJsonCall
                }
                if (addr < 0) {
                    put("mapped", false)
                    put("reason", "negative address")
                    return@safeJsonCall
                }
                try {
                    MemoryTools.validateAddress(addr, size)
                    put("mapped", true)
                } catch (t: Throwable) {
                    put("mapped", false)
                    put("reason", t.message ?: "unmapped")
                }
            }
        }
    }
}
