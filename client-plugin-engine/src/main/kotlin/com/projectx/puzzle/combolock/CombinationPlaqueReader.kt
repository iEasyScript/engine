package com.projectx.puzzle.combolock

import com.projectx.script.api.interfaces

/**
 * Reads the lodestone plaque interface (140) — opened by reading the "Plaque" scenery object — and
 * returns the combination: the first letter of each depicted lodestone, left to right.
 *
 * The four icons are components 5..8. Each is a lodestone map graphic; the plaque shows the highlighted
 * variant, which is the base graphic + [HIGHLIGHT_OFFSET] (base graphics start at [BASE_GRAPHIC]). The
 * graphic→lodestone table is authoritative (mapping supplied from the live game), not guessed.
 */
object CombinationPlaqueReader {
    const val INTERFACE_ID = 140
    private val ICON_COMPONENTS = intArrayOf(5, 6, 7, 8)

    private const val BASE_GRAPHIC = 22233
    private const val HIGHLIGHT_OFFSET = 46

    // Lodestone names in base-graphic order from 22233. The plaque shows base+46; both map to the name.
    private val NAMES = listOf(
        "Lumbridge", "Varrock", "Falador", "Ardougne", "Taverley", "Burthorpe", "Catherby",
        "Seers' Village", "Port Sarim", "Draynor Village", "Yanille", "Edgeville", "Al Kharid",
        "Bandit Camp", "Lunar Isle", "Canifis", "Eagles' Peak", "Oo'glog", "Fremennik Province",
        "Karamja", "Tirannwn", "Wilderness", "Ashdale",
    )

    private val LETTER_BY_GRAPHIC: Map<Int, Char> = buildMap {
        NAMES.forEachIndexed { i, name ->
            val letter = name.first().uppercaseChar()
            put(BASE_GRAPHIC + i, letter)
            put(BASE_GRAPHIC + HIGHLIGHT_OFFSET + i, letter)
        }
        // Prifddinas uses its own graphic sheet (base 24249, highlighted 24250).
        put(24249, 'P')
        put(24250, 'P')
    }

    /** First letter of the lodestone for an icon graphic (base or highlighted variant), or null if unknown. */
    fun letterForGraphic(graphicId: Int): Char? = LETTER_BY_GRAPHIC[graphicId]

    fun isOpen(): Boolean = runCatching { interfaces.isOpen(INTERFACE_ID) }.getOrDefault(false)

    fun read(): String? {
        if (!isOpen()) return null
        val letters = CharArray(ICON_COMPONENTS.size)
        for (i in ICON_COMPONENTS.indices) {
            val graphic = runCatching { interfaces.getComponent(INTERFACE_ID, ICON_COMPONENTS[i])?.graphicId }.getOrNull() ?: return null
            letters[i] = letterForGraphic(graphic) ?: return null
        }
        return String(letters)
    }
}
