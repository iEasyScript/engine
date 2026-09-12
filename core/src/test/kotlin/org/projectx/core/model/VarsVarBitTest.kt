package org.projectx.core.model

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.sqlite.SQLiteCache
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.streams.asSequence
import kotlin.test.assertEquals

class VarsVarBitTest {

    private fun Path.hasCache(): Boolean = exists() && Files.list(this).use { stream ->
        stream.asSequence().any { it.fileName.toString().matches(Regex("js5-\\d+\\.jcache")) }
    }

    private fun resolveCacheDir(): Path? {
        System.getenv("RS_CACHE_DIR")?.let { Path.of(it) }?.takeIf { it.hasCache() }?.let { return it }
        val userDir = System.getProperty("user.dir")
        for (candidate in listOf("$userDir/data/cache", "$userDir/../data/cache")) {
            val p = Path.of(candidate)
            if (p.hasCache()) return p
        }
        return null
    }

    @Test
    fun `setVarBit sizes varpValues from the NXT var_player archive`() {
        val dir = resolveCacheDir()
        assumeTrue(dir != null, "no cache on this machine — skipping")
        Cache.init(SQLiteCache.load(dir!!, readOnly = true))

        val vars = Vars().initOffline()
        assertDoesNotThrow("setVarBit must not overrun varpValues (base varp 3814)") {
            vars.setVarBit("toplevel_v2_slim_mode", 1)
        }
        assertEquals(1, vars.getVarBit("toplevel_v2_slim_mode"))
    }
}
