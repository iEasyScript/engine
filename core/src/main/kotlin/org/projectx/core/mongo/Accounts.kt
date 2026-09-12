package org.projectx.core.mongo

import org.projectx.core.EnvVars
import org.projectx.core.Logger
import org.projectx.core.formatForDisplay
import org.projectx.core.formatForProtocol
import org.projectx.core.model.Account
import org.projectx.core.model.Rights
import org.projectx.core.security.PasswordHash

/**
 * `accounts` store operations.
 * Schema based on ~/projectx/server reference.
 */
object Accounts {
    private val collection: DocCollection<Account> get() = MongoManager.store.collection("accounts")

    suspend fun ensureIndexes() {
        collection.ensureIndex("username", unique = true)
        collection.ensureIndex("email", unique = true, sparse = true)
        collection.ensureIndex("displayName")
        Logger.log("Accounts", "Indexes ensured on accounts collection")
    }

    suspend fun findByUsername(username: String): Account? =
        collection.findOne("username", username.lowercase())

    suspend fun findByEmail(email: String): Account? =
        collection.findOne("email", email.lowercase())

    suspend fun findByDisplayName(displayName: String): Account? {
        val usernameKey = displayName.lowercase().replace(" ", "_")
        // Single round-trip: match either field, then prefer the display-name hit.
        val matches = collection.findAnyOf("displayName" to displayName, "username" to usernameKey)
        return matches.firstOrNull { it.displayName == displayName } ?: matches.firstOrNull()
    }

    /** Batch lookup for friend list initialization — avoids N+1 queries. */
    suspend fun findByUsernames(usernames: Collection<String>): List<Account> {
        if (usernames.isEmpty()) return emptyList()
        return collection.findIn("username", usernames.toList())
    }

    suspend fun exists(username: String): Boolean =
        collection.count("username", username.lowercase()) > 0

    /** Create a new account. Returns the created account. */
    suspend fun create(username: String, email: String, password: String, displayName: String? = null): Account {
        val proto = username.formatForProtocol()
        val account = Account(
            username = proto,
            email = email.lowercase(),
            displayName = displayName ?: proto.formatForDisplay(),
            passwordHash = PasswordHash.hashSuspend(password),
            rights = if (EnvVars.mongoInMemory) Rights.ADMIN else Rights.PLAYER,
        )
        collection.insert(account)
        Logger.log("Accounts", "Created account: ${account.username} (${account.displayName})")
        return account
    }

    /** Auto-create for SSO login — creates with minimal data if account doesn't exist. */
    suspend fun getOrCreate(username: String): Account {
        val proto = username.formatForProtocol()
        return findByUsername(proto) ?: create(
            username = proto,
            email = "$proto@projectx.local",
            password = "sso_${System.currentTimeMillis()}",
        )
    }

    /** Persist account changes. */
    suspend fun save(account: Account) {
        collection.upsert("username", account.username, account)
    }
}
