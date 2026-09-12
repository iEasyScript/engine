package org.projectx.core.mongo

import com.mongodb.client.model.CountOptions
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

/** [DocStore] backed by a real external MongoDB (used when `MONGO_IN_MEMORY` is off). */
class MongoDocStore(private val database: MongoDatabase) : DocStore {
    override fun <T : Any> collection(name: String, type: KClass<T>, serializer: KSerializer<T>): DocCollection<T> =
        MongoDocCollection(database.getCollection(name, type.java))
}

private class MongoDocCollection<T : Any>(private val collection: MongoCollection<T>) : DocCollection<T> {
    override suspend fun ensureIndex(field: String, unique: Boolean, sparse: Boolean) {
        collection.createIndex(Indexes.ascending(field), IndexOptions().unique(unique).sparse(sparse))
    }

    override suspend fun findOne(field: String, value: Any?): T? =
        collection.find(Filters.eq(field, value)).firstOrNull()

    override suspend fun findAnyOf(vararg conditions: Pair<String, Any?>): List<T> =
        collection.find(Filters.or(conditions.map { Filters.eq(it.first, it.second) })).toList()

    override suspend fun findIn(field: String, values: Collection<Any?>): List<T> =
        collection.find(Filters.`in`(field, values)).toList()

    override suspend fun count(field: String, value: Any?): Long =
        collection.countDocuments(Filters.eq(field, value), CountOptions().limit(1))

    override suspend fun insert(doc: T) {
        collection.insertOne(doc)
    }

    override suspend fun upsert(field: String, value: Any?, doc: T) {
        collection.replaceOne(Filters.eq(field, value), doc, ReplaceOptions().upsert(true))
    }

    override suspend fun delete(field: String, value: Any?) {
        collection.deleteOne(Filters.eq(field, value))
    }
}
