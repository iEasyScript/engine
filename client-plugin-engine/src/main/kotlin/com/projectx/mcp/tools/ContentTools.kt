package com.projectx.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import world.gregs.voidps.cache.Cache
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible

object ContentTools {

    fun register(server: Server): Int {
        registerGetContentType(server)
        registerListContentType(server)
        return 2
    }

    private fun reflectToJson(obj: Any, verbose: Boolean): JsonObject = buildJsonObject {
        val cls = obj::class
        for (prop in cls.declaredMemberProperties) {
            if (prop.visibility != KVisibility.PUBLIC) continue
            runCatching {
                prop.isAccessible = true
                val value = prop.getter.call(obj)
                val element = valueToJson(value, verbose)
                if (!verbose && isDefaultLike(element)) return@runCatching
                put(prop.name, element)
            }
        }
    }

    private fun isDefaultLike(e: JsonElement): Boolean = when (e) {
        is JsonNull -> true
        is JsonPrimitive -> e.content == "0" || e.content == "-1" || e.content == "0.0" || e.content == "false" || e.content == ""
        is JsonArray -> e.isEmpty()
        is JsonObject -> e.isEmpty()
    }

    private fun valueToJson(v: Any?, verbose: Boolean): JsonElement = when (v) {
        null -> JsonNull
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is ByteArray -> JsonPrimitive("ByteArray(${v.size})")
        is IntArray -> buildJsonArray { for (i in v) add(JsonPrimitive(i)) }
        is ShortArray -> buildJsonArray { for (i in v) add(JsonPrimitive(i.toInt())) }
        is LongArray -> buildJsonArray { for (i in v) add(JsonPrimitive(i)) }
        is FloatArray -> buildJsonArray { for (i in v) add(JsonPrimitive(i)) }
        is DoubleArray -> buildJsonArray { for (i in v) add(JsonPrimitive(i)) }
        is BooleanArray -> buildJsonArray { for (i in v) add(JsonPrimitive(i)) }
        is Array<*> -> buildJsonArray { for (e in v) add(valueToJson(e, verbose)) }
        is List<*> -> buildJsonArray { for (e in v) add(valueToJson(e, verbose)) }
        is Set<*> -> buildJsonArray { for (e in v) add(valueToJson(e, verbose)) }
        is Map<*, *> -> buildJsonObject {
            for ((k, vv) in v) {
                if (k == null) continue
                put(k.toString(), valueToJson(vv, verbose))
            }
        }
        is Enum<*> -> JsonPrimitive(v.name)
        else -> {
            runCatching { reflectToJson(v, verbose) }
                .getOrElse { JsonPrimitive("(unserializable ${v.javaClass.simpleName})") }
        }
    }

