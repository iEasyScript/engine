package com.projectx.puzzle.combolock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CombinationRecipeParserTest {

    // The exact recipe read live from interface 220: amounts 100,1,500,100 are all single Roman numerals.
    private val liveRecipe = listOf(
        "An old recipe for something special...",
        "",
        " - 100 grams of Nettle tea",
        " - 1 cup of Wine",
        " - 500 grams of Rosemary",
        " - 100 grams of Bacon",
    )

    @Test
    fun allRomanAmountsGiveTheNumeralCode() {
        assertEquals("CIDC", CombinationRecipeParser.parse(liveRecipe))
    }

    @Test
    fun nonRomanAmountFallsBackToFirstLetters() {
        val recipe = listOf(
            "An old recipe for something special...",
            " - 3 grams of Nettle tea",   // 3 is not a single Roman numeral
            " - 1 cup of Wine",
            " - 500 grams of Rosemary",
            " - 100 grams of Bacon",
        )
        assertEquals("NWRB", CombinationRecipeParser.parse(recipe))
    }

    @Test
    fun commaThousandsParseAsRoman() {
        val recipe = listOf(
            " - 1,000 grams of Bacon",
            " - 50 sprigs of Mint",
            " - 10 cups of Water",
            " - 5 pinches of Salt",
        )
        assertEquals("MLXV", CombinationRecipeParser.parse(recipe))
    }

    @Test
    fun titleAndBlankLinesAreIgnored() {
        // Only four "- ... of ..." bullets are ingredients; title/blanks must not count.
        assertEquals(4, liveRecipe.count { it.trim().startsWith("-") })
        assertEquals("CIDC", CombinationRecipeParser.parse(liveRecipe))
    }

    @Test
    fun wrongIngredientCountReturnsNull() {
        assertNull(CombinationRecipeParser.parse(listOf(" - 100 grams of Bacon", " - 1 cup of Wine")))
        assertNull(CombinationRecipeParser.parse(emptyList()))
    }
}
