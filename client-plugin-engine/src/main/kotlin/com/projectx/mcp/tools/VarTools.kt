package com.projectx.mcp.tools

import com.projectx.game.memory.NativeAccess.readInt
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.VarBitType
import world.gregs.voidps.cache.type.data.VarBitType.Companion.BIT_MASKS
import world.gregs.voidps.cache.type.data.VarDomain
import world.gregs.voidps.gameval.Gameval
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object VarTools {

    fun register(server: Server): Int {
        registerGetVarp(server)
        registerGetVarbit(server)
        registerGetVarClient(server)
        registerListVarps(server)
        registerListVarbitsForVarp(server)
        return 5
    }

    private fun registerGetVarp(server: Server) {
        server.addTool(
            name = "get_varp",
            description = """
                Purpose: Read the value of a player varp (PlayerVarDomain) by id, including the heap address of the underlying hash-table node so the agent can drill into adjacent slots.
                || Returns: JSON envelope with: varp_id, value (int), node_addr (the EastlHashNode address in the hash table, nullable if the varp is unset), domain ("PLAYER").
                || Inputs: `id` (required int) - the varp id.
                || Use cases: "What's the value of varp 1004 (quest list)?", "Read a player state varp before/after a script step", "Drill into the raw bytes of the hash-table node for varp N".
                || Related tools: get_varbit (decode a varbit on top of a varp), list_varps (full varp dump), list_varbits_for_varp (every varbit reading this varp), get_var_client (client-domain sibling).
                || Pitfalls: Requires LOGGED_IN. Returns value=0 and node_addr=null when the varp has never been set on this character (the hash slot doesn't exist yet). The value is the FULL int - varbits within this varp must be decoded separately via get_varbit.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("id") {
                        put("type", "integer")
                        put("description", "Varp id")
                    }
                },
                required = listOf("id"),
            ),
        ) { request ->
            safeJsonCall("get_varp") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val id = args["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw BadRequest("missing/invalid 'id'")
                val client = requireLoggedIn()
                val node = client.playerVarDomain.hashTable[id]
                val long = Cache.varPlayer(id)?.long == true
                put("varp_id", id)
                put("varp_name", Gameval.varp(id) ?: "")
                put("domain", "PLAYER")
                put("long", long)
                if (node == null) {
                    put("value", 0)
                    putPtrField("node", 0L)
                } else {
                    put("value", if (long) client.playerVarDomain.getVarLong(id) else node.readInt().toLong())
                    putPtrField("node", node.address())
                }
            }
        }
    }

    private fun registerGetVarbit(server: Server) {
        server.addTool(
            name = "get_varbit",
            description = """
                Purpose: Decode a varbit by id - looks up its VarbitType (baseVar, startBit, endBit, domain), reads the underlying varp from the appropriate domain, and returns the masked/shifted value plus the raw varp value for debugging.
                || Returns: JSON envelope with: varbit_id, decoded_value, raw_varp_value, base_varp, start_bit, end_bit, domain, mask, node_addr (raw varp's hash-table node).
                || Inputs: `id` (required int) - the varbit id.
                || Use cases: "Is quest stage 9159 set?", "What's the prayer-points varbit (16736)?", "Decode a varbit and see both the boolean answer and the raw bit pattern of its parent varp".
                || Related tools: get_varp (raw underlying varp), list_varbits_for_varp (siblings on same varp), get_content_type kind=varbit id=N (varbit cache definition).
                || Pitfalls: Returns decoded_value=0 and raw_varp_value=0 when the underlying varp is unset. Currently only PLAYER and CLIENT var domains have engine-side readers - varbits in NPC/CLAN/WORLD/MAPSQUARE/OBJECT domains return 0 with a warning.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("id") {
                        put("type", "integer")
                        put("description", "Varbit id")
                    }
                },
                required = listOf("id"),
            ),
        ) { request ->
            safeJsonCall("get_varbit") { warnings ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val id = args["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw BadRequest("missing/invalid 'id'")
                val client = requireLoggedIn()
                val type = Cache.varbit(id) ?: throw BadRequest("unknown varbit id $id")
                val domain = type.domain
                put("varbit_id", id)
                put("varbit_name", Gameval.varbit(id) ?: "")
                put("base_varp", type.baseVar)
                put("start_bit", type.startBit)
                put("end_bit", type.endBit)
                put("domain", domain?.name ?: "UNKNOWN")
                val maskWidth = (type.endBit - type.startBit).coerceIn(0, 31)
                put("mask", BIT_MASKS[maskWidth])

                val rawValueAndNode: Pair<Int, Long> = when (domain) {
                    VarDomain.PLAYER -> {
                        val node = client.playerVarDomain.hashTable[type.baseVar]
                        (node?.readInt() ?: 0) to (node?.address() ?: 0L)
                    }
                    VarDomain.CLIENT -> {
                        val node = client.clientVarDomain.hashTable[type.baseVar]
                        (node?.readInt() ?: 0) to (node?.address() ?: 0L)
                    }
                    else -> {
                        warnings.add("domain $domain not directly readable from engine - only PLAYER and CLIENT are wired")
                        0 to 0L
                    }
                }
                put("raw_varp_value", rawValueAndNode.first)
                putPtrField("node", rawValueAndNode.second)
                put("decoded_value", rawValueAndNode.first shr type.startBit and BIT_MASKS[maskWidth])
            }
        }
    }

    private fun registerGetVarClient(server: Server) {
        server.addTool(
            name = "get_var_client",
            description = """
                Purpose: Read a client-domain var (ClientVarDomain) by id, returning the value and the hash-table node address. The ClientVarDomain stores client-side state separate from PlayerVarDomain (e.g., viewport coords at varc 3001/3002/3005/3006).
                || Returns: JSON envelope with: varc_id, value (int), node_addr, domain ("CLIENT").
                || Inputs: `id` (required int) - the varc id.
                || Use cases: "What are my viewport dimensions (varc 3001/3002)?", "Read a client-side UI flag", "Diff a client var across a frame".
                || Related tools: get_varp (player-domain sibling), get_varbit (decode bits on top of any var).
                || Pitfalls: Returns 0 / null node when unset. The varbit decode path for CLIENT domain varbits works via get_varbit when the cache type's domain matches.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("id") {
                        put("type", "integer")
                        put("description", "Varc id")
                    }
                },
                required = listOf("id"),
            ),
        ) { request ->
            safeJsonCall("get_var_client") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val id = args["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw BadRequest("missing/invalid 'id'")
                val client = requireLoggedIn()
                val node = client.clientVarDomain.hashTable[id]
                val long = Cache.varc(id)?.long == true
                put("varc_id", id)
                put("varc_name", Gameval.varc(id) ?: "")
                put("domain", "CLIENT")
                put("long", long)
                if (node == null) {
                    put("value", 0)
                    putPtrField("node", 0L)
                } else {
                    put("value", if (long) client.clientVarDomain.getVarLong(id) else node.readInt().toLong())
                    putPtrField("node", node.address())
                }
            }
        }
    }

    private fun registerListVarps(server: Server) {
        server.addTool(
            name = "list_varps",
            description = """
                Purpose: Dump every varp the player currently has set in PlayerVarDomain by walking the EastlHashTable values array. Each row reports the varp id, value, and node heap address.
                || Returns: JSON envelope with element_count, bucket_count, count, items[]. Each item: varp_id (hash code = id), value, node_addr.
                || Inputs: Standard list filters. `id` matches an exact varp id. `limit` defaults 50. Use offset to page through. To dump all, raise limit (max 500).
                || Use cases: "Show me every varp set on this character", "Find any varps in range 1000..2000 that are nonzero", "Take a snapshot of all varps so I can diff after running a script".
                || Related tools: get_varp (single id), list_varbits_for_varp (decode varbits on top), get_content_type kind=varp (cache definition).
                || Pitfalls: Requires LOGGED_IN. Unset varps don't appear in the hash table. The values dump is in hash-table iteration order, not by id - pass sort=id to sort numerically.
            """.trimIndent().replace("\n", " "),
            inputSchema = listSchema(),
        ) { request ->
            safeJsonCall("list_varps") { _ ->
                val filters = parseListFilters(request.arguments)
                val client = requireLoggedIn()
                val table = client.playerVarDomain.hashTable
                put("element_count", table.elementCount)
                put("bucket_count", table.bucketCount)

                data class Row(val id: Int, val value: Int, val nodeAddr: Long)

                val matched = mutableListOf<Row>()
                val count = table.elementCount.toInt().coerceAtMost(50000)
                for (i in 0 until count) {
                    runCatching {
                        val node = table.getNodeAtValuesIndex(i)
                        val id = node.hashCode
                        if (filters.id != null && id != filters.id) return@runCatching
                        val v = node.value.readInt()
                        matched.add(Row(id, v, node.value.address()))
                    }
                }

                val sorted = when (filters.sort) {
                    "id" -> matched.sortedBy { it.id }
                    "addr" -> matched.sortedBy { it.nodeAddr }
                    else -> matched
                }
                val window = sorted.drop(filters.offset).take(filters.limit)

                put("count", window.size)
                put("total", matched.size)
                put("truncated", sorted.size > window.size + filters.offset)
                put("items", buildJsonArray {
                    for (r in window) {
                        add(buildJsonObject {
                            put("varp_id", r.id)
                            put("varp_name", Gameval.varp(r.id) ?: "")
                            put("value", r.value)
                            putPtrField("node", r.nodeAddr)
                        })
                    }
                })
            }
        }
    }

    private fun registerListVarbitsForVarp(server: Server) {
        server.addTool(
            name = "list_varbits_for_varp",
            description = """
                Purpose: Given a varp id, list every varbit in the cache that reads from it (baseVar == id), with each varbit's bit range and current decoded value. Uses VarbitType.baseVarMap (populated at Bootstrap via loadBaseVarMap).
                || Returns: JSON envelope with: base_varp, raw_varp_value, count, items[]. Each item: varbit_id, start_bit, end_bit, mask, decoded_value, domain.
                || Inputs: `base_varp` (required int), `domain` (optional string, default "PLAYER"; one of PLAYER|NPC|CLIENT|WORLD|MAPSQUARE|OBJECT|CLAN|CLAN_SETTING).
                || Use cases: "I know varp 4 has bits - what varbits read from it?", "Decode all quest progress bits packed into varp 1004", "After setting varp X, see every varbit that changed".
                || Related tools: get_varp (raw value), get_varbit (single decoded), get_content_type kind=varbit (cache def).
                || Pitfalls: Requires LOGGED_IN for reading the raw varp value. baseVarMap is built at engine start; if it failed to load, this returns empty. Domains other than PLAYER and CLIENT have varbit definitions but no live read backing - decoded_value will be 0 for those.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("base_varp") {
                        put("type", "integer")
                        put("description", "Base varp id")
                    }
                    putJsonObject("domain") {
                        put("type", "string")
                        put("description", "VarDomain name (default PLAYER)")
                    }
                },
                required = listOf("base_varp"),
            ),
        ) { request ->
            safeJsonCall("list_varbits_for_varp") { warnings ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val baseVarp = args["base_varp"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: throw BadRequest("missing/invalid 'base_varp'")
                val domainName = args["domain"]?.jsonPrimitive?.content ?: "PLAYER"
                val domain = try {
                    VarDomain.valueOf(domainName.uppercase())
                } catch (t: Throwable) {
                    throw BadRequest("unknown domain '$domainName'. Valid: ${VarDomain.entries.joinToString { it.name }}")
                }
                val client = requireLoggedIn()

                val rawValue: Int = when (domain) {
                    VarDomain.PLAYER -> client.playerVarDomain.getVar(baseVarp)
                    VarDomain.CLIENT -> client.clientVarDomain.getVar(baseVarp)
                    else -> {
                        warnings.add("domain $domain not readable from engine - decoded values will be 0")
                        0
                    }
                }
                put("base_varp", baseVarp)
                put("domain", domain.name)
                put("raw_varp_value", rawValue)

                val varbits = VarBitType.baseVarMap[domain]?.get(baseVarp) ?: emptySet()
                put("count", varbits.size)
                put("items", buildJsonArray {
                    for (vb in varbits.sortedBy { it.id }) {
                        add(buildJsonObject {
                            put("varbit_id", vb.id)
                            put("varbit_name", Gameval.varbit(vb.id) ?: "")
                            put("start_bit", vb.startBit)
                            put("end_bit", vb.endBit)
                            val maskWidth = (vb.endBit - vb.startBit).coerceIn(0, 31)
                            put("mask", BIT_MASKS[maskWidth])
                            put("decoded_value", rawValue shr vb.startBit and BIT_MASKS[maskWidth])
                            put("domain", vb.domain?.name ?: "UNKNOWN")
                        })
                    }
                })
            }
        }
    }
}
