package org.projectx.core

import java.io.File

/**
 * Locates the project-x working tree so project-relative data files resolve to the ONE
 * editable copy in the repo — for the server (CWD = repo root) AND the injected engine (CWD = the
 * game client's, but its classes load from a jar under `<repo>/client-plugin-engine/build`). When the tree can't
 * be found (a packaged deploy), callers fall back to a per-user home base.
 */
object ProjectPaths {
    private fun isRoot(dir: File) =
        File(dir, "settings.gradle.kts").isFile && File(dir, "re-resources").isDirectory

    private fun walkUp(start: File?): File? {
        var dir = start?.absoluteFile
        while (dir != null) {
            if (isRoot(dir)) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun codeSource(): File? = runCatching {
        File(ProjectPaths::class.java.protectionDomain.codeSource.location.toURI())
    }.getOrNull()

    /** The repo root, or null when running outside the working tree. */
    val root: File? by lazy {
        System.getenv("PROJECTX_PROJECT_ROOT")?.let(::File)?.takeIf(::isRoot)
            ?: walkUp(File(System.getProperty("user.dir")))
            ?: walkUp(codeSource())
    }

    /** Per-user fallback base (`~/.projectx`) used when [root] is unavailable. */
    val home: File = File(System.getProperty("user.home"), ".projectx")

    /** The repo copy at [repoRelative] when the working tree is found, else the home fallback. */
    fun resolve(repoRelative: String, homeRelative: String): File =
        root?.let { File(it, repoRelative) } ?: File(home, homeRelative)
}
