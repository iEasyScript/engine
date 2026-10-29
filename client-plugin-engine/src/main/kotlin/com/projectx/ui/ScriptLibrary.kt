package com.projectx.ui

import com.projectx.script.ScriptExecutor
import com.projectx.script.ScriptMetadata
import com.projectx.store.Entitlements
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

    // Store scripts already put in the library once, by name, so one the player has since removed stays removed.
    private val offeredFile = File(System.getProperty("user.home"), ".projectx/library-offered.txt")
    private val offered: MutableSet<String> = runCatching { offeredFile.readLines().filter { it.isNotBlank() } }
        .getOrDefault(emptyList()).toMutableSet()

    /** Bumped on every change, so a tab can cache its filtered list against it. */
    var version = 0
        private set

    fun contains(meta: ScriptMetadata): Boolean {
        seedIfNeeded()
        return meta.scriptClass.name in added || isStoreScript(meta) && added.any { simpleName(it) == meta.scriptClass.simpleName }
    }

    fun add(meta: ScriptMetadata) {
        seedIfNeeded()
        if (added.add(meta.scriptClass.name)) save()
    }

    fun remove(meta: ScriptMetadata) {
        seedIfNeeded()
        val removed = added.remove(meta.scriptClass.name) or
            (isStoreScript(meta) && added.removeAll { simpleName(it) == meta.scriptClass.simpleName })
        if (removed) save()
    }

    fun size(): Int {
        seedIfNeeded()
        return ScriptExecutor.scripts.values.count { contains(it) }
    }

    /**
     * Puts each store script the player owns into the library the first time it is seen, so a subscription made on
     * the website shows in the Scripts tab without a trip to the Store tab. Call on the game thread.
     */
    internal fun addOwned(owned: Set<String>) {
        seedIfNeeded()
        val fresh = ScriptExecutor.scripts.values.filter { it.scriptClass.simpleName in owned && it.scriptClass.simpleName !in offered }
        if (fresh.isEmpty()) return
        fresh.forEach {
            offered += it.scriptClass.simpleName
            added += it.scriptClass.name
        }
        runCatching { offeredFile.apply { parentFile.mkdirs() }.writeText(offered.sorted().joinToString("\n")) }
        save()
    }

    // The store knows a script by its simple name, so a store script moved to another package is still the same entry.
    private fun isStoreScript(meta: ScriptMetadata) = Entitlements.isPaid(meta.scriptClass.simpleName)

    private fun simpleName(className: String) = className.substringAfterLast('.')

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
