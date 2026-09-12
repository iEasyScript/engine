package com.projectx.puzzle.combolock

import com.projectx.script.api.interfaces

/**
 * Reads the "Old recipe" interface (220) — opened by clicking the "Old recipe" NPC — and returns the
 * combination it encodes, or null when it isn't open / can't be parsed. The recipe text lines live in
 * components 0..[MAX_TEXT_COMPONENT]; parsing is delegated to [CombinationRecipeParser].
 */
object CombinationRecipeReader {
    const val INTERFACE_ID = 220
    private const val MAX_TEXT_COMPONENT = 14

    fun isOpen(): Boolean = runCatching { interfaces.isOpen(INTERFACE_ID) }.getOrDefault(false)

    fun read(): String? {
        if (!isOpen()) return null
        val lines = (0..MAX_TEXT_COMPONENT).mapNotNull { c ->
            runCatching { interfaces.getComponent(INTERFACE_ID, c)?.text }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        return CombinationRecipeParser.parse(lines)
    }
}
