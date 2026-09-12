package world.gregs.voidps.cache.sqlite

import org.projectx.core.Logger.logError
import java.io.Closeable
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException

/**
 * SQLite-backed storage for a single cache index (`js5-N.jcache`).
 *
 * The single JDBC [Connection] is shared by all callers (JS5 serve coroutines run on
 * multiple threads), so every connection access is serialised with [lock]. SQLite would
 * serialise statements internally anyway; the lock also protects the cached
 * [PreparedStatement]s and the ref-table cache.
 *
 * The constructor throws on any failure to open or initialise the database so the caller
 * can distinguish a broken index from an absent archive.
 */
/**
 * @param readOnly opens the database read-only (`mode=ro`) and performs NO writes — no journal-mode
 * change, no table creation, no version/data writes. REQUIRED when reading a cache owned by another
 * process (the injected engine reading the live NXT client's cache): writing it — even just the
 * `journal_mode=WAL` header pragma — corrupts the indices the client has open, forcing it to
 * re-download them. The server, which owns its cache, leaves this false to download/serve via JS5.
 */
class IndexFile(path: Path, private val readOnly: Boolean = false) : Closeable {
    private val lock = Any()
    private val connection: Connection = open(path, readOnly)
    private val getRawStatement: PreparedStatement
    private val getLengthStatement: PreparedStatement

    /** Ref table bytes cached after first load - read once at startup, served many times. */
    private var refTable: ByteArray? = null

