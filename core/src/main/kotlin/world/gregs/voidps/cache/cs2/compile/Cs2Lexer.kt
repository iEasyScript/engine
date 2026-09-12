package world.gregs.voidps.cache.cs2.compile

/** Token kinds the CS2 TypeScript subset needs. */
enum class TokenKind { IDENT, NUMBER, BIGINT, STRING, PUNCT, EOF }

data class Token(val kind: TokenKind, val text: String, val line: Int) {
    override fun toString() = "$kind($text) at line $line"
}

/**
 * Tokeniser for the TypeScript subset the decompiler emits.
 *
 * Only what that subset actually uses is supported: identifiers, decimal and
 * hex numbers, `bigint` literals, double-quoted strings with the escapes
 * the string escaping produces, line comments, and the operators
 * below.
 */
class Cs2Lexer(private val source: String) {

    private var at = 0
    private var line = 1

    private val punctuation = listOf(
        "===", "!==", "==", "!=", "<=", ">=", "&&", "||",
        "{", "}", "(", ")", "[", "]", ",", ";", ":", "=", "<", ">", "!",
        "+", "-", "*", "/", "%", "&", "|", ".",
    )

    fun tokenise(): List<Token> {
        val tokens = ArrayList<Token>()
        while (true) {
            skipTrivia()
            if (at >= source.length) {
                tokens.add(Token(TokenKind.EOF, "", line))
                return tokens
            }
            tokens.add(next())
        }
    }

    private fun skipTrivia() {
        while (at < source.length) {
            val ch = source[at]
            when {
                ch == '\n' -> { line++; at++ }
                ch.isWhitespace() -> at++
                ch == '/' && at + 1 < source.length && source[at + 1] == '/' -> {
                    while (at < source.length && source[at] != '\n') at++
                }
                ch == '/' && at + 1 < source.length && source[at + 1] == '*' -> {
                    at += 2
                    while (at + 1 < source.length && !(source[at] == '*' && source[at + 1] == '/')) {
                        if (source[at] == '\n') line++
                        at++
                    }
                    at = minOf(at + 2, source.length)
                }
                else -> return
            }
        }
    }

    private fun next(): Token {
        val ch = source[at]
        return when {
            ch.isLetter() || ch == '_' || ch == '$' -> identifier()
            ch.isDigit() -> number()
            ch == '"' -> string()
            else -> punct()
        }
    }

    private fun identifier(): Token {
        val start = at
        while (at < source.length && (source[at].isLetterOrDigit() || source[at] == '_' || source[at] == '$')) at++
        return Token(TokenKind.IDENT, source.substring(start, at), line)
    }

    private fun number(): Token {
        val start = at
        if (source.startsWith("0x", at) || source.startsWith("0X", at)) {
            at += 2
            while (at < source.length && (source[at].isLetterOrDigit())) at++
        } else {
            while (at < source.length && source[at].isDigit()) at++
        }
        if (at < source.length && source[at] == 'n') {
            at++
            return Token(TokenKind.BIGINT, source.substring(start, at - 1), line)
        }
        return Token(TokenKind.NUMBER, source.substring(start, at), line)
    }

    private fun string(): Token {
        val start = ++at
        while (at < source.length && source[at] != '"') {
            if (source[at] == '\\') at++
            at++
        }
        val body = source.substring(start, at)
        at++ // closing quote
        return Token(TokenKind.STRING, body, line)
    }

    private fun punct(): Token {
        for (candidate in punctuation) {
            if (source.startsWith(candidate, at)) {
                at += candidate.length
                return Token(TokenKind.PUNCT, candidate, line)
            }
        }
        error("Unexpected character '${source[at]}' on line $line")
    }
}
