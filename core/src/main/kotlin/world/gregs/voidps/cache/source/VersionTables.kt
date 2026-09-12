package world.gregs.voidps.cache.source

import org.projectx.core.EnvVars
import world.gregs.voidps.cache.secure.CRC
import world.gregs.voidps.cache.secure.VersionTableBuilder
import world.gregs.voidps.cache.secure.Whirlpool
import world.gregs.voidps.cache.store.CacheStore
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.ReferenceTable
import world.gregs.voidps.cache.store.SqliteStore
import java.math.BigInteger

/**
 * The master version table - index 255's own listing of every reference table - derived from a
 * store's tables and signed with the server's JS5 key.
 *
 * A legacy client is sent it from memory, so a sector store never holds one. The NXT client keeps
 * the one it downloaded in `js5-255`, so a SQLite store is given a freshly derived one whenever it
 * is packed, which is what lets the server open the build without deriving it again.
 */
object VersionTables {

    /**
     * Derive the version table over every reference table in [store], for [indexCount] indices. An
     * index the store does not hold is skipped, so a store can be derived over a wider count than
     * its own and compared like for like against a tree that declares more indices than it packed.
     */
    fun derive(store: CacheStore, exponent: BigInteger, modulus: BigInteger, indexCount: Int = store.indexCount()): ByteArray {
        val builder = VersionTableBuilder(exponent, modulus, indexCount)
        val whirlpool = Whirlpool()
        for (index in 0 until indexCount) {
            entry(builder, index, store.readTable(index), whirlpool)
        }
        return builder.build(whirlpool)
    }

    /** Derive the version table over [tables], for [indexCount] indices. */
    fun derive(tables: Map<Int, ByteArray>, indexCount: Int, exponent: BigInteger, modulus: BigInteger): ByteArray {
        val builder = VersionTableBuilder(exponent, modulus, indexCount)
        val whirlpool = Whirlpool()
        for (index in 0 until indexCount) {
            entry(builder, index, tables[index], whirlpool)
        }
        return builder.build(whirlpool)
    }

    /** Store a derived table in [store] when it is the kind that keeps one and a key is configured. */
    fun write(store: CacheStore, tree: SourceTree) {
        if (store !is SqliteStore) {
            return
        }
        val keys = keys() ?: return
        val table = derive(store, keys.first, keys.second)
        store.writeMasterTable(table, CRC.calculate(table, 0, table.size))
        store.flush()
    }

    /** The server's JS5 signing key, or null when none is configured. */
    fun keys(): Pair<BigInteger, BigInteger>? {
        val exponent = EnvVars.js5RsaExponent
        val modulus = EnvVars.js5RsaModulus
        if (exponent.isBlank() || modulus.isBlank()) {
            return null
        }
        return BigInteger(exponent) to BigInteger(modulus)
    }

    private fun entry(builder: VersionTableBuilder, index: Int, bytes: ByteArray?, whirlpool: Whirlpool) {
        if (bytes == null) {
            builder.skip(index)
            return
        }
        builder.sector(index, bytes, whirlpool)
        val container = Container.decode(bytes, trailer = false)
        val decompressed = container.data()
        builder.uncompressedSize(index, decompressed.size)
        val table = ReferenceTable.decode(decompressed)
        if (table.format >= 6) {
            builder.revision(index, table.revision)
        }
        var highest = 0
        for (archive in table.archives) {
            if (archive.id > highest) {
                highest = archive.id
            }
        }
        builder.fileCount(index, if (table.archiveCount == 0) 0 else highest + 1)
    }
}
