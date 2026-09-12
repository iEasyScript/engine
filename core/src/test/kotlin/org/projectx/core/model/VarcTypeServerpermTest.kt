package org.projectx.core.model

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.sqlite.SQLiteCache
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.streams.asSequence
import kotlin.test.assertTrue

class VarcTypeServerpermTest {

    private fun Path.hasCache(): Boolean = exists() && Files.list(this).use { s ->
        s.asSequence().any { it.fileName.toString().matches(Regex("js5-\\d+\\.jcache")) }
    }

    private fun resolveCacheDir(): Path? {
        val userDir = System.getProperty("user.dir")
        for (candidate in listOf("$userDir/data/cache", "$userDir/../data/cache")) {
            val p = Path.of(candidate)
            if (p.hasCache()) return p
        }
        return null
    }

    @Test
    fun `serverperm varcs come from the cache and cover the captured set`() {
        val dir = resolveCacheDir()
        assumeTrue(dir != null, "no cache on this machine — skipping")
        Cache.init(SQLiteCache.load(dir!!, readOnly = true))

        val fromCache = Cache.varcs.filter { it.serverperm }.map { it.id }.toSet()
        assertTrue(fromCache.isNotEmpty(), "VarcType decoded zero serverperm varcs — decoder/lifetime is wrong")

        val captured = Vars.DEFAULT_SERVERPERM_VARCS.keys
        val missing = captured - fromCache
        assertTrue(missing.isEmpty(), "cache serverperm set misses ${missing.size} captured ids: ${missing.take(10)}")
    }
}