    private fun lookupKotlinType(kind: String, id: Int): Any? = try {
        when (kind.lowercase()) {
            "npc" -> Cache.npc(id)
            "item" -> Cache.obj(id)
            "obj", "loc" -> Cache.loc(id)
            "enum" -> Cache.enum(id)
            "struct" -> Cache.struct(id)
            "param" -> Cache.param(id)
            "varbit" -> Cache.varbit(id)
            "seq" -> Cache.seq(id)
            "bas" -> Cache.bas(id)
            "inv" -> Cache.inv(id)
            "quest" -> Cache.quest(id)
            "varp" -> Cache.varPlayer(id)
            "varc" -> Cache.varc(id)
            "varnpc" -> Cache.varNpc(id)
            "varclan" -> Cache.varClan(id)
            "varclansetting" -> Cache.varClanSetting(id)
            "vargroup" -> Cache.varGroup(id)
            "achievement" -> Cache.achievement(id)
            "dbrow" -> Cache.dbRow(id)
            "dbtable" -> Cache.dbTable(id)
            "dbtableindex" -> Cache.dbTableIndex(id)
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    private val CONTENT_KINDS = setOf(
        "npc", "item", "obj", "loc", "enum", "struct", "param", "varbit", "varp", "varc",
        "varnpc", "varclan", "varclansetting", "vargroup", "seq", "bas", "inv", "quest",
        "achievement", "dbrow", "dbtable", "dbtableindex",
    )

    private fun registerGetContentType(server: Server) {
        server.addTool(
            name = "get_content_type",
            description = """
                Purpose: Unified lookup of any cache-defined content type, decoded straight from the cache by the :core decoders - NPC, item (obj-type), location/object (loc-type), enum, struct, param, every var domain (varbit, varp, varc, varnpc, varclan, varclansetting, vargroup), seq, bas, inv, quest, achievement, dbrow, dbtable, dbtableindex.
                || Returns: JSON envelope with: kind, id, fields (every decoded field, reflected off the type; default/empty fields trimmed unless verbose).
                || Inputs: `kind` (required string, one of npc|item|obj|loc|enum|struct|param|varbit|varp|varc|varnpc|varclan|varclansetting|vargroup|seq|bas|inv|quest|achievement|dbrow|dbtable|dbtableindex). `id` (required int). `verbose` (optional bool, default false) - include default/empty fields.
                || Use cases: "What does NPC type 0 look like (Hans)?", "List the option names on object id 1816 (a door)?", "Decode a varbit's base+bits", "Look up a struct's params by id".
                || Related tools: list_content_type (range scan), get_varbit (live decoded value).
                || Pitfalls: An id the cache does not define reports `engine_state`. The cache returns "default" types (id=0 fields) for unknown ids on some kinds - check `id` in the response.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("kind") {
                        put("type", "string")
                        put("description", "Content kind (npc, item, obj|loc, enum, struct, param, varbit, seq, bas, inv, quest, varp, dbrow, achievement)")
                    }
                    putJsonObject("id") {
                        put("type", "integer")
                        put("description", "Content id")
                    }
                    putJsonObject("verbose") {
                        put("type", "boolean")
                        put("description", "If true, include default/empty fields")
                    }
                },
                required = listOf("kind", "id"),
            ),
        ) { request ->
            safeJsonCall("get_content_type") { warnings ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val kind = args["kind"]?.jsonPrimitive?.content?.lowercase()
                    ?: throw BadRequest("missing 'kind'")
                val id = args["id"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: throw BadRequest("missing/invalid 'id'")
                val verbose = args["verbose"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

                put("kind", kind)
                put("id", id)

                if (kind !in CONTENT_KINDS) throw BadRequest("unknown kind '$kind'")
                val obj = lookupKotlinType(kind, id) ?: throw EngineState("cache has no $kind $id")
                put("fields", reflectToJson(obj, verbose))
            }
        }
    }

    private fun registerListContentType(server: Server) {
        server.addTool(
            name = "list_content_type",
            description = """
                Purpose: Scan a content kind by id range or substring filter, iterating the decoder's max-id range and running each id through the lookup.
                || Returns: JSON envelope with kind, count, total, truncated, items[]. Each item: id + a compact summary (name when available, top-level fields trimmed). Use get_content_type for full detail.
                || Inputs: `kind` (required string). Standard list filters: `id` (exact filter - usually pointless here, but supported), `name` (substring on the resolved name when present), `limit` (default 50, hard max 500), `offset`, `verbose` (every field instead of a summary).
                || Use cases: "Find every NPC with 'goblin' in the name", "List items 1000..1100", "Scan all varbits in domain PLAYER".
                || Related tools: get_content_type (full detail), find_cs2_callers (Phase 4 - who reads this content id from CS2).
                || Pitfalls: Scanning calls `get(id)` for every id up to maxId; that's slow for large content sets. The first call after engine start cold-loads the decoder cache. Kinds without an eager whole-array decoder (param, inv, quest, dbrow, dbtable) report no max_id and cannot be scanned - use get_content_type.
            """.trimIndent().replace("\n", " "),
            inputSchema = listSchema {
                putJsonObject("kind") {
                    put("type", "string")
                    put("description", "Content kind")
                }
            },
        ) { request ->
            safeJsonCall("list_content_type") { warnings ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val kind = args["kind"]?.jsonPrimitive?.content?.lowercase()
                    ?: throw BadRequest("missing 'kind'")
                val filters = parseListFilters(args)

                put("kind", kind)

                if (kind in CONTENT_KINDS) {
                    val maxId = parserMaxId(kind)
                    if (maxId < 0) throw EngineState("could not determine maxId for kind=$kind")
                    put("max_id", maxId)

                    val rows = mutableListOf<Pair<Int, Any>>()
                    val startId = filters.offset
                    val limit = filters.limit
                    var scanned = 0
                    for (i in startId..maxId) {
                        if (rows.size >= limit) break
                        runCatching {
                            val obj = lookupKotlinType(kind, i) ?: return@runCatching
                            scanned++
                            if (filters.id != null && i != filters.id) return@runCatching
                            if (filters.name != null) {
                                val name = (obj::class.declaredMemberProperties.firstOrNull { it.name == "name" }
                                    ?.also { it.isAccessible = true }
                                    ?.getter?.call(obj) as? String)
                                if (name == null || !name.contains(filters.name, ignoreCase = true)) return@runCatching
                            }
                            rows.add(i to obj)
                        }
                    }
                    put("count", rows.size)
                    put("scanned", scanned)
                    put("truncated", rows.size == limit)
                    put("items", buildJsonArray {
                        for ((id, obj) in rows) {
                            add(buildJsonObject {
                                put("id", id)
                                if (filters.verbose) {
                                    put("fields", reflectToJson(obj, true))
                                } else {
                                    val name = (obj::class.declaredMemberProperties.firstOrNull { it.name == "name" }
                                        ?.also { it.isAccessible = true }
                                        ?.getter?.call(obj) as? String)
                                    if (name != null) put("name", name)
                                }
                            })
                        }
                    })
                } else {
                    throw BadRequest("unknown kind '$kind'")
                }
            }
        }
    }

    // param/inv/quest have no eager whole-array decoder in :core, so a full id scan
    // (and therefore list_content_type) isn't available for them; get_content_type
    // still resolves a single id via the per-id accessors.
    private fun parserMaxId(kind: String): Int = try {
        when (kind.lowercase()) {
            "npc" -> Cache.npcs.size - 1
            "item" -> Cache.objs.size - 1
            "obj", "loc" -> Cache.locs.size - 1
            "enum" -> Cache.enums.size - 1
            "struct" -> Cache.structs.size - 1
            "varbit" -> Cache.varbits.size - 1
            "seq" -> Cache.animations.size - 1
            "bas" -> Cache.bas.size - 1
            "param" -> Cache.params.size - 1
            "inv" -> Cache.invs.size - 1
            "quest" -> Cache.quests.size - 1
            "varp" -> Cache.varPlayers.size - 1
            "varc" -> Cache.varcs.size - 1
            "varnpc" -> Cache.varNpcs.size - 1
            "varclan" -> Cache.varClans.size - 1
            "varclansetting" -> Cache.varClanSettings.size - 1
            "vargroup" -> Cache.varGroups.size - 1
            "achievement" -> Cache.achievements.size - 1
            "dbtableindex" -> Cache.dbTableIndexes.size - 1
            else -> -1
        }
    } catch (_: Throwable) {
        -1
    }
}
