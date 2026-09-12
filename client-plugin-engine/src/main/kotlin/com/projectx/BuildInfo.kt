package com.projectx

import java.io.File
import java.time.Instant

/**
 * Identifies the currently-loaded engine build. Derived from the jar this class was loaded from
 * (name + mtime), so it changes on every `./gradlew :client-plugin-engine:build` and reflects the jar that is
 * *actually* loaded — under the hot-reload `URLClassLoader` a reinject that picked up a freshly
 * built jar shows a new value. This is the end-to-end verification signal for uninject/reinject.
 */
object BuildInfo {
    val VERSION: String by lazy {
        runCatching {
            val src = File(BuildInfo::class.java.protectionDomain.codeSource.location.toURI())
            "${src.name}@${Instant.ofEpochMilli(src.lastModified())}"
        }.getOrDefault("unknown")
    }
}
