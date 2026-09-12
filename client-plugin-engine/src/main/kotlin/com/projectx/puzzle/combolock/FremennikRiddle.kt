package com.projectx.puzzle.combolock

/**
 * Solves Peer the Seer's door riddle in The Fremennik Trials. The riddle is a six-line poem whose
 * first sentence — "My first is in <word>, but not in ..." — alone fixes the 4-letter answer word
 * entered on the [CombinationLockInterface]. Only the word right after "my first is in" matters; the
 * "but not in ..." clause is a decoy and is ignored.
 *
 * Pure and engine-independent so it can be unit-tested without the live cache.
 */
object FremennikRiddle {

    const val MARKER = "my first is in"

    private val ANSWER_BY_KEYWORD = linkedMapOf(
        "mage" to "MIND",
        "tar" to "TREE",
        "well" to "LIFE",
        "fish" to "FIRE",
        "water" to "TIME",
        "wizard" to "WIND",
    )

    /** The answer word for the riddle contained in [text], or null if none of the six lines is present. */
    fun answerFrom(text: String): String? {
        val lower = text.lowercase()
        val at = lower.indexOf(MARKER)
        if (at < 0) return null
        var rest = lower.substring(at + MARKER.length).trimStart()
        if (rest.startsWith("the ")) rest = rest.substring(4).trimStart()
        val keyword = rest.takeWhile(Char::isLetter)
        return ANSWER_BY_KEYWORD[keyword]
    }

    /**
     * Answer for channels that only expose a substring test (e.g. the recent-chat ring buffer): tries
     * each "my first is in <keyword>" prefix — safe because the decoy "but not in ..." clause never
     * begins with the marker.
     */
    fun answerMatching(contains: (String) -> Boolean): String? =
        ANSWER_BY_KEYWORD.entries.firstOrNull { (keyword, _) ->
            contains("$MARKER $keyword") || contains("$MARKER the $keyword")
        }?.value
}
