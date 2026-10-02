package world.gregs.voidps.cache

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientCacheDirsTest {

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? =
        pairs.toMap()::get

    @Test
    fun `windows programdata root is probed before the home dir`() {
        val candidates = ClientCacheDirs.candidates(
            env("PROGRAMDATA" to "C:/ProgramData", "APPDATA" to "C:/Users/u/AppData/Roaming"),
            home = "C:/Users/u",
        )
        assertEquals("C:/ProgramData/Jagex/RuneScape", candidates.first())
        assertTrue(candidates.contains("C:/Users/u/AppData/Roaming/project-x-launcher/Jagex/RuneScape"))
    }

    @Test
    fun `unix candidates survive with no windows environment`() {
        val candidates = ClientCacheDirs.candidates(env(), home = "/home/u")
        assertEquals(
            listOf(
                "/home/u/Jagex/RuneScape",
                "/home/u/.local/share/project-x-launcher/Jagex/RuneScape",
                "/home/u/Library/Application Support/project-x-launcher/Jagex/RuneScape",
            ),
            candidates,
        )
    }

    @Test
    fun `resolve picks the candidate that actually holds a cache`() {
        val base = Files.createTempDirectory("client-cache-dirs")
        try {
            val empty = base.resolve("empty/Jagex/RuneScape").also { it.createDirectories() }
            val real = base.resolve("real/Jagex/RuneScape").also { it.createDirectories() }
            Files.createFile(real.resolve("js5-12.jcache"))

            assertNull(ClientCacheDirs.resolve(env("PROGRAMDATA" to "$base/empty"), home = "$base/none"))
            assertEquals(
                real,
                ClientCacheDirs.resolve(env("PROGRAMDATA" to "$base/real"), home = "$base/none"),
            )
            assertTrue(Files.isDirectory(empty))
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}
