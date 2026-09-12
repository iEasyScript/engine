package org.projectx.tools.betascanner

import org.projectx.core.EnvVars
import world.gregs.voidps.cache.sqlite.LiveCacheGuard
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Hard safety boundary for the beta scanner: the live game cache must NEVER be the
 * write target. Every protected location is absolutized + normalized, and the resolved
 * `--out` is rejected if it equals, sits inside, or contains any of them.
 */
object IsolationGuard {

    class ForbiddenOutputException(message: String) : RuntimeException(message)

    /**
     * All directories the scanner must never write into (absolute, normalized): every client-owned
     * root from [LiveCacheGuard] plus the server's own live cache, which the scanner must not touch
     * either even though the server itself writes it.
     */
    fun forbiddenPaths(): List<Path> {
        val server = listOf(EnvVars.cachePath, "./data/cache").map { Paths.get(it).toAbsolutePath().normalize() }
        return (server + LiveCacheGuard.roots()).distinct()
    }

    /**
     * Throws [ForbiddenOutputException] when [outAbs] (already absolutized + normalized)
     * is a protected path, lives inside one, or would contain one.
     */
    fun check(outAbs: Path) {
        for (forbidden in forbiddenPaths()) {
            if (outAbs == forbidden) {
                throw ForbiddenOutputException(
                    "--out resolves to a protected cache path: $outAbs"
                )
            }
            if (outAbs.startsWith(forbidden)) {
                throw ForbiddenOutputException(
                    "--out ($outAbs) is INSIDE a protected cache path: $forbidden"
                )
            }
            if (forbidden.startsWith(outAbs)) {
                throw ForbiddenOutputException(
                    "--out ($outAbs) CONTAINS a protected cache path: $forbidden. Pick an isolated directory."
                )
            }
        }
    }
}
