package com.projectx.puzzle.combolock

/**
 * Parses the "Old recipe" interface (220) into a 4-letter combination per the Desperate Times rule:
 * if every ingredient's amount is a single Roman numeral (1=I, 5=V, 10=X, 50=L, 100=C, 500=D, 1000=M)
 * the code is those numerals in listed order; otherwise it's the first letter of each ingredient.
 *
 * Only the structural tokens are stable — the bullet "-" and the " of " before the ingredient name.
 * Quantities, units, and names are randomized per player, so the amount is read as the first integer on
 * the line (commas stripped) and the name as everything after " of ". Pure / unit-testable.
 */
object CombinationRecipeParser {

    private val ROMAN = mapOf(1 to 'I', 5 to 'V', 10 to 'X', 50 to 'L', 100 to 'C', 500 to 'D', 1000 to 'M')
    private val AMOUNT = Regex("\\d[\\d,]*")

    private data class Ingredient(val amount: Int?, val name: String)

    fun parse(lines: List<String>, dials: Int = 4): String? {
        val ingredients = lines.mapNotNull(::parseLine)
        if (ingredients.size != dials) return null

        if (ingredients.all { it.amount != null && ROMAN.containsKey(it.amount) }) {
            return ingredients.joinToString("") { ROMAN.getValue(it.amount!!).toString() }
        }
        val initials = ingredients.map { it.name.firstOrNull(Char::isLetter)?.uppercaseChar() }
        if (initials.any { it == null }) return null
        return initials.joinToString("")
    }

    private fun parseLine(line: String): Ingredient? {
        if (!line.trim().startsWith("-")) return null
        val ofIndex = line.indexOf(" of ", ignoreCase = true)
        if (ofIndex < 0) return null
        val name = line.substring(ofIndex + 4).trim()
        if (name.isEmpty()) return null
        val amount = AMOUNT.find(line)?.value?.replace(",", "")?.toIntOrNull()
        return Ingredient(amount, name)
    }
}
