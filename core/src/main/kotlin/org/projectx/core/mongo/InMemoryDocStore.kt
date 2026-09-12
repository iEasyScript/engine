package org.projectx.core.mongo

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Crazy-basic in-process [DocStore] used when `MONGO_IN_MEMORY=true` — no external mongod, no
 * embedded-mongod download, just heap. Documents live in plain lists; equality filters read a
 * field by serializing the document to JSON and looking the field up by name (every field the
 * stores filter on is a top-level string, so this stays trivial).
 *
 * Per-process: only the lobby touches the account/clan stores, and the lobby→world login handoff
 * goes over the social gateway rather than a shared DB, so a single-JVM store is sufficient. Not a
 * real database — no persistence, no concurrent durability guarantees beyond coarse locking.
 */
class InMemoryDocStore : DocStore {
    private val collections = ConcurrentHashMap<String, InMemoryDocCollection<*>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> collection(name: String, type: KClass<T>, serializer: KSerializer<T>): DocCollection<T> =
        collections.getOrPut(name) { InMemoryDocCollection(serializer) } as DocCollection<T>
}

private class InMemoryDocCollection<T : Any>(private val serializer: KSerializer<T>) : DocCollection<T> {
    private val json = Json { encodeDefaults = true }
    private val docs = ArrayList<JsonObject>()
    private val lock = Any()

    private fun encode(doc: T): JsonObject = json.encodeToJsonElement(serializer, doc) as JsonObject
    private fun decode(doc: JsonObject): T = json.decodeFromJsonElement(serializer, doc)
    private fun fieldOf(doc: JsonObject, field: String): String? = doc[field]?.jsonPrimitive?.contentOrNull

    override suspend fun ensureIndex(field: String, unique: Boolean, sparse: Boolean) = Unit

    override suspend fun findOne(field: String, value: Any?): T? = synchronized(lock) {
        docs.firstOrNull { fieldOf(it, field) == value?.toString() }?.let { decode(it) }
    }

    override suspend fun findAnyOf(vararg conditions: Pair<String, Any?>): List<T> = synchronized(lock) {
        docs.filter { doc -> conditions.any { fieldOf(doc, it.first) == it.second?.toString() } }.map { decode(it) }
    }

    override suspend fun findIn(field: String, values: Collection<Any?>): List<T> = synchronized(lock) {
        val wanted = values.mapTo(HashSet()) { it?.toString() }
        docs.filter { fieldOf(it, field) in wanted }.map { decode(it) }
    }

    override suspend fun count(field: String, value: Any?): Long = synchronized(lock) {
        if (docs.any { fieldOf(it, field) == value?.toString() }) 1L else 0L
    }

    override suspend fun insert(doc: T): Unit = synchronized(lock) {
        docs.add(encode(doc))
    }

    override suspend fun upsert(field: String, value: Any?, doc: T): Unit = synchronized(lock) {
        val target = value?.toString()
        val encoded = encode(doc)
        val idx = docs.indexOfFirst { fieldOf(it, field) == target }
        if (idx >= 0) docs[idx] = encoded else docs.add(encoded)
    }

    override suspend fun delete(field: String, value: Any?): Unit = synchronized(lock) {
        docs.removeAll { fieldOf(it, field) == value?.toString() }
    }
}
