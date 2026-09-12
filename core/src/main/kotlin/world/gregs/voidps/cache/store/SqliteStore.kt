package world.gregs.voidps.cache.store

import world.gregs.voidps.cache.sqlite.LiveCacheGuard
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name

/**
 * The NXT client's cache: one SQLite database per index, `js5-<index>.jcache`.
 *
 * Each database has two tables of the same shape, `cache` holding the archive containers keyed
 * by archive id and `cache_index` holding the index's reference table under key 1; `js5-255`
 * holds no archives and keeps the signed master version table in its `cache_index` instead. A
 * container is stored exactly as JS5 serves it - no version trailer - with the archive's version
 * and the container's CRC beside it in their own columns.
 *
 * A read-only store is opened `immutable`, which takes no lock and writes no sidecar, so a cache
 * another process owns is never disturbed; every reading thread gets a connection of its own. A
 * writable store goes through [LiveCacheGuard] first, holds one connection per index, and batches
 * its writes into transactions that [flush] and [close] commit.
 */
class SqliteStore private constructor(
    override val directory: Path,
    override val writable: Boolean
) : CacheStore {

    override val kind: StoreKind
        get() = StoreKind.SQLITE

    override val trailers: Boolean
        get() = false

    private val writers = ConcurrentHashMap<Int, Writer>()

    private val readers = ConcurrentHashMap<Int, ThreadLocal<Connection?>>()

    private val opened = ConcurrentHashMap.newKeySet<Connection>()

    @Volatile
    private var closed = false

    override fun indexCount(): Int {
        var highest = -1
        for (index in files()) {
            if (index != MASTER && index > highest) {
                highest = index
            }
        }
        return highest + 1
    }

    override fun indices(): IntArray = files().filter { it != MASTER && readTable(it) != null }.sorted().toIntArray()

    override fun readTable(index: Int): ByteArray? = query(index, "SELECT DATA FROM cache_index WHERE KEY = 1") { it.getBytes(1) }

    override fun read(index: Int, archive: Int): ByteArray? =
        query(index, "SELECT DATA FROM cache WHERE KEY = $archive") { it.getBytes(1) }

    override fun archives(index: Int): IntArray {
        val ids = ArrayList<Int>()
        queryAll(index, "SELECT KEY FROM cache ORDER BY KEY") { ids.add(it.getInt(1)) }
        return ids.toIntArray()
    }

    /** The signed master version table `js5-255` keeps, or null when the store has none. */
    fun readMasterTable(): ByteArray? = readTable(MASTER)

    override fun write(index: Int, archive: Int, container: ByteArray, version: Int, crc: Int) {
        writer(index).write("cache", archive, container, version, crc)
    }

    override fun writeTable(index: Int, container: ByteArray, version: Int, crc: Int) {
        writer(index).write("cache_index", 1, container, version, crc)
    }

    /** Store the master version table under `js5-255`. */
    fun writeMasterTable(table: ByteArray, crc: Int) {
        writeTable(MASTER, table, 0, crc)
    }

    override fun remove(index: Int, archive: Int) {
        if (!Files.isRegularFile(file(index))) {
            return
        }
        writer(index).delete("cache", archive)
    }

    override fun removeIndex(index: Int) {
        check(writable) { "This store was opened read only." }
        writers.remove(index)?.close()
        Files.deleteIfExists(file(index))
    }

    override fun flush() {
        for (writer in writers.values) {
            writer.commit()
        }
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        for (writer in writers.values) {
            writer.close()
        }
        writers.clear()
        for (connection in opened) {
            runCatching { connection.close() }
        }
        opened.clear()
        readers.clear()
    }

    private fun files(): List<Int> {
        if (!Files.isDirectory(directory)) {
            return emptyList()
        }
        Files.list(directory).use { stream ->
            return stream.map { indexOf(it.name) }.filter { it != null }.map { it!! }.toList()
        }
    }

    private fun file(index: Int): Path = directory.resolve("$PREFIX$index$EXTENSION")

    /** An index the client left as an empty file has no tables at all, and holds no archives. */
    private fun Connection.hasCacheTables(): Boolean =
        createStatement().use { statement ->
            statement.executeQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'cache_index'").use { it.next() }
        }

    private fun <T> query(index: Int, sql: String, read: (java.sql.ResultSet) -> T): T? {
        val connection = reader(index) ?: return null
        if (!connection.hasCacheTables()) {
            return null
        }
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                return if (result.next()) read(result) else null
            }
        }
    }

    private fun queryAll(index: Int, sql: String, read: (java.sql.ResultSet) -> Unit) {
        val connection = reader(index) ?: return
        if (!connection.hasCacheTables()) {
            return
        }
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                while (result.next()) {
                    read(result)
                }
            }
        }
    }

    /**
     * A connection for the calling thread. A writable store reads through its writer so that a
     * read sees what was just written; a read-only store opens the file immutable, once per thread.
     */
    private fun reader(index: Int): Connection? {
        check(!closed) { "The store is closed." }
        if (writable) {
            if (!Files.isRegularFile(file(index))) {
                return null
            }
            return writer(index).connection
        }
        val local = readers.getOrPut(index) { ThreadLocal() }
        local.get()?.let { return it }
        val file = file(index)
        if (!Files.isRegularFile(file)) {
            return null
        }
        val connection = DriverManager.getConnection("jdbc:sqlite:file:$file?immutable=1")
        opened.add(connection)
        local.set(connection)
        return connection
    }

    private fun writer(index: Int): Writer {
        check(writable) { "This store was opened read only." }
        check(!closed) { "The store is closed." }
        return writers.getOrPut(index) { Writer(file(index)) }
    }

    /**
     * One index database open for writing: a single connection, a transaction that is committed
     * every [BATCH] writes and on [commit], and the two tables created if they are missing.
     */
    private class Writer(path: Path) {
        val connection: Connection

        private val lock = Any()
        private var pending = 0

        init {
            Files.createDirectories(path.parent)
            val fresh = !Files.exists(path)
            connection = DriverManager.getConnection("jdbc:sqlite:$path")
            connection.createStatement().use { statement ->
                if (fresh) {
                    statement.execute("PRAGMA page_size=$PAGE_SIZE")
                    statement.execute("PRAGMA user_version=$USER_VERSION")
                }
                statement.execute("PRAGMA journal_mode=DELETE")
                statement.execute("PRAGMA synchronous=OFF")
                statement.execute("CREATE TABLE IF NOT EXISTS cache(KEY INTEGER PRIMARY KEY,DATA BLOB,VERSION INTEGER,CRC INTEGER)")
                statement.execute("CREATE TABLE IF NOT EXISTS cache_index(KEY INTEGER PRIMARY KEY,DATA BLOB,VERSION INTEGER,CRC INTEGER)")
            }
            connection.autoCommit = false
        }

        fun write(table: String, key: Int, data: ByteArray, version: Int, crc: Int) = synchronized(lock) {
            connection.prepareStatement("INSERT OR REPLACE INTO $table (KEY, DATA, VERSION, CRC) VALUES (?, ?, ?, ?)").use { statement ->
                statement.setInt(1, key)
                statement.setBytes(2, data)
                statement.setInt(3, version)
                statement.setInt(4, crc)
                statement.executeUpdate()
            }
            if (++pending >= BATCH) {
                connection.commit()
                pending = 0
            }
        }

        fun delete(table: String, key: Int) = synchronized(lock) {
            connection.prepareStatement("DELETE FROM $table WHERE KEY = ?").use { statement ->
                statement.setInt(1, key)
                statement.executeUpdate()
            }
            pending++
        }

        fun commit() = synchronized(lock) {
            if (pending > 0) {
                connection.commit()
                pending = 0
            }
        }

        fun close() = synchronized(lock) {
            runCatching { commit() }
            runCatching { connection.close() }
        }
    }

    companion object {
        const val PREFIX = "js5-"
        const val EXTENSION = ".jcache"

        /** The database that holds the master version table rather than an index. */
        const val MASTER = 255

        private const val PAGE_SIZE = 4096

        /** What the NXT client stamps its own databases with. */
        private const val USER_VERSION = 2

        private const val BATCH = 512

        /** The index a `js5-<n>.jcache` file name belongs to, or null for any other name. */
        fun indexOf(name: String): Int? {
            if (!name.startsWith(PREFIX) || !name.endsWith(EXTENSION)) {
                return null
            }
            return name.substring(PREFIX.length, name.length - EXTENSION.length).toIntOrNull()
        }

        fun open(directory: Path, writable: Boolean = false): SqliteStore {
            require(Files.isDirectory(directory)) { "No cache directory at ${directory.toAbsolutePath()}." }
            if (writable) {
                LiveCacheGuard.verifyWritable(directory)
            }
            return SqliteStore(directory, writable)
        }

        /** An empty writable store. [indexCount] is not needed: an index database is created when it is first written. */
        fun create(directory: Path, @Suppress("UNUSED_PARAMETER") indexCount: Int): SqliteStore {
            Files.createDirectories(directory)
            LiveCacheGuard.verifyWritable(directory)
            return SqliteStore(directory, writable = true)
        }

        init {
            runCatching {
                val driver = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as Driver
                DriverManager.registerDriver(driver)
            }
        }
    }
}
