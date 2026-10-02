package world.gregs.voidps.cache

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the NXT client keeps its cache, in the order it is probed.
 *
 * Windows caches under the machine-wide ProgramData root and no HOME redirect reaches it, because
 * the launcher only rewrites HOME on unix. This list is the only one: the engine bootstrap, the
 * test fixture and the beta scanner all read it, and the two copies that drifted from it both lost
 * the Windows root and resolved nothing on this platform.
 */
object ClientCacheDirs {

    private const val LAUNCHER = "project-x-launcher"

    fun candidates(
        env: (String) -> String? = System::getenv,
        home: String = System.getProperty("user.home"),
    ): List<String> = buildList {
        env("PROGRAMDATA")?.let { add("$it/Jagex/RuneScape") }
        add("$home/Jagex/RuneScape")
        env("APPDATA")?.let { add("$it/$LAUNCHER/Jagex/RuneScape") }
        env("LOCALAPPDATA")?.let { add("$it/$LAUNCHER/Jagex/RuneScape") }
        env("XDG_DATA_HOME")?.let { add("$it/$LAUNCHER/Jagex/RuneScape") }
        add("$home/.local/share/$LAUNCHER/Jagex/RuneScape")
        add("$home/Library/Application Support/$LAUNCHER/Jagex/RuneScape")
    }

    /** The first candidate that holds a cache, or null when this machine has none. */
    fun resolve(
        env: (String) -> String? = System::getenv,
        home: String = System.getProperty("user.home"),
    ): Path? = candidates(env, home).map(Path::of).firstOrNull(::holdsCache)

    fun holdsCache(dir: Path): Boolean =
        Files.isDirectory(dir) && (0..255).any { Files.exists(dir.resolve("js5-$it.jcache")) }
}
