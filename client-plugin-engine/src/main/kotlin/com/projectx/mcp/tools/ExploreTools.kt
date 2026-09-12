package com.projectx.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

object ExploreTools {

    fun register(server: Server): Int {
        registerScanMemoryRegion(server)
        registerScanForValue(server)
        registerXrefPointerTo(server)
        registerDumpStructAt(server)
        registerCompareMemory(server)
        return 5
    }

    private fun parseHexBytes(hex: String): Pair<ByteArray, BooleanArray> {
        val tokens = hex.trim().split(Regex("\\s+"))
        val bytes = ByteArray(tokens.size)
        val wildcards = BooleanArray(tokens.size)
        for ((i, tok) in tokens.withIndex()) {
            val clean = tok.removePrefix("0x")
            if (clean == "??" || clean == "?") {
                wildcards[i] = true
                bytes[i] = 0
            } else {
                val b = clean.toInt(16)
                bytes[i] = b.toByte()
            }
        }
        return bytes to wildcards
    }

    private fun registerScanMemoryRegion(server: Server) {
        server.addTool(
            name = "scan_memory_region",
            description = """
                Purpose: Linear byte-pattern search within a single mapped memory region. Validates the region is mapped before scanning. Pattern syntax: space-separated hex bytes; `??` or `?` for wildcards (e.g. "DE AD BE EF" or "12 ?? 34 ??").
                || Returns: JSON envelope with: addr (region start), size, pattern, matches (count), items[]. Each match: addr (absolute hex), offset_in_region.
                || Inputs: `address` (required hex string; base-relative by default, prefix abs:), `size` (required int - bytes to scan, hard max 16777216 = 16MB per call), `pattern` (required string - hex with optional wildcards), `limit` (optional int, default 100, max 10000).
                || Use cases: "I found this magic constant in memory - where else does it appear?", "Scan a region for a specific 8-byte struct header pattern".
                || Related tools: scan_for_value (typed scan), xref_pointer_to (pointer scan), read_memory (read at a hit).
                || Pitfalls: Address must be mapped - call validate_memory first if uncertain. Scans are O(N) per call; 16MB takes ~50ms. Wildcards are byte-level only (no nibble wildcards). No SIMD optimization - for very large scans, narrow the address range.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("address") { put("type", "string"); put("description", "Region start (hex, base-rel default or abs: prefix)") }
                    putJsonObject("size") { put("type", "integer"); put("description", "Region size in bytes (max 16777216)") }
                    putJsonObject("pattern") { put("type", "string"); put("description", "Hex pattern with optional ?? wildcards") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Max matches (default 100, max 10000)") }
                },
                required = listOf("address", "size", "pattern"),
            ),
        ) { request ->
            safeJsonCall("scan_memory_region") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val rawAddr = args["address"]?.jsonPrimitive?.content ?: throw BadRequest("missing 'address'")
                val size = args["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: throw BadRequest("missing/invalid 'size'")
                if (size <= 0 || size > 16_777_216L) throw BadRequest("size must be 1..16777216")
                val pattern = args["pattern"]?.jsonPrimitive?.content ?: throw BadRequest("missing 'pattern'")
                val limit = (args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100).coerceIn(1, 10000)

                val addr = MemoryTools.resolveAddress(rawAddr)
                requireMappedAddress(addr, size, "scan region")

                val (needle, wildcards) = parseHexBytes(pattern)
                if (needle.isEmpty()) throw BadRequest("empty pattern")

                val seg = MemorySegment.ofAddress(addr).reinterpret(size)
                val matches = mutableListOf<Long>()
                val end = size - needle.size + 1
                var i = 0L
                while (i < end && matches.size < limit) {
                    var ok = true
                    for (j in needle.indices) {
                        if (wildcards[j]) continue
                        if (seg.get(ValueLayout.JAVA_BYTE, i + j) != needle[j]) {
                            ok = false; break
                        }
                    }
                    if (ok) matches.add(addr + i)
                    i++
                }

                putHex("addr", addr)
                put("size", size)
                put("pattern", pattern)
                put("matches", matches.size)
                put("truncated", matches.size == limit)
                put("items", buildJsonArray {
                    for (m in matches) {
                        add(buildJsonObject {
                            putHex("addr", m)
                            put("offset_in_region", m - addr)
                        })
                    }
                })
            }
        }
    }

    private fun registerScanForValue(server: Server) {
        server.addTool(
            name = "scan_for_value",
            description = """
                Purpose: Scan a memory region for a typed value (int / long / float). Slot-aligned scan stride matches the value's natural size - int reads at every 4-byte position, long at every 8, etc.
                || Returns: JSON envelope with: addr (region start), size, type, value, matches, items[] (each {addr, offset_in_region}).
                || Inputs: `address` (required), `size` (required, max 16777216), `type` (required: int|long|float), `value` (required number - exact match), `limit` (default 100, max 10000), `stride` (optional int - override default per-type stride, e.g. stride=1 for unaligned scan).
                || Use cases: "Find every int that equals 0xDEADBEEF in this region", "Locate every float == 1.015748 (skybox-transition marker)".
                || Related tools: scan_memory_region (byte pattern), xref_pointer_to (pointer-equality scan).
                || Pitfalls: Floats compared with bit-exact equality (no epsilon). Unaligned scans (stride < type size) catch more matches but multiply work. Large regions × small stride can be slow.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("address") { put("type", "string"); put("description", "Region start") }
                    putJsonObject("size") { put("type", "integer"); put("description", "Region size in bytes") }
                    putJsonObject("type") { put("type", "string"); put("description", "int | long | float") }
                    putJsonObject("value") { put("type", "number"); put("description", "Value to match") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Max hits (default 100, max 10000)") }
                    putJsonObject("stride") { put("type", "integer"); put("description", "Scan stride in bytes (default = type size)") }
                },
                required = listOf("address", "size", "type", "value"),
            ),
        ) { request ->
            safeJsonCall("scan_for_value") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val rawAddr = args["address"]?.jsonPrimitive?.content ?: throw BadRequest("missing 'address'")
                val size = args["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: throw BadRequest("missing/invalid 'size'")
                if (size <= 0 || size > 16_777_216L) throw BadRequest("size must be 1..16777216")
                val type = args["type"]?.jsonPrimitive?.content?.lowercase() ?: throw BadRequest("missing 'type'")
                val valueStr = args["value"]?.jsonPrimitive?.content ?: throw BadRequest("missing 'value'")
                val limit = (args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100).coerceIn(1, 10000)

                val addr = MemoryTools.resolveAddress(rawAddr)
                requireMappedAddress(addr, size, "scan region")
                val seg = MemorySegment.ofAddress(addr).reinterpret(size)

                val (typeSize, stride) = when (type) {
                    "int" -> 4L to (args["stride"]?.jsonPrimitive?.content?.toLongOrNull() ?: 4L)
                    "long" -> 8L to (args["stride"]?.jsonPrimitive?.content?.toLongOrNull() ?: 8L)
                    "float" -> 4L to (args["stride"]?.jsonPrimitive?.content?.toLongOrNull() ?: 4L)
                    else -> throw BadRequest("type must be int|long|float")
                }

                val matches = mutableListOf<Long>()
                val end = size - typeSize + 1
                var i = 0L

                when (type) {
                    "int" -> {
                        val want = valueStr.toInt()
                        while (i < end && matches.size < limit) {
                            if (seg.get(ValueLayout.JAVA_INT, i) == want) matches.add(addr + i)
                            i += stride
                        }
                    }
                    "long" -> {
                        val want = valueStr.toLong()
                        while (i < end && matches.size < limit) {
                            if (seg.get(ValueLayout.JAVA_LONG, i) == want) matches.add(addr + i)
                            i += stride
                        }
                    }
                    "float" -> {
                        val want = valueStr.toFloat()
                        val wantBits = java.lang.Float.floatToRawIntBits(want)
                        while (i < end && matches.size < limit) {
                            if (seg.get(ValueLayout.JAVA_INT, i) == wantBits) matches.add(addr + i)
                            i += stride
                        }
                    }
                }

                putHex("addr", addr)
                put("size", size)
                put("type", type)
                put("value", valueStr)
                put("stride", stride)
                put("matches", matches.size)
                put("truncated", matches.size == limit)
                put("items", buildJsonArray {
                    for (m in matches) {
                        add(buildJsonObject {
                            putHex("addr", m)
                            put("offset_in_region", m - addr)
                        })
                    }
                })
            }
        }
    }

    private fun registerXrefPointerTo(server: Server) {
        server.addTool(
            name = "xref_pointer_to",
            description = """
                Purpose: Find every 8-byte aligned slot in a region whose value equals a target pointer address. The "who references this object?" tool - useful for finding parent structures that hold a known child pointer.
                || Returns: JSON envelope with: target_addr, region_addr, size, matches, items[] (each {addr, offset_in_region}).
                || Inputs: `target` (required hex string - the address you want to find references to; abs: prefix or base-relative), `region_address` (required - where to scan), `size` (required, max 16777216), `limit` (default 100).
                || Use cases: "Who points at NPC entity 0x... ?", "Find the manager struct that holds this Inventory pointer".
                || Related tools: scan_for_value (typed scan, more general), read_memory.
                || Pitfalls: Pointers are stored absolute in this process; the `target` is resolved (base-relative + base addr or abs:) before scanning. False positives are possible - a numeric coincidence could match. Scans 8-byte aligned slots only.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("target") { put("type", "string"); put("description", "Target pointer address") }
                    putJsonObject("region_address") { put("type", "string"); put("description", "Scan region start") }
                    putJsonObject("size") { put("type", "integer"); put("description", "Scan region size in bytes") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Max matches (default 100, max 10000)") }
                },
                required = listOf("target", "region_address", "size"),
            ),
        ) { request ->
            safeJsonCall("xref_pointer_to") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val rawTarget = args["target"]?.jsonPrimitive?.content ?: throw BadRequest("missing 'target'")
                val rawRegion = args["region_address"]?.jsonPrimitive?.content ?: throw BadRequest("missing 'region_address'")
                val size = args["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: throw BadRequest("missing/invalid 'size'")
                if (size <= 0 || size > 16_777_216L) throw BadRequest("size must be 1..16777216")
                val limit = (args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100).coerceIn(1, 10000)

                val target = MemoryTools.resolveAddress(rawTarget)
                val region = MemoryTools.resolveAddress(rawRegion)
                requireMappedAddress(region, size, "scan region")
                val seg = MemorySegment.ofAddress(region).reinterpret(size)

                val matches = mutableListOf<Long>()
                val end = size - 8
                var i = 0L
                while (i <= end && matches.size < limit) {
                    if (seg.get(ValueLayout.JAVA_LONG, i) == target) matches.add(region + i)
                    i += 8
                }

                putHex("target_addr", target)
                putHex("region_addr", region)
                put("size", size)
                put("matches", matches.size)
                put("truncated", matches.size == limit)
                put("items", buildJsonArray {
                    for (m in matches) {
                        add(buildJsonObject {
                            putHex("addr", m)
                            put("offset_in_region", m - region)
                        })
                    }
                })
            }
        }
    }

    private fun registerDumpStructAt(server: Server) {
        server.addTool(
            name = "dump_struct_at",
            description = """
                Purpose: Overlay a caller-supplied struct layout on an address and dump every field's value in one call. The agent reads the layout from `client-plugin-engine/src/main/kotlin/com/projectx/game/nxt/Offsets.kt` (no offset registry in the MCP) and passes the field list here as a JSON array.
                || Returns: JSON envelope with: addr, fields[] (each {name, offset_hex, type, value}). Errors per field are caught and reported inline.
                || Inputs: `address` (required hex), `fields` (required array of objects, each {name, offset, type}). `offset` accepts hex strings ("0x10") or decimal. `type` is byte|short|int|long|float|double|pointer|string.
                || Use cases: "Dump every field of OInterfaceComponent at 0x... - I'll pass the layout from Offsets.kt", "Read 10 fields of an NPC struct in one shot rather than 10 read_struct_field calls".
                || Related tools: read_memory, read_struct_field, validate_memory.
                || Pitfalls: This is intentionally NOT backed by a meta-registry - pass the layout each call so the source of truth stays in Offsets.kt. Per-field errors don't fail the whole call; they appear inline as `error` in the field entry.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("address") { put("type", "string"); put("description", "Struct base address") }
                    putJsonObject("fields") { put("type", "array"); put("description", "Array of {name, offset, type} objects") }
                },
                required = listOf("address", "fields"),
            ),
        ) { request ->
            safeJsonCall("dump_struct_at") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val rawAddr = args["address"]?.jsonPrimitive?.content ?: throw BadRequest("missing 'address'")
                val fieldsArr = args["fields"]?.jsonArray ?: throw BadRequest("missing 'fields' array")
                val addr = MemoryTools.resolveAddress(rawAddr)
                putHex("addr", addr)

                put("fields", buildJsonArray {
                    for ((idx, fieldEl) in fieldsArr.withIndex()) {
                        val fo = fieldEl as? JsonObject
                            ?: throw BadRequest("fields[$idx] must be an object")
                        val name = fo["name"]?.jsonPrimitive?.content ?: throw BadRequest("fields[$idx].name missing")
                        val offsetStr = fo["offset"]?.jsonPrimitive?.content ?: throw BadRequest("fields[$idx].offset missing")
                        val type = fo["type"]?.jsonPrimitive?.content ?: throw BadRequest("fields[$idx].type missing")
                        val offset = try { MemoryTools.parseNumber(offsetStr) } catch (t: Throwable) { throw BadRequest("fields[$idx].offset parse: ${t.message}") }
                        val fieldAddr = addr + offset
                        add(buildJsonObject {
                            put("name", name)
                            put("offset_hex", "0x" + offset.toString(16))
                            put("type", type)
                            runCatching {
                                MemoryTools.validateAddress(fieldAddr, MemoryTools.typeSize(type))
                                val seg = MemorySegment.ofAddress(fieldAddr).reinterpret(MemoryTools.typeSize(type) + 8)
                                put("value", MemoryTools.readTyped(seg, 0L, type))
                            }.onFailure { put("error", it.message ?: "read failed") }
                        })
                    }
                })
            }
        }
    }

    private fun registerCompareMemory(server: Server) {
        server.addTool(
            name = "compare_memory",
            description = """
                Purpose: Byte-by-byte diff of two equally-sized memory regions, returning the offsets that differ. Useful for snapshot diffs (capture before/after of a struct to see what changed).
                || Returns: JSON envelope with: addr_a, addr_b, size, diff_count, identical, items[] (each {offset, byte_a, byte_b} for the first N differences).
                || Inputs: `addr_a` (required hex), `addr_b` (required hex), `size` (required int, max 1048576 = 1MB), `limit` (optional int - max diffs reported, default 200, max 10000).
                || Use cases: "What bytes changed in this entity between ticks?", "Compare two NPC struct snapshots taken before and after an action".
                || Related tools: read_memory (capture a snapshot), dump_struct_at (interpret bytes as a known layout).
                || Pitfalls: Both regions must be currently mapped. The tool returns up to `limit` diff sites; if there are more, truncated=true. For struct comparison, prefer comparing field values via dump_struct_at twice for cleaner output.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("addr_a") { put("type", "string"); put("description", "First region start") }
                    putJsonObject("addr_b") { put("type", "string"); put("description", "Second region start") }
                    putJsonObject("size") { put("type", "integer"); put("description", "Region size (max 1048576)") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Max diffs reported (default 200, max 10000)") }
                },
                required = listOf("addr_a", "addr_b", "size"),
            ),
        ) { request ->
            safeJsonCall("compare_memory") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val rawA = args["addr_a"]?.jsonPrimitive?.content ?: throw BadRequest("missing 'addr_a'")
                val rawB = args["addr_b"]?.jsonPrimitive?.content ?: throw BadRequest("missing 'addr_b'")
                val size = args["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: throw BadRequest("missing/invalid 'size'")
                if (size <= 0 || size > 1_048_576L) throw BadRequest("size must be 1..1048576")
                val limit = (args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 200).coerceIn(1, 10000)

                val a = MemoryTools.resolveAddress(rawA)
                val b = MemoryTools.resolveAddress(rawB)
                requireMappedAddress(a, size, "region A")
                requireMappedAddress(b, size, "region B")
                val segA = MemorySegment.ofAddress(a).reinterpret(size)
                val segB = MemorySegment.ofAddress(b).reinterpret(size)

                val diffs = mutableListOf<Triple<Long, Byte, Byte>>()
                var count = 0L
                var i = 0L
                while (i < size) {
                    val ba = segA.get(ValueLayout.JAVA_BYTE, i)
                    val bb = segB.get(ValueLayout.JAVA_BYTE, i)
                    if (ba != bb) {
                        count++
                        if (diffs.size < limit) diffs.add(Triple(i, ba, bb))
                    }
                    i++
                }

                putHex("addr_a", a)
                putHex("addr_b", b)
                put("size", size)
                put("diff_count", count)
                put("identical", count == 0L)
                put("truncated", count > diffs.size)
                put("items", buildJsonArray {
                    for ((off, ba, bb) in diffs) {
                        add(buildJsonObject {
                            put("offset", off)
                            put("byte_a", "0x" + (ba.toInt() and 0xFF).toString(16).padStart(2, '0'))
                            put("byte_b", "0x" + (bb.toInt() and 0xFF).toString(16).padStart(2, '0'))
                        })
                    }
                })
            }
        }
    }
}
