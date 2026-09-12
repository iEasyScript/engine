package com.projectx.puzzle.combolock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CombinationPlaqueReaderTest {

    @Test
    fun liveplaqueIconsResolveToFCKP() {
        // The four icon graphics read live from interface 140, left to right.
        assertEquals('F', CombinationPlaqueReader.letterForGraphic(22281)) // Falador
        assertEquals('C', CombinationPlaqueReader.letterForGraphic(22294)) // Canifis
        assertEquals('K', CombinationPlaqueReader.letterForGraphic(22298)) // Karamja
        assertEquals('P', CombinationPlaqueReader.letterForGraphic(24250)) // Prifddinas
    }

    @Test
    fun baseAndHighlightedGraphicsBothResolve() {
        // Base graphic (22233 = Lumbridge) and its highlighted variant (+46) map to the same letter.
        assertEquals('L', CombinationPlaqueReader.letterForGraphic(22233))
        assertEquals('L', CombinationPlaqueReader.letterForGraphic(22279))
        assertEquals('V', CombinationPlaqueReader.letterForGraphic(22234))
        assertEquals('A', CombinationPlaqueReader.letterForGraphic(22301)) // Ashdale (last)
    }

    @Test
    fun unknownGraphicIsNull() {
        assertNull(CombinationPlaqueReader.letterForGraphic(0))
        assertNull(CombinationPlaqueReader.letterForGraphic(12345))
    }
}
