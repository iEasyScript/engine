package world.gregs.voidps.cache

import org.junit.jupiter.api.Assumptions.assumeTrue
import world.gregs.voidps.cache.sqlite.SQLiteCache
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.streams.asSequence

object CacheFixture {
    private fun Path.hasCache(): Boolean = exists() && Files.list(this).use { s ->
        s.asSequence().any { it.fileName.toString().matches(Regex("js5-\\d+\\.jcache")) }
    }

    fun resolveCacheDir(): Path? {
        val userDir = System.getProperty("user.dir")
        for (candidate in listOf("$userDir/data/cache", "$userDir/../data/cache")) {
            val p = Path.of(candidate)
            if (p.hasCache()) return p
        }
        return null
    }

    /**
     * The NXT client's own cache dirs, in the order the engine bootstrap probes them (`RS_CACHE_DIR`
     * is exported by the launcher per server mode).
     */
    fun resolveClientCacheDir(): Path? {
        System.getenv("RS_CACHE_DIR")?.let { Path.of(it) }?.takeIf { it.hasCache() }?.let { return it }
        val home = System.getProperty("user.home")
        for (candidate in listOf("$home/Jagex/RuneScape", "$home/.local/share/project-x-launcher/Jagex/RuneScape")) {
            val p = Path.of(candidate)
            if (p.hasCache()) return p
        }
        return null
    }

    fun load(dir: Path): Cache = SQLiteCache.load(dir, readOnly = true)

    fun init(dir: Path) = Cache.init(load(dir))

    /**
     * Opens the repo's cache for a test that reads real game data, or SKIPS the test when this
     * machine has none.
     *
     * The cache is a 24GB download that is not in the repo, so a contributor who has not fetched it
     * must still be able to build. A missing cache is an absent prerequisite, not a failure.
     */
    fun requireCache() {
        val dir = resolveCacheDir()
        assumeTrue(dir != null, "no game cache on this machine — skipping")
        if (!initialised) {
            init(dir!!)
            initialised = true
        }
    }

    /**
     * Additionally skips when the cache does not define every id the test asserts on. Caches built
     * from different downloads carry different content, and a test that names ids the local cache
     * has never heard of is out of scope on that machine rather than broken.
     */
    fun requireSeqs(vararg ids: Int) = requireAll(ids) { Cache.seq(it) != null }

    fun requireStructs(vararg ids: Int) = requireAll(ids) { Cache.struct(it) != null }

    fun requireSpotAnims(vararg ids: Int) = requireAll(ids) { Cache.spotAnim(it) != null }

    private fun requireAll(ids: IntArray, present: (Int) -> Boolean) {
        requireCache()
        val missing = ids.filterNot(present)
        assumeTrue(missing.isEmpty(), "cache does not define $missing — skipping")
    }

    @Volatile
    private var initialised = false
}
