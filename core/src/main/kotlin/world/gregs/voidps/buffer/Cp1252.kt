package world.gregs.voidps.buffer

import java.nio.charset.Charset

/**
 * RS strings are encoded by the NXT client (jag::Packet gStringCP1252ToUTF8 / pStringCP1252)
 * using the Windows-1252 (CP1252) code page, NOT UTF-8 and NOT ISO-8859-1.
 *
 * The distinction matters in the 0x80-0x9F range: e.g. byte 0x92 maps to U+2019 (right single
 * quotation mark) in CP1252, whereas ISO-8859-1 leaves 0x80-0x9F as C1 control codes and UTF-8
 * would encode U+2019 as the three bytes 0xE2 0x80 0x99.
 *
 * Only the char<->byte mapping lives here; framing (null terminators, length prefixes) is the
 * caller's responsibility.
 */
object Cp1252 {
    val charset: Charset = Charset.forName("windows-1252")

    fun encode(value: String): ByteArray = value.toByteArray(charset)

    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String =
        String(bytes, offset, length, charset)
}
