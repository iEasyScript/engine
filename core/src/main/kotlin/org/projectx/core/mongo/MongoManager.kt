package org.projectx.core.mongo

import com.mongodb.kotlin.client.coroutine.MongoClient
import org.projectx.core.EnvVars
import org.projectx.core.Logger

/**
 * Picks and owns the process-wide [DocStore]: an [InMemoryDocStore] when `MONGO_IN_MEMORY=true`
 * (dev convenience — no external mongod needed), otherwise a [MongoDocStore] over a real MongoDB at
 * [EnvVars.mongoUri].
 */
object MongoManager {
    private var client: MongoClient? = null

    lateinit var store: DocStore
        private set

    fun init() {
        if (::store.isInitialized) return
        if (EnvVars.mongoInMemory) {
            store = InMemoryDocStore()
            Logger.log("MongoManager", "Using in-memory store (MONGO_IN_MEMORY=true; no external MongoDB)")
            return
        }
        val mongo = MongoClient.create(EnvVars.mongoUri)
        client = mongo
        store = MongoDocStore(mongo.getDatabase(EnvVars.mongoDatabase))
        Logger.log("MongoManager", "Connected to ${EnvVars.mongoUri}/${EnvVars.mongoDatabase}")
    }

    fun close() {
        client?.close()
        client = null
    }
}
