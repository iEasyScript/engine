package world.gregs.voidps.cache.cs2

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.DbTableType
import world.gregs.voidps.cache.type.data.EnumType
import world.gregs.voidps.cache.type.data.ParamType
import world.gregs.voidps.cache.type.decoder.DbTableDecoder
import world.gregs.voidps.cache.type.decoder.EnumDecoder
import world.gregs.voidps.cache.type.decoder.ParamDecoder

/**
 * The config records the CS2 work reads, out of the cache it is working on.
 *
 * Typing an operand needs more than the script: a param's declared type, an enum's key and value
 * types, a db column's tuple. Those come from config records, and *which* cache they come from is
 * not the process's business - it is the corpus's. A server builds its cache from a source tree,
 * so the cache being analysed is the one the tree was unpacked from, while the process-wide
 * [Cache] singleton is the one the build is still writing; reaching for that one from inside a
 * pack deadlocks against the build that has not finished producing it.
 *
 * [install] therefore points this at the corpus's cache and [warm] decodes everything up front, on
 * the thread that sets the corpus up, so the parallel pack that follows only ever reads.
 */
object Cs2Records {

    @Volatile
    private var source: Cache? = null

    @Volatile
    private var params: Array<ParamType>? = null

    @Volatile
    private var enums: Array<EnumType>? = null

    @Volatile
    private var dbTables: Array<DbTableType>? = null

    /** Read the records from [cache] from now on, dropping whatever another cache decoded. */
    fun install(cache: Cache) {
        synchronized(this) {
            if (source === cache) {
                return
            }
            source = cache
            params = null
            enums = null
            dbTables = null
        }
    }

    /** Decode every record now, so nothing decodes them later from several threads at once. */
    fun warm() {
        params()
        enums()
        dbTables()
    }

    /** The cache being worked on; the process's own only where nobody installed one. */
    fun cache(): Cache = source ?: Cache.get()

    fun params(): Array<ParamType> =
        params ?: synchronized(this) { params ?: ParamDecoder().load(cache()).also { params = it } }

    fun enums(): Array<EnumType> =
        enums ?: synchronized(this) { enums ?: EnumDecoder().load(cache()).also { enums = it } }

    fun dbTable(id: Int): DbTableType? = dbTables().getOrNull(id)

    private fun dbTables(): Array<DbTableType> =
        dbTables ?: synchronized(this) { dbTables ?: DbTableDecoder().load(cache()).also { dbTables = it } }
}
