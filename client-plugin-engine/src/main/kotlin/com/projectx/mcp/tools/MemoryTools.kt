package com.projectx.mcp.tools

import com.projectx.game.platform.PlatformMemory
import com.projectx.game.memory.NativeAccess
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

object MemoryTools {

    fun register(server: Server): Int {
        registerReadMemory(server)
        registerReadStructField(server)
        registerDerefPointer(server)
        registerDumpMatrix(server)
        return 4
    }

    private fun registerReadMemory(server: Server) {
        server.addTool(
            name = "read_memory",
            description = "Read typed value(s) at an absolute memory address. " +
                "Types: byte, short, int, long, float, double, pointer, string. " +
                "Address is relative to process base unless prefixed with 'abs:'.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("address") {
                        put("type", "string")
                        put("description", "Hex address (e.g. '0x16F8CE0'). Relative to process base by default. Prefix 'abs:' for absolute.")
                    }
                    putJsonObject("type") {
                        put("type", "string")
                        put("description", "Data type: byte, short, int, long, float, double, pointer, string")
                    }
                    putJsonObject("count") {
                        put("type", "integer")
                        put("description", "Number of values to read (default 1)")
                    }
                },
                required = listOf("address", "type")
            )
        ) { request ->
            safeCall {
                val args = request.arguments ?: error("Missing arguments")
                val address = resolveAddress(args["address"]?.jsonPrimitive?.content ?: error("Missing address"))
                val type = args["type"]?.jsonPrimitive?.content ?: error("Missing type")
                val count = args["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1

                val readSize = count * typeSize(type)
                validateAddress(address, readSize)

                val result = StringBuilder()
                result.appendLine("Reading $count x $type at 0x${address.toString(16)}")

                val seg = MemorySegment.ofAddress(address).reinterpret(count * 16L)
                for (i in 0 until count) {
                    val offset = i * typeSize(type)
                    val value = readTyped(seg, offset, type)
                    if (count > 1) result.append("[$i] ")
                    result.appendLine(value)
                }

                result.toString()
            }
        }
    }

    private fun registerReadStructField(server: Server) {
        server.addTool(
            name = "read_struct_field",
            description = "Read a typed value at base_address + offset. Convenience for struct field access.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("base_address") {
                        put("type", "string")
                        put("description", "Hex base address (relative to process base by default, prefix 'abs:' for absolute)")
                    }
                    putJsonObject("offset") {
                        put("type", "string")
                        put("description", "Hex or decimal offset (e.g. '0x194b8' or '1234')")
                    }
                    putJsonObject("type") {
                        put("type", "string")
                        put("description", "Data type: byte, short, int, long, float, double, pointer, string")
                    }
                },
                required = listOf("base_address", "offset", "type")
            )
        ) { request ->
            safeCall {
                val args = request.arguments ?: error("Missing arguments")
                val base = resolveAddress(args["base_address"]?.jsonPrimitive?.content ?: error("Missing base_address"))
                val offset = parseNumber(args["offset"]?.jsonPrimitive?.content ?: error("Missing offset"))
                val type = args["type"]?.jsonPrimitive?.content ?: error("Missing type")
                val addr = base + offset
                validateAddress(addr, typeSize(type))

                val seg = MemorySegment.ofAddress(addr).reinterpret(16L)
                val value = readTyped(seg, 0L, type)

                "base=0x${base.toString(16)} + offset=0x${offset.toString(16)} = 0x${addr.toString(16)}\nValue ($type): $value"
            }
        }
    }

    private fun registerDerefPointer(server: Server) {
        server.addTool(
            name = "deref_pointer",
            description = "Follow a pointer chain: read ptr at address, add offset[0], read ptr, add offset[1], etc. " +
                "Returns intermediate addresses and final value.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("address") {
                        put("type", "string")
                        put("description", "Starting hex address (relative to process base by default)")
                    }
                    putJsonObject("offsets") {
                        put("type", "array")
                        put("description", "Array of hex offset strings to follow (e.g. ['0x194b8', '0x10'])")
                    }
                    putJsonObject("final_type") {
                        put("type", "string")
                        put("description", "Type to read at final address (default: pointer)")
                    }
                },
                required = listOf("address", "offsets")
            )
        ) { request ->
            safeCall {
                val args = request.arguments ?: error("Missing arguments")
                val startAddr = resolveAddress(args["address"]?.jsonPrimitive?.content ?: error("Missing address"))
                val offsets = args["offsets"]?.jsonArray?.map { parseNumber(it.jsonPrimitive.content) }
                    ?: error("Missing offsets array")
                val finalType = args["final_type"]?.jsonPrimitive?.content ?: "pointer"

                val result = StringBuilder()
                result.appendLine("Start: 0x${startAddr.toString(16)}")

                var current = startAddr
                for ((i, offset) in offsets.withIndex()) {
                    val isLast = (i == offsets.lastIndex)

                    if (isLast) {
                        val finalAddr = current + offset
                        validateAddress(finalAddr, typeSize(finalType))
                        val seg = MemorySegment.ofAddress(finalAddr).reinterpret(16L)
                        val value = readTyped(seg, 0L, finalType)
                        result.appendLine("  + 0x${offset.toString(16)} = 0x${finalAddr.toString(16)} -> $finalType: $value")
                    } else {
                        validateAddress(current, 8L)
                        val seg = MemorySegment.ofAddress(current).reinterpret(8L)
                        val ptrValue = seg.get(ValueLayout.JAVA_LONG, 0L)
                        result.appendLine("  [0x${current.toString(16)}] -> 0x${ptrValue.toString(16)}")
                        current = ptrValue + offset
                        result.appendLine("  + 0x${offset.toString(16)} = 0x${current.toString(16)}")
                    }
                }

                result.toString()
            }
        }
    }

    private fun registerDumpMatrix(server: Server) {
        server.addTool(
            name = "dump_matrix",
            description = "Read 16 floats at an address as a 4x4 column-major matrix.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("address") {
                        put("type", "string")
                        put("description", "Hex address of the matrix (relative to process base by default)")
                    }
                },
                required = listOf("address")
            )
        ) { request ->
            safeCall {
                val args = request.arguments ?: error("Missing arguments")
                val addr = resolveAddress(args["address"]?.jsonPrimitive?.content ?: error("Missing address"))
                validateAddress(addr, 64L)

                val seg = MemorySegment.ofAddress(addr).reinterpret(64L)
                val m = FloatArray(16) { seg.get(ValueLayout.JAVA_FLOAT, it * 4L) }

                val result = StringBuilder()
                result.appendLine("4x4 Matrix at 0x${addr.toString(16)} (column-major):")
                for (row in 0..3) {
                    result.appendLine("  [%12.6f %12.6f %12.6f %12.6f]".format(
                        m[row], m[row + 4], m[row + 8], m[row + 12]
                    ))
                }

                result.toString()
            }
        }
    }

    fun validateAddress(addr: Long, size: Long) {
        if (addr == 0L) error("NULL pointer (address 0x0)")
        if (addr < 0) error("Negative address: 0x${addr.toULong().toString(16)}")

        // A one-byte probe through the platform reader answers "is this mapped" without needing a
        // region list, so it works identically on both platforms.
        val mapped = PlatformMemory.read(addr, ByteArray(1), 1) &&
            (size <= 1 || PlatformMemory.read(addr + size - 1, ByteArray(1), 1))
        if (!mapped) {
            error(
                "Address 0x${addr.toULong().toString(16)} (size=$size) is not in a mapped memory region. " +
                    "The game client may have been re-injected - use get_game_state to verify."
            )
        }
    }

    fun resolveAddress(raw: String): Long {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("abs:")) {
            parseNumber(trimmed.removePrefix("abs:"))
        } else {
            NativeAccess.BASE_ADDR.address() + parseNumber(trimmed)
        }
    }

    fun parseNumber(s: String): Long {
        val trimmed = s.trim()
        return if (trimmed.startsWith("0x", ignoreCase = true)) {
            java.lang.Long.parseUnsignedLong(trimmed.removePrefix("0x").removePrefix("0X"), 16)
        } else {
            trimmed.toLong()
        }
    }

    fun readTyped(seg: MemorySegment, offset: Long, type: String): String {
        return when (type.lowercase()) {
            "byte" -> {
                val v = seg.get(ValueLayout.JAVA_BYTE, offset)
                "$v (0x${(v.toInt() and 0xFF).toString(16)})"
            }
            "short" -> {
                val v = seg.get(ValueLayout.JAVA_SHORT, offset)
                "$v (0x${(v.toInt() and 0xFFFF).toString(16)})"
            }
            "int" -> {
                val v = seg.get(ValueLayout.JAVA_INT, offset)
                "$v (0x${v.toUInt().toString(16)})"
            }
            "long" -> {
                val v = seg.get(ValueLayout.JAVA_LONG, offset)
                "$v (0x${v.toULong().toString(16)})"
            }
            "float" -> {
                val v = seg.get(ValueLayout.JAVA_FLOAT, offset)
                "$v"
            }
            "double" -> {
                val v = seg.get(ValueLayout.JAVA_DOUBLE, offset)
                "$v"
            }
            "pointer", "ptr" -> {
                val v = seg.get(ValueLayout.JAVA_LONG, offset)
                "0x${v.toULong().toString(16)}" + if (v == 0L) " (NULL)" else ""
            }
            "string" -> {
                val sb = StringBuilder()
                for (i in 0 until 256) {
                    val b = seg.get(ValueLayout.JAVA_BYTE, offset + i)
                    if (b == 0.toByte()) break
                    sb.append(b.toInt().toChar())
                }
                "\"$sb\" (${sb.length} chars)"
            }
            else -> error("Unknown type: $type. Supported: byte, short, int, long, float, double, pointer, string")
        }
    }

    fun typeSize(type: String): Long {
        return when (type.lowercase()) {
            "byte" -> 1L
            "short" -> 2L
            "int", "float" -> 4L
            "long", "double", "pointer", "ptr" -> 8L
            "string" -> 256L
            else -> 8L
        }
    }

    fun safeCall(block: () -> String): CallToolResult {
        return try {
            val text = onGameTick { block() }
            CallToolResult(content = listOf(TextContent(text = text)))
        } catch (e: Throwable) {
            CallToolResult(
                content = listOf(TextContent(text = "ERROR: ${e.javaClass.simpleName}: ${e.message}")),
                isError = true
            )
        }
    }
}
