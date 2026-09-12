package org.projectx.core.cache

import org.projectx.core.EnvVars
import org.projectx.core.Logger.logInfo
import world.gregs.voidps.cache.source.BuildResult
import world.gregs.voidps.cache.source.Builder
import world.gregs.voidps.cache.source.SourceTree
import world.gregs.voidps.cache.source.codec.cs2.Cs2SourceCodec
import world.gregs.voidps.cache.store.CacheStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The server's switch between the prepacked cache and a cache source tree
 * (`re-resources/docs/cache/CACHE_SOURCE_TREE.md`).
 *
 * With `CACHE_SOURCE_PATH` unset nothing happens and [EnvVars.cachePath] is what the server serves.
 * With it set, [ensureBuilt] builds the tree into [EnvVars.cacheBuildPath] - a full pack the first
 * time, afterwards only the archives whose inputs changed, a stat walk when nothing did - and the
 * server serves that build. The tree's passthrough indices are copied from the prepacked cache at
 * [EnvVars.cachePath], which is why it still has to exist beside the tree. Every default cache open
 * goes through [servedPath], so no caller has to remember the switch.
 */
object CacheSource {

    @Volatile
    private var result: BuildResult? = null

    /** The tree the server serves from, or null when it serves the prepacked cache. */
    val tree: Path? = EnvVars.cacheSourcePath?.let { Paths.get(it) }

    val enabled: Boolean
        get() = tree != null

    /** What the last [ensureBuilt] did, once it has run. */
    val lastBuild: BuildResult?
        get() = result

    /** The directory the server opens: the build of the tree when one is configured, the prepacked cache otherwise. */
    fun servedPath(): String {
        if (!enabled) {
            return EnvVars.cachePath
        }
        ensureBuilt()
        return EnvVars.cacheBuildPath
    }

    /** Build the configured tree if this process has not yet; idempotent, and a no-op without a tree. */
    @Synchronized
    fun ensureBuilt(): BuildResult? {
        val directory = tree ?: return null
        result?.let { return it }
        check(Files.isDirectory(directory)) { "CACHE_SOURCE_PATH '${directory.toAbsolutePath()}' is not a directory" }
        val source = SourceTree.open(directory)
        val output = Paths.get(EnvVars.cacheBuildPath)
        logInfo("Building cache from source tree ${directory.toAbsolutePath()} into ${output.toAbsolutePath()}")
        // The clientscript compiler resolves a call against its callee's signature, so it analyses the
        // whole corpus first, and it cannot read that from the cache this build is still writing.
        Cs2SourceCodec.cachePath = Paths.get(EnvVars.cachePath)
        val packed = if (source.passthrough.isEmpty()) null else CacheStore.open(Paths.get(EnvVars.cachePath))
        val built = try {
            Builder.build(source, output, packed) { line -> logInfo("cache build: $line") }
        } finally {
            packed?.close()
        }
        logInfo("Cache build ${if (built.unchanged) "up to date" else "done"}: $built")
        result = built
        return built
    }
}
