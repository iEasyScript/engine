package org.projectx.tools.cacheunpack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.IdentityHashMap

/**
 * Renders a decoded definition as a deterministic JSON object keyed by the decoder's own field
 * names. Field order, map order and number formatting are all fixed so that re-running over an
 * unchanged cache is byte-identical and a diff only ever shows real content change.
 *
 * Bulk pixel and glyph payloads are summarised as a length plus digest: keeping them verbatim would
 * dwarf every other type, while a digest still changes exactly when the payload does.
 */
class DefinitionJson(private val arrayLimit: Int) {

    private val fieldCache = HashMap<Class<*>, List<Field>>()
    private val digest = MessageDigest.getInstance("SHA-256")

    fun body(definition: Any): String = COMPACT.encodeToString(JsonObject.serializer(), fields(definition, 0, IdentityHashMap()))

    fun line(id: Int, body: String): String = "{\"id\":$id" + if (body == "{}") "}" else ",${body.substring(1)}"

    private fun fields(value: Any, depth: Int, seen: IdentityHashMap<Any, Any>): JsonObject {
        val skipId = depth == 0
        return buildJsonObject {
            for (field in declaredFields(value.javaClass)) {
                if (skipId && field.name == "id") continue
                put(field.name, encode(field.get(value), depth + 1, seen))
            }
        }
    }

    private fun encode(value: Any?, depth: Int, seen: IdentityHashMap<Any, Any>): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Byte -> JsonPrimitive(value.toInt())
        is Short -> JsonPrimitive(value.toInt())
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> real(value.toDouble())
        is Double -> real(value)
        is Char -> JsonPrimitive(value.toString())
        is String -> JsonPrimitive(value)
        is Enum<*> -> JsonPrimitive(value.name)
        is ByteArray -> bulk(value.size, value) { JsonArray(value.map { JsonPrimitive(it.toInt()) }) }
        is BooleanArray -> bulk(value.size, { ByteArray(value.size) { if (value[it]) 1 else 0 } }) {
            JsonArray(value.map { JsonPrimitive(it) })
        }
        is ShortArray -> bulk(value.size, { shortBytes(value) }) { JsonArray(value.map { JsonPrimitive(it.toInt()) }) }
        is CharArray -> bulk(value.size, { shortBytes(ShortArray(value.size) { value[it].code.toShort() }) }) {
            JsonArray(value.map { JsonPrimitive(it.toString()) })
        }
        is IntArray -> bulk(value.size, { intBytes(value) }) { JsonArray(value.map { JsonPrimitive(it) }) }
        is LongArray -> bulk(value.size, { longBytes(value) }) { JsonArray(value.map { JsonPrimitive(it) }) }
        is FloatArray -> bulk(value.size, { intBytes(IntArray(value.size) { value[it].toRawBits() }) }) {
            JsonArray(value.map { real(it.toDouble()) })
        }
        is DoubleArray -> bulk(value.size, { longBytes(LongArray(value.size) { value[it].toRawBits() }) }) {
            JsonArray(value.map { real(it) })
        }
        is Array<*> -> guard(value, depth, seen) { JsonArray(value.map { encode(it, depth + 1, seen) }) }
        is Map<*, *> -> guard(value, depth, seen) { map(value, depth, seen) }
        is Iterable<*> -> guard(value, depth, seen) { JsonArray(value.map { encode(it, depth + 1, seen) }) }
        else -> opaque(value, depth, seen)
    }

    private fun opaque(value: Any, depth: Int, seen: IdentityHashMap<Any, Any>): JsonElement {
        if (!value.javaClass.name.startsWith(CACHE_PACKAGE)) return JsonPrimitive(value.toString())
        return guard(value, depth, seen) { fields(value, depth, seen) }
    }

    private inline fun guard(
        value: Any,
        depth: Int,
        seen: IdentityHashMap<Any, Any>,
        build: () -> JsonElement,
    ): JsonElement {
        if (depth > MAX_DEPTH) return JsonPrimitive(DEPTH_LIMIT)
        if (seen.put(value, value) != null) return JsonPrimitive(CYCLE)
        try {
            return build()
        } finally {
            seen.remove(value)
        }
    }

    private fun map(value: Map<*, *>, depth: Int, seen: IdentityHashMap<Any, Any>): JsonObject {
        val entries = if (value.keys.all { it is Int }) {
            value.entries.sortedBy { it.key as Int }
        } else {
            value.entries.sortedBy { it.key.toString() }
        }
        return buildJsonObject {
            for ((key, entry) in entries) put(key.toString(), encode(entry, depth + 1, seen))
        }
    }

    private inline fun bulk(size: Int, raw: () -> ByteArray, verbatim: () -> JsonElement): JsonElement =
        if (size > arrayLimit) summary(size, raw()) else verbatim()

    private inline fun bulk(size: Int, raw: ByteArray, verbatim: () -> JsonElement): JsonElement =
        if (size > arrayLimit) summary(size, raw) else verbatim()

    private fun summary(size: Int, raw: ByteArray): JsonObject = buildJsonObject {
        put("length", size)
        put("sha256", digest.digest(raw).take(DIGEST_BYTES).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        })
    }

    private fun real(value: Double): JsonPrimitive =
        if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())

    private fun declaredFields(type: Class<*>): List<Field> = fieldCache.getOrPut(type) {
        val found = ArrayList<Field>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) continue
                field.isAccessible = true
                found.add(field)
            }
            current = current.superclass
        }
        found.distinctBy { it.name }.sortedBy { it.name }
    }

    private fun shortBytes(value: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(value.size * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (element in value) buffer.putShort(element)
        return buffer.array()
    }

    private fun intBytes(value: IntArray): ByteArray {
        val buffer = ByteBuffer.allocate(value.size * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (element in value) buffer.putInt(element)
        return buffer.array()
    }

    private fun longBytes(value: LongArray): ByteArray {
        val buffer = ByteBuffer.allocate(value.size * Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (element in value) buffer.putLong(element)
        return buffer.array()
    }

    companion object {
        private const val CYCLE = "<cycle>"
        private const val DEPTH_LIMIT = "<depth-limit>"
        private const val CACHE_PACKAGE = "world.gregs.voidps"
        private const val MAX_DEPTH = 12
        private const val DIGEST_BYTES = 16
        private val COMPACT = Json { prettyPrint = false; encodeDefaults = true }
    }
}
