package com.projectx.mcp.tools

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.memory.NativeAccess
import com.projectx.game.nxt.Client
import com.projectx.game.nxt.MainState
import com.projectx.mcp.MainLogicTickQueue
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class BadRequest(message: String) : RuntimeException(message)
class EngineState(message: String, val mainStateName: String? = null) : RuntimeException(message)

@OptIn(ExperimentalSerializationApi::class)
internal val JSON_PRETTY = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = false
}

fun <T> onGameTick(timeoutMs: Long = 5_000L, block: () -> T): T {
    if (MainLogicTickQueue.isOnGameThread()) return block()
    val fut = CompletableFuture<T>()
    val accepted = MainLogicTickQueue.enqueue {
        try {
            fut.complete(block())
        } catch (t: Throwable) {
            fut.completeExceptionally(t)
        }
    }
    if (!accepted) {
        throw EngineState("tick_queue_full (capacity ${MainLogicTickQueue.CAPACITY}, pending ${MainLogicTickQueue.pendingCount()})")
    }
    return try {
        fut.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } catch (e: TimeoutException) {
        throw EngineState("tick_timeout after ${timeoutMs}ms - game tick may be hung")
    }
}

fun fmtAddr(addr: Long): JsonElement =
    if (addr == 0L) JsonNull else JsonPrimitive("0x" + addr.toULong().toString(16))

fun fmtAddrRel(addr: Long): JsonElement {
    if (addr == 0L) return JsonNull
    val base = NativeAccess.BASE_ADDR.address()
    val rel = addr - base
    return JsonPrimitive("0x" + rel.toULong().toString(16))
}

fun JsonObjectBuilder.putAddr(addr: Long) {
    put("addr", fmtAddr(addr))
    put("addr_rel", fmtAddrRel(addr))
}

fun JsonObjectBuilder.putPtrField(key: String, addr: Long) {
    put("${key}_addr", fmtAddr(addr))
}

fun JsonObjectBuilder.putHex(key: String, value: Long) {
    put(key, "0x" + value.toULong().toString(16))
}

fun jsonResult(obj: JsonObject): CallToolResult =
    CallToolResult(content = listOf(TextContent(text = JSON_PRETTY.encodeToString(JsonObject.serializer(), obj))))

fun mainStateName(state: Int): String = when (state) {
    MainState.INITIALIZING -> "INITIALIZING"
    MainState.LOGIN_SCREEN -> "LOGIN_SCREEN"
    MainState.LOBBY_SCREEN -> "LOBBY_SCREEN"
    MainState.ACCOUNT_CREATION -> "ACCOUNT_CREATION"
    MainState.LOGGED_IN -> "LOGGED_IN"
    MainState.ATTEMPTING_TO_REESTABLISH_NOTIFICATION -> "ATTEMPTING_TO_REESTABLISH"
    MainState.RECONNECTING_TO_SERVER -> "RECONNECTING"
    MainState.LOADING_NOTIFICATION -> "LOADING_NOTIFICATION"
    else -> "UNKNOWN($state)"
}

private fun safeMainStateName(): String? = try {
    mainStateName(Bootstrap.client.mainState)
} catch (_: Throwable) {
    null
}

class WarningCollector {
    private val items = mutableListOf<String>()
    fun add(msg: String) {
        items.add(msg)
    }

    fun emit(builder: JsonObjectBuilder) {
        if (items.isNotEmpty()) {
            builder.put("warnings", JsonArray(items.map { JsonPrimitive(it) }))
        }
    }
}

fun safeJsonCall(toolName: String, block: JsonObjectBuilder.(WarningCollector) -> Unit): CallToolResult {
    return try {
        onGameTick {
            val warnings = WarningCollector()
            val obj = buildJsonObject {
                put("tool", toolName)
                runCatching {
                    val baseAddr = NativeAccess.BASE_ADDR.address()
                    put("base_addr", "0x" + baseAddr.toULong().toString(16))
                }
                runCatching {
                    put("client_cycle", Bootstrap.client.clientCycle)
                    put("main_state", mainStateName(Bootstrap.client.mainState))
                }
                block(warnings)
                warnings.emit(this)
            }
            jsonResult(obj)
        }
    } catch (e: BadRequest) {
        errorResult(toolName, "bad_request", e.message)
    } catch (e: EngineState) {
        errorResult(toolName, "engine_state", e.message, mainState = e.mainStateName ?: safeMainStateName())
    } catch (e: Throwable) {
        errorResult(toolName, "internal", "${e.javaClass.simpleName}: ${e.message}", stack = e.stackTraceToString())
    }
}

/**
 * Variant of safeJsonCall that does NOT auto-marshal to the game tick. The block runs on the
 * caller (SSE) thread and is expected to call onGameTick { ... } itself for any segments that
 * need to read/write engine state atomically. Used for "wait_for_X" tools that poll across
 * many ticks - those must release the tick between polls so the game can run.
 */
