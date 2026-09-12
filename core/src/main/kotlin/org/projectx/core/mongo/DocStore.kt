package org.projectx.core.mongo

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * Minimal document-store abstraction over the slice of MongoDB the server actually uses.
 *
 * Two implementations exist: [MongoDocStore] (the real driver, used against an external MongoDB)
 * and [InMemoryDocStore] (a tiny in-process mock used when `MONGO_IN_MEMORY=true`, so dev runs need
 * neither an installed mongod nor the old embedded-mongod download). [MongoManager] picks one at
 * init based on the flag; everything else talks only to this interface.
 *
 * Queries are intentionally limited to single-field equality / `$in` / OR-of-equality — that is the
 * entire surface the account + clan stores need, and keeping it small is what lets the in-memory
 * mock stay trivial.
 */
interface DocStore {
    fun <T : Any> collection(name: String, type: KClass<T>, serializer: KSerializer<T>): DocCollection<T>
}

inline fun <reified T : Any> DocStore.collection(name: String): DocCollection<T> =
    collection(name, T::class, serializer())

interface DocCollection<T : Any> {
    suspend fun ensureIndex(field: String, unique: Boolean = false, sparse: Boolean = false)

    suspend fun findOne(field: String, value: Any?): T?

    /** Documents matching any of the given `field == value` conditions (mongo `$or`). */
    suspend fun findAnyOf(vararg conditions: Pair<String, Any?>): List<T>

    /** Documents whose [field] is one of [values] (mongo `$in`). */
    suspend fun findIn(field: String, values: Collection<Any?>): List<T>

    suspend fun count(field: String, value: Any?): Long

    suspend fun insert(doc: T)

    /** Replace the document whose [field] == [value], inserting it if none matches (upsert). */
    suspend fun upsert(field: String, value: Any?, doc: T)

    suspend fun delete(field: String, value: Any?)
}