    init {
        try {
            // A read-only connection must not issue journal_mode/CREATE TABLE — they write the file.
            if (!readOnly) {
                connection.prepareStatement("PRAGMA journal_mode=WAL;").use { it.executeQuery().close() }
                connection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS `cache`(`KEY` INTEGER PRIMARY KEY, `DATA` BLOB, `VERSION` INTEGER, `CRC` INTEGER);"
                ).use { it.executeUpdate() }
                connection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS `cache_index`(`KEY` INTEGER PRIMARY KEY, `DATA` BLOB, `VERSION` INTEGER, `CRC` INTEGER);"
                ).use { it.executeUpdate() }
            }
            connection.prepareStatement("PRAGMA busy_timeout=30000;").use { it.executeQuery().close() }
            getRawStatement = connection.prepareStatement("SELECT DATA FROM cache WHERE KEY = ?")
            getLengthStatement = connection.prepareStatement("SELECT LENGTH(DATA) FROM cache WHERE KEY = ?")
        } catch (e: SQLException) {
            try {
                connection.close()
            } catch (_: SQLException) {
            }
            throw e
        }
    }

    fun hasReferenceTable(): Boolean {
        return getRawTable() != null
    }

    fun getRaw(id: Int): ByteArray? = synchronized(lock) {
        try {
            getRawStatement.setInt(1, id)
            getRawStatement.executeQuery().use { result ->
                if (result.next()) result.getBytes(1) else null
            }
        } catch (e: SQLException) {
            null
        }
    }

    /** Size in bytes of the stored blob for [id], or -1 if absent. */
    fun getLength(id: Int): Int = synchronized(lock) {
        try {
            getLengthStatement.setInt(1, id)
            getLengthStatement.executeQuery().use { result ->
                if (result.next()) {
                    val length = result.getInt(1)
                    if (result.wasNull()) -1 else length
                } else {
                    -1
                }
            }
        } catch (e: SQLException) {
            -1
        }
    }

    fun getRawTable(): ByteArray? = synchronized(lock) {
        val cached = refTable
        if (cached != null) {
            return cached
        }
        val data = try {
            connection.prepareStatement("SELECT DATA FROM cache_index WHERE KEY = 1").use { stmt ->
                stmt.executeQuery().use { result ->
                    if (result.next()) result.getBytes(1) else null
                }
            }
        } catch (e: SQLException) {
            null
        }
        refTable = data
        data
    }

    fun getMaxArchive(): Int = synchronized(lock) {
        try {
            connection.prepareStatement("SELECT MAX(`KEY`) FROM cache").use { stmt ->
                stmt.executeQuery().use { result ->
                    if (result.next()) result.getInt(1) else 0
                }
            }
        } catch (e: SQLException) {
            0
        }
    }

    fun exists(id: Int): Boolean = synchronized(lock) {
        try {
            connection.prepareStatement("SELECT 1 FROM cache WHERE KEY = ?").use { stmt ->
                stmt.setInt(1, id)
                stmt.executeQuery().use { it.next() }
            }
        } catch (e: SQLException) {
            false
        }
    }

    fun allKeys(): Set<Int> = synchronized(lock) {
        try {
            connection.prepareStatement("SELECT KEY FROM cache").use { stmt ->
                stmt.executeQuery().use { result ->
                    val keys = mutableSetOf<Int>()
                    while (result.next()) {
                        keys.add(result.getInt(1))
                    }
                    keys
                }
            }
        } catch (e: SQLException) {
            emptySet()
        }
    }

    /** Returns map of archiveId -> (version, crc) for all stored archives. */
    fun allVersions(): Map<Int, Pair<Int, Int>> = synchronized(lock) {
        try {
            connection.prepareStatement("SELECT KEY, VERSION, CRC FROM cache").use { stmt ->
                stmt.executeQuery().use { result ->
                    val map = mutableMapOf<Int, Pair<Int, Int>>()
                    while (result.next()) {
                        map[result.getInt(1)] = result.getInt(2) to result.getInt(3)
                    }
                    map
                }
            }
        } catch (e: SQLException) {
            emptyMap()
        }
    }

    fun updateVersion(archiveId: Int, version: Int): Unit = synchronized(lock) {
        if (readOnly) return
        try {
            connection.prepareStatement("UPDATE cache SET VERSION = ? WHERE KEY = ?").use { stmt ->
                stmt.setInt(1, version)
                stmt.setInt(2, archiveId)
                stmt.executeUpdate()
            }
        } catch (e: SQLException) {
            logError("Failed to update version for archive $archiveId", e)
        }
    }

    fun batchUpdateVersions(updates: Map<Int, Int>) {
        if (readOnly || updates.isEmpty()) return
        synchronized(lock) {
            try {
                connection.autoCommit = false
                try {
                    connection.prepareStatement("UPDATE cache SET VERSION = ? WHERE KEY = ?").use { stmt ->
                        for ((archiveId, version) in updates) {
                            stmt.setInt(1, version)
                            stmt.setInt(2, archiveId)
                            stmt.addBatch()
                        }
                        stmt.executeBatch()
                    }
                    connection.commit()
                } catch (e: SQLException) {
                    try {
                        connection.rollback()
                    } catch (_: SQLException) {
                    }
                    throw e
                } finally {
                    connection.autoCommit = true
                }
            } catch (e: SQLException) {
                logError("Failed to batch update ${updates.size} versions", e)
            }
        }
    }

    fun putRaw(archiveId: Int, data: ByteArray, version: Int, crc: Int): Unit = synchronized(lock) {
        if (readOnly) return
        try {
            connection.prepareStatement(
                "INSERT OR REPLACE INTO cache (KEY, DATA, VERSION, CRC) VALUES (?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setInt(1, archiveId)
                stmt.setBytes(2, data)
                stmt.setInt(3, version)
                stmt.setInt(4, crc)
                stmt.executeUpdate()
            }
        } catch (e: SQLException) {
            logError("Failed to store archive $archiveId (${data.size} bytes)", e)
        }
    }

    fun putRefTable(data: ByteArray, version: Int, crc: Int): Unit = synchronized(lock) {
        if (readOnly) return
        try {
            connection.prepareStatement(
                "INSERT OR REPLACE INTO cache_index (KEY, DATA, VERSION, CRC) VALUES (1, ?, ?, ?)"
            ).use { stmt ->
                stmt.setBytes(1, data)
                stmt.setInt(2, version)
                stmt.setInt(3, crc)
                stmt.executeUpdate()
            }
            refTable = data
        } catch (e: SQLException) {
            logError("Failed to store ref table (${data.size} bytes)", e)
        }
    }

    override fun close(): Unit = synchronized(lock) {
        try {
            getRawStatement.close()
            getLengthStatement.close()
            connection.close()
        } catch (e: SQLException) {
            logError("Failed to close index file", e)
        }
    }

    companion object {
        /**
         * A read-write open is refused outright for a client-owned cache ([LiveCacheGuard]) — the
         * check runs before the connection so a wrong path can neither be written nor created.
         */
        private fun open(path: Path, readOnly: Boolean): Connection {
            if (readOnly) return DriverManager.getConnection("jdbc:sqlite:file:$path?mode=ro")
            LiveCacheGuard.verifyWritable(path)
            return DriverManager.getConnection("jdbc:sqlite:$path")
        }

        init {
            // The injected engine loads :core via a child URLClassLoader that DriverManager's
            // ServiceLoader auto-registration never scans, so a plain getConnection("jdbc:sqlite:…")
            // fails with "No suitable driver found". Register the driver explicitly through this
            // class's loader (a no-op on the server's normal classpath where it auto-registers).
            runCatching {
                val driver = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as Driver
                DriverManager.registerDriver(driver)
            }
        }
    }
}