fun safeJsonCallAsync(toolName: String, block: JsonObjectBuilder.(WarningCollector) -> Unit): CallToolResult {
    return try {
        val warnings = WarningCollector()
        val obj = buildJsonObject {
            put("tool", toolName)
            block(warnings)
            warnings.emit(this)
        }
        jsonResult(obj)
    } catch (e: BadRequest) {
        errorResult(toolName, "bad_request", e.message)
    } catch (e: EngineState) {
        errorResult(toolName, "engine_state", e.message, mainState = e.mainStateName ?: safeMainStateName())
    } catch (e: Throwable) {
        errorResult(toolName, "internal", "${e.javaClass.simpleName}: ${e.message}", stack = e.stackTraceToString())
    }
}

private fun errorResult(
    tool: String,
    code: String,
    message: String?,
    mainState: String? = null,
    stack: String? = null,
): CallToolResult {
    val obj = buildJsonObject {
        put("tool", tool)
        put("error", code)
        put("message", message ?: "(no message)")
        if (mainState != null) put("main_state", mainState)
        if (stack != null) put("stack", stack)
    }
    return CallToolResult(
        content = listOf(TextContent(text = JSON_PRETTY.encodeToString(JsonObject.serializer(), obj))),
        isError = true,
    )
}

fun requireLoggedIn(): Client {
    val c = Bootstrap.client
    if (c.mainState != MainState.LOGGED_IN) {
        throw EngineState("not logged in (main_state=${mainStateName(c.mainState)})", mainStateName(c.mainState))
    }
    return c
}

fun requireMappedAddress(addr: Long, size: Long, what: String) {
    if (addr == 0L) throw EngineState("NULL pointer for $what")
    try {
        MemoryTools.validateAddress(addr, size)
    } catch (t: Throwable) {
        throw EngineState("$what at 0x${addr.toULong().toString(16)} is unmapped: ${t.message}")
    }
}

data class ListFilters(
    val id: Int? = null,
    val name: String? = null,
    val kind: String? = null,
    val withinTiles: Int? = null,
    val withinZone: Boolean = false,
    val plane: Int? = null,
    val limit: Int = 50,
    val offset: Int = 0,
    val fields: Set<String>? = null,
    val verbose: Boolean = false,
    val sort: String = "addr",
) {
    fun matchesName(target: String?): Boolean {
        if (name == null) return true
        if (target == null) return false
        return target.contains(name, ignoreCase = true)
    }
}

fun parseListFilters(args: JsonObject?): ListFilters {
    if (args == null) return ListFilters()
    return ListFilters(
        id = args["id"]?.jsonPrimitive?.content?.toIntOrNull(),
        name = args["name"]?.jsonPrimitive?.content,
        kind = args["kind"]?.jsonPrimitive?.content,
        withinTiles = args["within_tiles"]?.jsonPrimitive?.content?.toIntOrNull(),
        withinZone = args["within_zone"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
        plane = args["plane"]?.jsonPrimitive?.content?.toIntOrNull(),
        limit = (args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 50).coerceIn(1, 500),
        offset = (args["offset"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceAtLeast(0),
        fields = args["fields"]?.let {
            (it as? JsonArray)?.mapNotNull { e -> (e as? JsonPrimitive)?.contentOrNull }?.toSet()
        },
        verbose = args["verbose"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
        sort = args["sort"]?.jsonPrimitive?.content ?: "addr",
    )
}

private val JsonPrimitive.contentOrNull: String?
    get() = if (this is JsonNull) null else this.content

fun listSchema(extra: JsonObjectBuilder.() -> Unit = {}): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        putJsonObject("id") {
            put("type", "integer")
            put("description", "Exact content id filter (e.g. NPCType.id). Omit for no id filter.")
        }
        putJsonObject("name") {
            put("type", "string")
            put("description", "Case-insensitive substring on the resolved name. Omit for no name filter.")
        }
        putJsonObject("kind") {
            put("type", "string")
            put("description", "Tool-specific subtype filter. See per-tool description for accepted values.")
        }
        putJsonObject("within_tiles") {
            put("type", "integer")
            put("description", "Radius (tiles) from the logged-in player's tile. Omit for no spatial filter.")
        }
        putJsonObject("within_zone") {
            put("type", "boolean")
            put("description", "If true, restrict to the player's 8x8 zone. Overrides within_tiles.")
        }
        putJsonObject("plane") {
            put("type", "integer")
            put("description", "Restrict to plane 0..3.")
        }
        putJsonObject("limit") {
            put("type", "integer")
            put("description", "Max items returned. Default 50, hard max 500.")
        }
        putJsonObject("offset") {
            put("type", "integer")
            put("description", "Pagination offset. Default 0.")
        }
        putJsonObject("fields") {
            put("type", "array")
            put("description", "Whitelist of field names to include in each item. Null = tool default set.")
        }
        putJsonObject("verbose") {
            put("type", "boolean")
            put("description", "If true, dump all known fields per item. Overrides 'fields'.")
        }
        putJsonObject("sort") {
            put("type", "string")
            put("description", "Sort order: addr | distance | id | name. Default addr.")
        }
        extra()
    },
)
