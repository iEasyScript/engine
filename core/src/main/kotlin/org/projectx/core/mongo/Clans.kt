package org.projectx.core.mongo

import org.projectx.core.Logger
import org.projectx.core.model.Clan

/**
 * `clans` store operations.
 */
object Clans {
    private val collection: DocCollection<Clan> get() = MongoManager.store.collection("clans")

    suspend fun ensureIndexes() {
        collection.ensureIndex("name", unique = true)
        collection.ensureIndex("leaderUsername")
        Logger.log("Clans", "Indexes ensured on clans collection")
    }

    suspend fun find(name: String): Clan? =
        collection.findOne("name", name)

    suspend fun findByLeader(leaderUsername: String): Clan? =
        collection.findOne("leaderUsername", leaderUsername)

    suspend fun save(clan: Clan) =
        collection.upsert("name", clan.name, clan)

    suspend fun delete(name: String) =
        collection.delete("name", name)
}
