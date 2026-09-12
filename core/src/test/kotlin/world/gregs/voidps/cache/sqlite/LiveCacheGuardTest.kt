package world.gregs.voidps.cache.sqlite

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import world.gregs.voidps.cache.CacheFixture
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveCacheGuardTest {

    private val home = System.getProperty("user.home")

    private val clientCaches = listOf(
        "$home/.local/share/project-x-launcher/Jagex/RuneScape",
        "$home/.local/share/project-x-launcher/custom/Jagex/RuneScape",
        "$home/Jagex/RuneScape",
        "$home/.projectx/Jagex/RuneScape",
    ).map { Path.of(it) }

    @Test
    fun `every client-owned cache dir is refused for writing`() {
        for (dir in clientCaches) {
            assertTrue(LiveCacheGuard.owns(dir), "$dir must be recognised as client-owned")
            assertThrows<LiveCacheGuard.WriteRefused> { LiveCacheGuard.verifyWritable(dir) }
        }
    }

    @Test
    fun `a read-write IndexFile open of a client cache throws and creates nothing`() {
        val file = clientCaches.first().resolve("js5-9999.jcache")
        assertFalse(file.exists(), "test target must not exist up front")
        assertThrows<LiveCacheGuard.WriteRefused> { IndexFile(file) }
        assertFalse(file.exists(), "the guard must run before SQLite can create the file")
        assertFalse(Path.of("$file-wal").exists(), "no journal sidecar may be left behind")
    }

    @Test
    fun `a read-write SQLiteCache load of a client cache dir throws`() {
        val dir = clientCaches.firstOrNull { Files.isDirectory(it) } ?: return
        assertThrows<LiveCacheGuard.WriteRefused> { SQLiteCache.load(dir) }
    }

    @Test
    fun `the server's own cache stays writable`() {
        val dir = CacheFixture.resolveCacheDir() ?: Path.of(System.getProperty("user.dir"), "data/cache")
        assertFalse(LiveCacheGuard.owns(dir), "$dir is the server's own cache and must remain writable")
        LiveCacheGuard.verifyWritable(dir)
    }
}
