package org.projectx.core.game.chat

/**
 * Chat text handling ported from the legacy Project X server. The per-channel char caps bound how much
 * the Huffman decoder produces: the client-declared length is clamped BEFORE the decode so a bogus
 * oversized length can't be used to exhaust memory or desync the stream.
 */
object ChatFormat {
    const val MAX_PUBLIC_CHARS = 200
    const val MAX_PRIVATE_CHARS = 150

    private val SENTENCE_END = charArrayOf('?', '!', '.', ':', ';')
    private val SYMBOLS = charArrayOf(':', ';')

    /** Clamp a client-declared character count to `[0, max]` before it drives a Huffman/buffer decode. */
    fun clampCount(count: Int, max: Int): Int = count.coerceIn(0, max)

    /**
     * Legacy chat capitalisation: force-caps the first letter of the message and of each new sentence
     * (after `? ! . : ;`), keeps a capital immediately after a space (proper nouns / acronyms), and
     * lowercases any other mid-word capital so shouted text reads normally.
     */
    fun fixChatMessage(message: String): String {
        val sb = StringBuilder(message.length)
        var space = false
        var forcedCaps = true
        for (ch in message) {
            if (forcedCaps) {
                if (ch == ' ' || ch in SYMBOLS) {
                    sb.append(ch)
                } else {
                    sb.append(ch.uppercaseChar())
                    forcedCaps = false
                }
            } else when {
                ch in SENTENCE_END -> {
                    forcedCaps = true
                    sb.append(ch)
                }
                ch == ' ' -> {
                    space = true
                    sb.append(ch)
                }
                ch == ch.uppercaseChar() -> {
                    if (space) {
                        sb.append(ch)
                        space = false
                    } else {
                        sb.append(ch.lowercaseChar())
                    }
                }
                else -> {
                    sb.append(ch)
                    space = false
                }
            }
        }
        return sb.toString()
    }
}
