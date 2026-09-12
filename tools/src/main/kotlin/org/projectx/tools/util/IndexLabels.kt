package org.projectx.tools.util

import world.gregs.voidps.cache.Index
import java.lang.reflect.Modifier

/**
 * Human-readable index names derived from [Index] itself, so the tooling can never drift from the
 * constants the cache library uses. Indices with no constant are reported as unidentified rather
 * than guessed at — several populated indices genuinely have no known purpose yet.
 */
object IndexLabels {

    private val byId: Map<Int, String> by lazy {
        val labels = HashMap<Int, String>()
        for (field in Index::class.java.declaredFields) {
            if (!Modifier.isStatic(field.modifiers) || field.type != Int::class.javaPrimitiveType) continue
            if (field.isAnnotationPresent(Deprecated::class.java)) continue
            field.isAccessible = true
            val id = field.getInt(null)
            val name = field.name.lowercase().replace('_', '-')
            labels.merge(id, name) { existing, _ -> existing }
        }
        labels
    }

    const val UNIDENTIFIED = "?"

    fun label(index: Int): String = byId[index] ?: UNIDENTIFIED

    fun identified(index: Int): Boolean = byId.containsKey(index)
}
