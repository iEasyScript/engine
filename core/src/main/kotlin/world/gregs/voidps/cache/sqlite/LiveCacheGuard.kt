package world.gregs.voidps.cache.sqlite

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Refuses read-write opens of a `.jcache` owned by the NXT client (the launcher's data dirs, the
 * HOME-redirected client cache, the legacy projectx dir).
 *
 * A read-write SQLite open rewrites the database header to WAL and leaves `-wal`/`-shm` sidecars,
 * which locks the file against the running client and forces a multi-GB cache re-download. Nothing
 * in this repo ever needs to write a client-owned cache — the engine reads it, the tools copy it —
 * so the refusal is unconditional and has no opt-out: the only writable caches are the server's own
 * [org.projectx.core.EnvVars.cachePath] and explicit download/scratch directories.
 *
 * Paths are canonicalised before comparison so a symlink into a protected root cannot slip past.
 */
object LiveCacheGuard {

    private const val LAUNCHER = "project-x-launcher"

    class WriteRefused(path: Path, root: Path) : IllegalStateException(
        "Refusing to open $path read-write: it belongs to the client-owned cache root $root. " +
            "Open it read-only (IndexFile(readOnly = true), SQLiteCache.load(readOnly = true), " +
            "Cache.init(path, readOnly = true)) or copy it to a scratch dir first."
    )

    /** Every directory tree whose caches belong to the client, canonicalised and de-duplicated. */
    fun roots(): List<Path> {
        val home = System.getProperty("user.home")
        return buildList {
            env("XDG_DATA_HOME")?.let { add("$it/$LAUNCHER") }
            env("APPDATA")?.let { add("$it/$LAUNCHER") }
            env("LOCALAPPDATA")?.let { add("$it/$LAUNCHER") }
            env("PROGRAMDATA")?.let { add("$it/Jagex") }
            add("$home/.local/share/$LAUNCHER")
            add("$home/Library/Application Support/$LAUNCHER")
            add("$home/Jagex")
            add("$home/.projectx")
        }.map { canonical(Paths.get(it)) }.distinct()
    }

    /** True when [path] sits in a client-owned cache tree and may therefore only ever be read. */
    fun owns(path: Path): Boolean = rootOf(path) != null

    /** Throws [WriteRefused] when [path] is client-owned. Call BEFORE opening the database. */
    fun verifyWritable(path: Path) {
        rootOf(path)?.let { throw WriteRefused(canonical(path), it) }
    }

    private fun rootOf(path: Path): Path? {
        val target = canonical(path)
        return roots().firstOrNull { target.startsWith(it) }
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun canonical(path: Path): Path = try {
        path.toRealPath()
    } catch (_: IOException) {
        val absolute = path.toAbsolutePath().normalize()
        val existing = generateSequence(absolute) { it.parent }.firstOrNull { Files.exists(it) }
            ?: return absolute
        try {
            existing.toRealPath().resolve(existing.relativize(absolute))
        } catch (_: IOException) {
            absolute
        }
    }
}
