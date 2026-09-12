package com.projectx.puzzle.invention

/** Parsed contents of the discovery [track_score_text][1708:4] the server fills in per arrangement. */
class Optimisation(val level: String, val baseXp: Int, val bonusXp: Int) {
    val perfect: Boolean get() = level.equals("Perfect", ignoreCase = true)
}

object OptimisationText {

    private val LEVEL = Regex("""Optimisation:\s*([^<\r\n]+?)\s*(?:<br>|$)""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]*>""")
    private val NUMBER = Regex("""\d+""")

    /** True while the server hasn't scored the current arrangement yet (client shows "Analysing..."). */
    fun isPending(text: String): Boolean = text.contains("Analys", ignoreCase = true)

    /** Null when the text isn't a scored result (empty, pending, or unparseable). */
    fun parse(text: String): Optimisation? {
        if (text.isBlank() || isPending(text)) return null
        val level = LEVEL.find(text)?.groupValues?.get(1)?.trim() ?: return null
        val numbers = NUMBER.findAll(TAG.replace(text, " ")).map { it.value.toInt() }.toList()
        val base = numbers.getOrElse(0) { 0 }
        val bonus = numbers.getOrElse(1) { base }
        return Optimisation(level, base, bonus)
    }
}
