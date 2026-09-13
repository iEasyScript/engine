package com.projectx.ui

import com.projectx.script.ScriptExecutor
import com.projectx.script.ScriptMetadata
import com.projectx.util.Configuration
import java.io.File

/** Where a script came from, told from the jar its class was loaded out of. */
enum class ScriptSource(val label: String) {
    OFFICIAL("Official"),
    COMMUNITY("Community"),
    LOCAL("Your scripts"),
}

/**
 * The scripts a user has added to their library from the Store. Every loaded script is in the Store; the library
 * is the short list the Scripts tab shows, so a large community catalogue does not bury the few scripts in use.
 *
 * Held by class name rather than by class: a script reload loads fresh classes, and the library must survive it.
 */
object ScriptLibrary {
    private const val COMMUNITY_JAR_PREFIX = "community-scripts"
    private const val OFFICIAL_JAR_PREFIX = "official-scripts"

    private val added: MutableSet<String> = Configuration.config.libraryScripts?.toMutableSet() ?: mutableSetOf()
    private var seeded = Configuration.config.libraryScripts != null
    private val sources = mutableMapOf<String, ScriptSource>()

    /** Bumped on every change, so a tab can cache its filtered list against it. */
    var version = 0
        private set

    fun contains(meta: ScriptMetadata): Boolean {
        seedIfNeeded()
        return meta.scriptClass.name in added
    }

    fun add(meta: ScriptMetadata) {
        seedIfNeeded()
        if (added.add(meta.scriptClass.name)) save()
    }

    fun remove(meta: ScriptMetadata) {
        seedIfNeeded()
        if (added.remove(meta.scriptClass.name)) save()
    }

    fun size(): Int {
        seedIfNeeded()
        return ScriptExecutor.scripts.values.count { it.scriptClass.name in added }
    }

    fun sourceOf(meta: ScriptMetadata): ScriptSource = sources.getOrPut(meta.scriptClass.name) {
        val jar = runCatching { File(meta.scriptClass.protectionDomain.codeSource.location.toURI()).name }.getOrDefault("")
        when {
            jar.startsWith(COMMUNITY_JAR_PREFIX) -> ScriptSource.COMMUNITY
            jar.startsWith(OFFICIAL_JAR_PREFIX) -> ScriptSource.OFFICIAL
            else -> ScriptSource.LOCAL
        }
    }

    // A config from before the Store has no library. Starting it empty would hide every script at once, so it
    // begins with the favourites and everything that is not from the community catalogue.
    private fun seedIfNeeded() {
        if (seeded || ScriptExecutor.scripts.isEmpty()) return
        seeded = true
        ScriptExecutor.scripts.values
            .filter { sourceOf(it) != ScriptSource.COMMUNITY || it.scriptClass in UIState.favoriteScripts }
            .mapTo(added) { it.scriptClass.name }
        save()
    }

    private fun save() {
        version++
        Configuration.saveLibraryScripts(added.toSet())
    }
}
